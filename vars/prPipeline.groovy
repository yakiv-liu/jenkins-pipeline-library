def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)

    pipeline {
        agent {
            label config.agentLabel
        }

        triggers {
            pullRequest(
                    org: config.org,
                    repo: config.repo,
                    branch: config.defaultBranch ?: 'main',
                    triggerPhrase: '.*test.*',
                    onlyTriggerPhrase: false,
                    githubApiUrl: 'https://api.github.com',
                    successComment: 'PR验证通过，可以合并',
                    failureComment: 'PR验证失败，请检查构建日志',
                    skipFirstBuild: false,
                    cancelBuildsOnUpdate: true
            )
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '8'))
            disableConcurrentBuilds()
        }

        environment {
            NEXUS_URL = "${config.nexusUrl}"
            SONAR_URL = "${config.sonarUrl}"
            TRIVY_URL = "${config.trivyUrl}"
            HARBOR_URL = "${config.harborUrl}"
        }

        stages {
            stage('Checkout PR') {
                steps {
                    checkout([
                            $class: 'GitSCM',
                            branches: [[name: 'refs/pull/${CHANGE_ID}/head']],
                            extensions: [
                                    [$class: 'CleanCheckout'],
                                    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'src']
                            ],
                            userRemoteConfigs: [[
                                                        refspec: '+refs/pull/*:refs/remotes/origin/pr/*',
                                                        url: "https://github.com/${config.org}/${config.repo}.git",
                                                        credentialsId: 'github-token'
                                                ]]
                    ])

                    dir('src') {
                        sh 'git log -1 --oneline'
                    }
                }
            }

            stage('Parallel Security & Build') {
                parallel {
                    stage('Security Scan') {
                        steps {
                            dir('src') {
                                script {
                                    // 修正：正确传递 steps 和 env
                                    def securityTools = new org.yakiv.SecurityTools(steps, env)
                                    securityTools.runPRSecurityScan(
                                            projectName: config.projectName,
                                            changeId: env.CHANGE_ID,
                                            changeBranch: env.CHANGE_BRANCH,
                                            changeTarget: env.CHANGE_TARGET
                                    )
                                }
                            }
                        }
                        post {
                            success {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'SUCCESS',
                                            context: 'security-scan',
                                            description: '安全扫描通过',
                                            targetUrl: "${env.BUILD_URL}"
                                    )
                                }
                            }
                            failure {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'FAILURE',
                                            context: 'security-scan',
                                            description: '安全扫描失败',
                                            targetUrl: "${env.BUILD_URL}"
                                    )
                                }
                            }
                        }
                    }

                    stage('Build & Test') {
                        steps {
                            dir('src') {
                                script {
                                    // 修正：正确传递 steps 和 env
                                    def buildTools = new org.yakiv.BuildTools(steps, env)
                                    buildTools.runPRBuildAndTest()
                                }
                            }
                        }
                        post {
                            success {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'SUCCESS',
                                            context: 'build',
                                            description: '构建测试通过',
                                            targetUrl: "${env.BUILD_URL}"
                                    )

                                    junit 'src/target/surefire-reports/*.xml'
                                    publishHTML([
                                            allowMissing: false,
                                            alwaysLinkToLastBuild: true,
                                            keepAll: true,
                                            reportDir: 'src/target/site',
                                            reportFiles: 'surefire-report.html,jacoco/index.html',
                                            reportName: '测试报告'
                                    ])
                                }
                            }
                            failure {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'FAILURE',
                                            context: 'build',
                                            description: '构建测试失败',
                                            targetUrl: "${env.BUILD_URL}"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    dir('src') {
                        script {
                            timeout(time: 10, unit: 'MINUTES') {
                                def qg = waitForQualityGate()
                                if (qg.status != 'OK') {
                                    error "质量门未通过: ${qg.status}"
                                }
                            }
                        }
                    }
                }
                post {
                    success {
                        script {
                            updateGitHubCommitStatus(
                                    state: 'SUCCESS',
                                    context: 'quality-gate',
                                    description: '质量门检查通过',
                                    targetUrl: "${env.BUILD_URL}"
                            )
                        }
                    }
                    failure {
                        script {
                            updateGitHubCommitStatus(
                                    state: 'FAILURE',
                                    context: 'quality-gate',
                                    description: '质量门检查失败',
                                    targetUrl: "${env.BUILD_URL}"
                            )
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    if (currentBuild.result == 'SUCCESS') {
                        githubPRComment comment: "✅ PR验证通过！所有检查均成功完成。\n\n- ✅ 安全扫描通过\n- ✅ 构建测试通过\n- ✅ 质量门检查通过\n\n构建详情: ${env.BUILD_URL}"
                    } else if (currentBuild.result == 'FAILURE') {
                        githubPRComment comment: "❌ PR验证失败！请检查以下问题：\n\n- 🔍 查看构建日志: ${env.BUILD_URL}\n- 📊 查看测试报告: ${env.BUILD_URL}testReport/\n- 🛡️ 查看安全扫描结果: ${config.sonarUrl}/dashboard?id=${config.projectName}-pr-${env.CHANGE_ID}"
                    }

                    cleanWs()
                }
            }
        }
    }
}