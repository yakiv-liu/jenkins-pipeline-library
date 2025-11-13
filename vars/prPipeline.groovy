def call(Map userConfig = [:]) {
    // ========== 配置合并逻辑 ==========
    def config = [:]

    try {
        def configInstance = new org.yakiv.Config(steps)
        config = configInstance.mergeConfig(userConfig)
        echo "✅ 使用共享库配置合并"
    } catch (Exception e) {
        echo "⚠️ 共享库配置合并失败，使用备用配置: ${e.message}"
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
        config.putAll(userConfig)
    }

    // ========== 判断构建类型 ==========
    def isPR = env.BRANCH_NAME && env.BRANCH_NAME.startsWith('PR-')
    def prNumber = isPR ? env.BRANCH_NAME.replace('PR-', '') : null

    // ========== 完整的 pipeline 定义 ==========
    pipeline {
        agent {
            label config.agentLabel
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '8'))
            disableConcurrentBuilds()
            githubProjectProperty(projectUrlStr: "https://github.com/${config.org}/${config.repo}/")
            retry(3) // 构建失败时重试3次
        }

        environment {
            NEXUS_URL = "${config.nexusUrl}"
            SONAR_URL = "${config.sonarUrl}"
            TRIVY_URL = "${config.trivyUrl}"
            HARBOR_URL = "${config.harborUrl}"
            PROJECT_DIR = "src"
            SCAN_INTENSITY = "${config.scanIntensity}"
            IS_PR = "${isPR}"
            GIT_TIMEOUT = "10"
        }

        stages {
            stage('Check Build Type') {
                steps {
                    script {
                        echo "=== 构建类型检测 ==="
                        echo "BRANCH_NAME: ${env.BRANCH_NAME}"
                        echo "GIT_BRANCH: ${env.GIT_BRANCH}"

                        if (isPR) {
                            echo "✅ 确认：这是 PR #${prNumber} 构建"
                            echo "构建类型：Pull Request 验证"
                        } else {
                            echo "✅ 确认：这是分支构建"
                            echo "构建分支：${env.BRANCH_NAME}"
                            echo "构建类型：分支流水线"
                        }

                        def causes = currentBuild.getBuildCauses()
                        echo "构建原因:"
                        causes.each { cause ->
                            echo " - ${cause.shortDescription ?: cause.toString()}"
                        }
                    }
                }
            }

            stage('Checkout Code') {
                steps {
                    script {
                        echo "开始检出代码..."

                        def checkoutSuccess = false
                        def retryCount = 0
                        def maxRetries = 5

                        while (!checkoutSuccess && retryCount < maxRetries) {
                            retryCount++
                            echo "尝试检出代码 (第 ${retryCount} 次)"

                            try {
                                timeout(time: 5, unit: 'MINUTES') {
                                    checkout([
                                            $class: 'GitSCM',
                                            branches: [[name: env.BRANCH_NAME]],
                                            extensions: [
                                                    [$class: 'CleanCheckout'],
                                                    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'src'],
                                                    [$class: 'CloneOption',
                                                     timeout: 5,
                                                     depth: 1,
                                                     noTags: true,
                                                     shallow: true],
                                                    [$class: 'LocalBranch', localBranch: '**']
                                            ],
                                            userRemoteConfigs: [[
                                                                        url: "https://github.com/${config.org}/${config.repo}.git",
                                                                        credentialsId: 'github-token',
                                                                        timeout: 10
                                                                ]]
                                    ])
                                }
                                checkoutSuccess = true
                                echo "✅ 代码检出成功"
                            } catch (Exception e) {
                                echo "⚠️ 代码检出失败 (第 ${retryCount} 次): ${e.message}"
                                if (retryCount < maxRetries) {
                                    sleep time: 10, unit: 'SECONDS'
                                } else {
                                    error "代码检出失败，已重试 ${maxRetries} 次"
                                }
                            }
                        }

                        dir('src') {
                            sh 'git log -1 --oneline'
                            sh 'git branch -a'
                            sh 'ls -la || echo "目录为空"'
                        }

                        echo "构建详情:"
                        echo "BRANCH_NAME: ${env.BRANCH_NAME}"
                        echo "IS_PR: ${env.IS_PR}"
                        echo "SCAN_INTENSITY: ${env.SCAN_INTENSITY}"
                    }
                }
            }

            stage('Parallel Security & Build') {
                when {
                    expression { fileExists('src') }
                }
                parallel {
                    stage('Security Scan') {
                        steps {
                            dir('src') {
                                script {
                                    try {
                                        def securityTools = new org.yakiv.SecurityTools(steps, env)
                                        securityTools.runPRSecurityScan(
                                                projectName: config.projectName,
                                                isPR: isPR,
                                                prNumber: prNumber,
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
                echo "Pipeline 执行完成 - 结果: ${currentBuild.result}"
            }
            success {
                echo "✅ Pipeline 执行成功"
                script {
                    if (isPR && prNumber) {
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
                echo "❌ Pipeline 执行失败"
                script {
                    if (isPR && prNumber) {
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
            unstable {
                echo "⚠️ Pipeline 执行不稳定"
                script {
                    if (isPR && prNumber) {
                        try {
                            githubPRComment comment: """⚠️ PR验证不稳定！部分检查未通过。

📊 **构建详情**: ${env.BUILD_URL}

请检查测试报告和安全扫描结果，修复问题后重新触发构建。"""
                        } catch (Exception e) {
                            echo "⚠️ PR评论发送失败: ${e.message}"
                        }
                    }
                }
            }
        }
    }
}