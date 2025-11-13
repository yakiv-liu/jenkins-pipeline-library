def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)

    echo "=== Pipeline 详细检测 ==="
    echo "BRANCH_NAME: ${env.BRANCH_NAME}"
    echo "GIT_BRANCH: ${env.GIT_BRANCH}"

    // ========== 修改点3：在共享库中也使用 BRANCH_NAME 判断 ==========
    def isPR = env.BRANCH_NAME && env.BRANCH_NAME.startsWith('PR-')

    if (isPR) {
        def prNumber = env.BRANCH_NAME.replace('PR-', '')
        echo "✅ 确认：PR #${prNumber} 流水线"
        config.prNumber = prNumber
    } else {
        echo "✅ 确认：分支流水线 - ${env.BRANCH_NAME}"
    }

    pipeline {
        agent {
            label config.agentLabel
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '8'))
            disableConcurrentBuilds()
            githubProjectProperty(projectUrlStr: "https://github.com/${config.org}/${config.repo}/")
        }

        environment {
            NEXUS_URL = "${config.nexusUrl}"
            SONAR_URL = "${config.sonarUrl}"
            TRIVY_URL = "${config.trivyUrl}"
            HARBOR_URL = "${config.harborUrl}"
            PROJECT_DIR = "src"
            SCAN_INTENSITY = "${config.scanIntensity ?: 'standard'}"
            // ========== 修改点4：设置 IS_PR 环境变量供后续步骤使用 ==========
            IS_PR = "${isPR}"
        }

        stages {
            stage('Checkout Code') {
                steps {
                    script {
                        echo "开始检出代码..."

                        // ========== 修改点5：简化检出逻辑，Multibranch 会自动处理 ==========
                        checkout([
                                $class: 'GitSCM',
                                branches: [[name: env.BRANCH_NAME]],
                                extensions: [
                                        [$class: 'CleanCheckout'],
                                        [$class: 'RelativeTargetDirectory', relativeTargetDir: 'src']
                                ],
                                userRemoteConfigs: [[
                                                            url: "https://github.com/${config.org}/${config.repo}.git",
                                                            credentialsId: 'github-token'
                                                    ]]
                        ])

                        dir('src') {
                            sh 'git log -1 --oneline'
                            sh 'git branch -a'
                        }

                        echo "构建详情:"
                        echo "BRANCH_NAME: ${env.BRANCH_NAME}"
                        echo "IS_PR: ${env.IS_PR}"
                        echo "SCAN_INTENSITY: ${env.SCAN_INTENSITY}"
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
                                    // ========== 修改点6：传递 IS_PR 信息 ==========
                                    securityTools.runPRSecurityScan(
                                            projectName: config.projectName,
                                            isPR: env.IS_PR.toBoolean(),
                                            prNumber: config.prNumber,
                                            branchName: env.BRANCH_NAME,
                                            skipDependencyCheck: config.skipDependencyCheck,
                                            scanIntensity: env.SCAN_INTENSITY
                                    )
                                }
                            }
                        }
                        post {
                            always {
                                script {
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
            }
        }

        post {
            always {
                cleanWs()
            }
            success {
                script {
                    // ========== 修改点7：只在 PR 构建时发送评论 ==========
                    if (env.IS_PR.toBoolean() && config.prNumber) {
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
                    if (env.IS_PR.toBoolean() && config.prNumber) {
                        githubPRComment comment: """❌ PR验证失败！请检查以下问题：

                            📊 **构建详情**: ${env.BUILD_URL}
                            
                            请查看构建日志和安全扫描报告，修复问题后重新触发构建。
                        """
                    }
                }
            }
        }
    }
}