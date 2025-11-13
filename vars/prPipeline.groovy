def call(Map userConfig = [:]) {
    // ========== 修改点1：修复配置合并方法调用 ==========
    def config = [:]

    try {
        // 正确调用共享库的配置合并方法
        def configInstance = new org.yakiv.Config(steps)
        config = configInstance.mergeConfig(userConfig)
        echo "✅ 使用共享库配置合并"
    } catch (Exception e) {
        echo "⚠️ 共享库配置合并失败，使用备用配置: ${e.message}"
        // 备用方案：手动设置基本配置
        config = [
                projectName: userConfig.projectName ?: 'demo-helloworld',
                org: userConfig.org ?: 'yakiv-liu',
                repo: userConfig.repo ?: 'demo-helloworld',
                agentLabel: userConfig.agentLabel ?: 'docker-jnlp-slave',
                defaultBranch: userConfig.defaultBranch ?: 'main',
                defaultEmail: userConfig.defaultEmail ?: '251934304@qq.com',
                skipDependencyCheck: userConfig.skipDependencyCheck ?: true,
                scanIntensity: userConfig.scanIntensity ?: 'standard',
                nexusUrl: userConfig.nexusUrl ?: 'https://nexus.example.com',
                sonarUrl: userConfig.sonarUrl ?: 'https://sonar.example.com',
                trivyUrl: userConfig.trivyUrl ?: 'https://trivy.example.com',
                harborUrl: userConfig.harborUrl ?: 'https://harbor.example.com'
        ]
        config.putAll(userConfig) // 确保用户配置覆盖默认值
    }

    echo "=== Pipeline 详细检测 ==="
    echo "BRANCH_NAME: ${env.BRANCH_NAME}"
    echo "GIT_BRANCH: ${env.GIT_BRANCH}"

    // 在共享库中也使用 BRANCH_NAME 判断
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
            // 设置 IS_PR 环境变量供后续步骤使用
            IS_PR = "${isPR}"
        }

        stages {
            stage('Checkout Code') {
                steps {
                    script {
                        echo "开始检出代码..."

                        // 简化检出逻辑，Multibranch 会自动处理
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
                                    // ========== 修改点2：安全地调用 SecurityTools ==========
                                    try {
                                        def securityTools = new org.yakiv.SecurityTools(steps, env)
                                        // 传递 IS_PR 信息
                                        securityTools.runPRSecurityScan(
                                                projectName: config.projectName,
                                                isPR: env.IS_PR.toBoolean(),
                                                prNumber: config.prNumber,
                                                branchName: env.BRANCH_NAME,
                                                skipDependencyCheck: config.skipDependencyCheck,
                                                scanIntensity: env.SCAN_INTENSITY
                                        )
                                    } catch (Exception e) {
                                        echo "⚠️ 安全扫描失败: ${e.message}"
                                        error "安全扫描步骤执行失败"
                                    }
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
                                    // ========== 修改点3：安全地调用 BuildTools ==========
                                    try {
                                        def buildTools = new org.yakiv.BuildTools(steps, env)
                                        buildTools.runPRBuildAndTest()
                                    } catch (Exception e) {
                                        echo "⚠️ 构建测试失败: ${e.message}"
                                        error "构建测试步骤执行失败"
                                    }
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
                            try {
                                def qg = waitForQualityGate()
                                if (qg.status != 'OK') {
                                    error "质量门未通过: ${qg.status}"
                                }
                            } catch (Exception e) {
                                echo "⚠️ 质量门检查失败: ${e.message}"
                                error "质量门检查执行失败"
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
                    // 只在 PR 构建时发送评论
                    if (env.IS_PR.toBoolean() && config.prNumber) {
                        try {
                            githubPRComment comment: """✅ PR验证通过！所有检查均成功完成。

📊 **构建详情**: ${env.BUILD_URL}

### 检查结果:
- ✅ 安全扫描通过 (${env.SCAN_INTENSITY}模式)
- ✅ 构建测试通过  
- ✅ 质量门检查通过
- ⚡ 依赖检查: ${config.skipDependencyCheck ? '已跳过' : '已执行'}

**注意**: 只有通过所有质量检查才允许合并。"""
                        } catch (Exception e) {
                            echo "⚠️ PR评论发送失败: ${e.message}"
                        }
                    }
                }
            }
            failure {
                script {
                    if (env.IS_PR.toBoolean() && config.prNumber) {
                        try {
                            githubPRComment comment: """❌ PR验证失败！请检查以下问题：

📊 **构建详情**: ${env.BUILD_URL}

请查看构建日志和安全扫描报告，修复问题后重新触发构建。"""
                        } catch (Exception e) {
                            echo "⚠️ PR评论发送失败: ${e.message}"
                        }
                    }
                }
            }
        }
    }
}