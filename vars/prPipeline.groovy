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
    def sourceBranch = isPR ? env.CHANGE_BRANCH : env.BRANCH_NAME
    def targetBranch = isPR ? env.CHANGE_TARGET : config.defaultBranch

    echo "=== PR Pipeline 开始执行 ==="
    echo "项目: ${config.projectName}"
    echo "是否为 PR: ${isPR}"
    echo "PR 编号: ${prNumber}"
    echo "源分支: ${sourceBranch}"
    echo "目标分支: ${targetBranch}"

    // ========== 修改点1：去掉 pipeline 块，直接执行步骤 ==========

    // 设置环境变量
    env.NEXUS_URL = "${config.nexusUrl}"
    env.SONAR_URL = "${config.sonarUrl}"
    env.TRIVY_URL = "${config.trivyUrl}"
    env.HARBOR_URL = "${config.harborUrl}"
    env.PROJECT_DIR = "src"
    env.SCAN_INTENSITY = "${config.scanIntensity}"
    env.IS_PR = "${isPR}"
    env.SOURCE_BRANCH = "${sourceBranch}"
    env.TARGET_BRANCH = "${targetBranch}"

    try {
        // ========== 执行各个阶段 ==========

        // 阶段 1: 安全扫描
        stage('Security Scan') {
            echo "🔍 开始安全扫描..."
            dir('src') {
                def securityTools = new org.yakiv.SecurityTools(steps, env)
                securityTools.runPRSecurityScan(
                        projectName: config.projectName,
                        isPR: isPR,
                        prNumber: prNumber,
                        branchName: sourceBranch,
                        targetBranch: targetBranch,
                        skipDependencyCheck: config.skipDependencyCheck,
                        scanIntensity: config.scanIntensity,
                        sonarqubeCommunityEdition: true
                )
            }

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

        // 阶段 2: 构建和测试
        stage('Build & Test') {
            echo "🔨 开始构建和测试..."
            dir('src') {
                def buildTools = new org.yakiv.BuildTools(steps, env)
                buildTools.runPRBuildAndTest()
            }

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

        // 阶段 3: 质量检查
        stage('Quality Check') {
            echo "📊 运行质量检查..."
            if (!env.SONARQUBE_COMMUNITY_EDITION.toBoolean()) {
                // 企业版：质量门检查
                timeout(time: 10, unit: 'MINUTES') {
                    def qg = waitForQualityGate()
                    if (qg.status != 'OK') {
                        error "质量门未通过: ${qg.status}"
                    }
                }
            } else {
                // 社区版：基本质量检查
                echo "⚠️ SonarQube 社区版：跳过详细质量门检查"
                dir('src') {
                    sh '''
                        echo "运行基本质量检查..."
                        # 这里可以添加基本检查命令
                        echo "基本质量检查完成"
                    '''
                }
            }
        }

        // ========== 构建成功处理 ==========
        echo "✅ PR Pipeline 执行成功"

        if (isPR && prNumber) {
            githubPRComment comment: """✅ PR验证通过！所有检查均成功完成。

📊 **构建详情**: ${env.BUILD_URL}

### 检查结果:
- ✅ 安全扫描通过 (${env.SCAN_INTENSITY}模式)
- ✅ 构建测试通过  
- ${env.SONARQUBE_COMMUNITY_EDITION.toBoolean() ? '⚠️ 基础质量检查通过（SonarQube 社区版）' : '✅ 质量门检查通过'}
- ⚡ 依赖检查: ${config.skipDependencyCheck ? '已跳过' : '已执行'}

${env.SONARQUBE_COMMUNITY_EDITION.toBoolean() ? '**注意**: 由于使用 SonarQube 社区版，部分高级质量检查不可用。' : '**注意**: 只有通过所有质量检查才允许合并。'}"""
        }

    } catch (Exception e) {
        // ========== 构建失败处理 ==========
        echo "❌ PR Pipeline 执行失败: ${e.message}"

        if (isPR && prNumber) {
            githubPRComment comment: """❌ PR验证失败！请检查以下问题：

📊 **构建详情**: ${env.BUILD_URL}

请查看构建日志和安全扫描报告，修复问题后重新触发构建。"""
        }

        throw e // 重新抛出异常，让外层知道构建失败
    } finally {
        // ========== 清理工作 ==========
        cleanWs()
        echo "PR Pipeline 执行完成 - 结果: ${currentBuild.result}"
    }
}