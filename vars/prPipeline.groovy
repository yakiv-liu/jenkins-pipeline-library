def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)
    // 检查构建类型 - 如果不是PR事件则中止
    if (!env.CHANGE_ID) {
        error "🚫 pr-pipeline 仅处理 Pull Request 事件。当前构建不是PR触发的。"
    }

    echo "✅ 确认：这是 PR #${env.CHANGE_ID} 事件，继续执行PR流水线"
    echo "PR 源分支: ${env.CHANGE_BRANCH}"
    echo "PR 目标分支: ${env.CHANGE_TARGET}"
    pipeline {
        agent {
            label config.agentLabel
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '8'))
            disableConcurrentBuilds()
            // 添加 GitHub 项目链接
            githubProjectProperty(projectUrlStr: "https://github.com/${config.org}/${config.repo}/")
        }

        environment {
            NEXUS_URL = "${config.nexusUrl}"
            SONAR_URL = "${config.sonarUrl}"
            TRIVY_URL = "${config.trivyUrl}"
            HARBOR_URL = "${config.harborUrl}"
            PROJECT_DIR = "src"
            SCAN_INTENSITY = "${config.scanIntensity ?: 'standard'}"
        }

        stages {
            stage('Checkout PR') {
                steps {
                    script {
                        checkout([
                                $class: 'GitSCM',
                                branches: [[name: 'refs/pull/${CHANGE_ID}/head']],
                                extensions: [
                                        [$class: 'CleanCheckout'],
                                        [$class: 'RelativeTargetDirectory', relativeTargetDir: 'src'],
                                        [$class: 'LocalBranch', localBranch: 'PR-${CHANGE_ID}']
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

                        // 调试信息
                        echo "PR Environment Variables:"
                        echo "CHANGE_ID: ${env.CHANGE_ID}"
                        echo "CHANGE_BRANCH: ${env.CHANGE_BRANCH}"
                        echo "CHANGE_TARGET: ${env.CHANGE_TARGET}"
                        echo "SCAN_INTENSITY: ${env.SCAN_INTENSITY}"
                        echo "SKIP_DEPENDENCY_CHECK: ${config.skipDependencyCheck}"
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
                                            changeTarget: env.CHANGE_TARGET,
                                            skipDependencyCheck: config.skipDependencyCheck,
                                            scanIntensity: env.SCAN_INTENSITY
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
                    if (env.CHANGE_ID) {
                        // PR 成功评论
                        githubPRComment comment: """✅ PR验证通过！所有检查均成功完成。

                        📊 **构建详情**: ${env.BUILD_URL}
                        
                        ### 检查结果:
                        - ✅ 安全扫描通过 (${env.SCAN_INTENSITY}模式)
                        - ✅ 构建测试通过  
                        - ✅ 质量门检查通过
                        - ⚡ 依赖检查: ${config.skipDependencyCheck ? '已跳过' : '已执行'}
                        
                        **注意**: 只有通过所有质量检查才允许合并。"""
                    }
                }
            }
            failure {
                script {
                    if (env.CHANGE_ID) {
                        // PR 失败评论
                        githubPRComment comment: """❌ PR验证失败！请检查以下问题：

                        📊 **构建详情**: ${env.BUILD_URL}
                        
                        ### 失败项目:
                        - 🔍 查看构建日志: ${env.BUILD_URL}console
                        - 🛡️ 安全扫描结果: ${env.BUILD_URL}security-scan/
                        - ⚗️ 测试报告: ${env.BUILD_URL}testReport/
                        - 📈 质量门结果: ${config.sonarUrl}/dashboard?id=${config.projectName}-pr-${env.CHANGE_ID}
                        
                        **重要**: 此PR未通过质量门禁，只允许force merge。"""
                    }
                }
            }
            unstable {
                script {
                    if (env.CHANGE_ID) {
                        // PR 不稳定评论
                        githubPRComment comment: """⚠️ PR验证不稳定！部分检查未通过。

                        📊 **构建详情**: ${env.BUILD_URL}
                        
                        请检查测试报告和安全扫描结果，修复问题后重新触发构建。"""
                    }
                }
            }
        }
    }
}