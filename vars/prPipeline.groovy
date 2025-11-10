def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)

    pipeline {
        agent {
            label config.agentLabel
        }

        // 移除无效的 triggers 块，改为通过 GitHub webhook 触发
        // triggers 配置应该在 Jenkinsfile 或 Jenkins 任务配置中设置

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
            PROJECT_DIR = "src"  // 添加项目目录环境变量
        }

        stages {
            stage('Checkout PR') {
                steps {
                    script {
                        // 使用 checkout scm 来获取 PR 代码
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
                            // 设置项目目录环境变量
                            env.PROJECT_DIR = "src"
                        }
                    }
                }
            }

            stage('Parallel Security & Build') {
                parallel {
                    stage('Security Scan') {
                        steps {
                            dir('src') {
                                script {
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
                            always {
                                script {
                                    // 发布安全扫描报告
                                    publishHTML([
                                            allowMissing: true,
                                            alwaysLinkToLastBuild: true,
                                            keepAll: true,
                                            reportDir: 'src/target',
                                            reportFiles: 'dependency-check-report.html,trivy-report.html',
                                            reportName: '安全扫描报告'
                                    ])
                                }
                            }
                            success {
                                script {
                                    // 更新 GitHub 状态
                                    updateGitHubCommitStatus(
                                            state: 'SUCCESS',
                                            context: 'security-scan',
                                            description: '安全扫描通过',
                                            targetUrl: "${env.BUILD_URL}security-scan/"
                                    )
                                }
                            }
                            failure {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'FAILURE',
                                            context: 'security-scan',
                                            description: '安全扫描失败',
                                            targetUrl: "${env.BUILD_URL}security-scan/"
                                    )
                                }
                            }
                        }
                    }

                    stage('Build & Test') {
                        steps {
                            dir('src') {
                                script {
                                    def buildTools = new org.yakiv.BuildTools(steps, env)
                                    buildTools.runPRBuildAndTest()
                                }
                            }
                        }
                        post {
                            always {
                                script {
                                    // 发布测试报告
                                    junit allowEmptyResults: true, testResults: 'src/target/surefire-reports/*.xml'
                                    publishHTML([
                                            allowMissing: true,
                                            alwaysLinkToLastBuild: true,
                                            keepAll: true,
                                            reportDir: 'src/target/site',
                                            reportFiles: 'surefire-report.html,jacoco/index.html',
                                            reportName: '测试报告'
                                    ])
                                }
                            }
                            success {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'SUCCESS',
                                            context: 'build',
                                            description: '构建测试通过',
                                            targetUrl: "${env.BUILD_URL}testReport/"
                                    )
                                }
                            }
                            failure {
                                script {
                                    updateGitHubCommitStatus(
                                            state: 'FAILURE',
                                            context: 'build',
                                            description: '构建测试失败',
                                            targetUrl: "${env.BUILD_URL}testReport/"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                steps {
                    script {
                        timeout(time: 10, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error "质量门未通过: ${qg.status}"
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
                    // 清理工作空间
                    cleanWs()
                }
            }
            success {
                script {
                    // PR 成功评论
                    if (env.CHANGE_ID) {
                        githubPRComment comment: """✅ PR验证通过！所有检查均成功完成。
                            - ✅ 安全扫描通过
                            - ✅ 构建测试通过  
                            - ✅ 质量门检查通过
                            构建详情: ${env.BUILD_URL}
                        """
                    }
                }
            }
            failure {
                script {
                    // PR 失败评论
                    if (env.CHANGE_ID) {
                        githubPRComment comment: """ ❌ PR验证失败！请检查以下问题：
                            - 🔍 查看构建日志: ${env.BUILD_URL}
                            - 📊 查看测试报告: ${env.BUILD_URL}testReport/
                            - 🛡️ 查看安全扫描结果: ${env.BUILD_URL}security-scan/
                            **重要**: 只有质量门禁和安全扫描通过才允许合并（force merge 除外）。
                        """
                    }
                }
            }
            unstable {
                script {
                    // PR 不稳定评论
                    if (env.CHANGE_ID) {
                        githubPRComment comment: """⚠️ PR验证不稳定！部分检查未通过。
                            - 📊 查看测试报告: ${env.BUILD_URL}testReport/
                            - 🛡️ 查看安全扫描结果: ${env.BUILD_URL}security-scan/
                            请检查相关问题后重试。
                        """
                    }
                }
            }
        }
    }
}