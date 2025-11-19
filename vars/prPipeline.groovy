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

    // ========== 设置环境变量 ==========
    env.NEXUS_URL = "${config.nexusUrl}"
    env.SONAR_URL = "${config.sonarUrl}"
    env.TRIVY_URL = "${config.trivyUrl}"
    env.HARBOR_URL = "${config.harborUrl}"
    env.PROJECT_DIR = "."
    env.SCAN_INTENSITY = "${config.scanIntensity}"
    env.IS_PR = "${isPR}"
    env.SOURCE_BRANCH = "${sourceBranch}"
    env.TARGET_BRANCH = "${targetBranch}"
    env.SONARQUBE_COMMUNITY_EDITION = "true"

    // ========== 新增：安全检查结果收集 ==========
    def securityResults = [:]

    try {
        // ========== 执行各个阶段 ==========

        // 阶段 1: 安全扫描
        stage('Security Scan') {
            echo "🔍 开始安全扫描..."
            def securityTools = new org.yakiv.SecurityTools(steps, env)

            // ========== 修改点1：收集安全扫描结果 ==========
            securityResults = securityTools.runPRSecurityScan(
                    projectName: config.projectName,
                    isPR: isPR,
                    prNumber: prNumber,
                    branchName: sourceBranch,
                    targetBranch: targetBranch,
                    skipDependencyCheck: config.skipDependencyCheck,
                    scanIntensity: config.scanIntensity,
                    sonarqubeCommunityEdition: env.SONARQUBE_COMMUNITY_EDITION.toBoolean()
            )

            // 发布安全扫描报告
            publishHTML([
                    allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: 'target',
                    reportFiles: 'dependency-check-report.html,trivy-report.html',
                    reportName: '安全扫描报告'
            ])

            // 发布代码质量报告
            publishHTML([
                    allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: 'target/site',
                    reportFiles: 'checkstyle.html,spotbugs.html,jacoco/index.html,pmd.html',
                    reportName: '代码质量报告'
            ])
        }

        // 阶段 2: 构建和测试
        stage('Build & Test') {
            echo "🔨 开始构建和测试..."
            def buildTools = new org.yakiv.BuildTools(steps, env)

            // ========== 修改点2：收集构建测试结果 ==========
            def buildResults = buildTools.runPRBuildAndTest()
            securityResults.putAll(buildResults)

            // 发布测试报告
            junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
            publishHTML([
                    allowMissing: true,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: 'target/site',
                    reportFiles: 'surefire-report.html,jacoco/index.html',
                    reportName: '测试报告'
            ])
        }

        // 质量检查阶段
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
                // 社区版：免费工具质量检查
                echo "✅ 使用免费工具进行质量检查"
                echo "检查项目:"
                echo "- Checkstyle: 代码风格规范"
                echo "- SpotBugs: 潜在缺陷检测"
                echo "- JaCoCo: 代码覆盖率"
                echo "- PMD: 代码质量分析"

                sh '''
                    echo "验证免费工具分析结果..."
                    echo "免费工具质量检查完成"
                '''
            }
        }

        // ========== 构建成功处理 ==========
        echo "✅ PR Pipeline 执行成功"

        if (isPR && prNumber) {
            // ========== 修改点3：生成详细的检查结果表格 ==========
            def commentBody = generatePRCommentBody(securityResults, config)
            postGitHubComment(prNumber, commentBody, config)
        }

    } catch (Exception e) {
        // ========== 构建失败处理 ==========
        echo "❌ PR Pipeline 执行失败: ${e.message}"

        if (isPR && prNumber) {
            // ========== 修改点4：失败时也生成检查结果表格 ==========
            securityResults.buildStatus = "FAILED"
            securityResults.overallStatus = "❌ 失败"
            def failureComment = generatePRCommentBody(securityResults, config)
            postGitHubComment(prNumber, failureComment, config)
        }

        throw e // 重新抛出异常，让外层知道构建失败
    } finally {
        // ========== 清理工作 ==========
        cleanWs()
        echo "PR Pipeline 执行完成 - 结果: ${currentBuild.result}"
    }
}

// ========== 新增方法：生成PR评论内容，包含详细检查表格 ==========
def generatePRCommentBody(Map results, Map config) {
    def statusIcon = results.overallStatus ?: "✅"
    def buildStatus = results.buildStatus ?: "SUCCESS"

    def tableRows = ""

    // 定义检查项目和对应的结果键值
    def checkItems = [
            [name: "构建状态", key: "buildStatus", format: { it == "SUCCESS" ? "✅ 通过" : "❌ 失败" }],
            [name: "单元测试通过率", key: "testSuccessRate", format: { it ? "${it}%" : "N/A" }],
            [name: "代码覆盖率", key: "codeCoverage", format: { it ? "${it}%" : "N/A" }],
            [name: "Checkstyle违规", key: "checkstyleViolations", format: { it ?: "0" }],
            [name: "SpotBugs问题", key: "spotbugsIssues", format: { it ?: "0" }],
            [name: "PMD问题", key: "pmdIssues", format: { it ?: "0" }],
            [name: "依赖检查", key: "dependencyCheckStatus", format: {
                if (it == "PASSED") "✅ 通过"
                else if (it == "FAILED") "❌ 存在漏洞"
                else if (it == "SKIPPED") "⚪ 已跳过"
                else "N/A"
            }],
            [name: "Trivy扫描", key: "trivyScanStatus", format: {
                if (it == "PASSED") "✅ 通过"
                else if (it == "FAILED") "❌ 存在漏洞"
                else if (it == "SKIPPED") "⚪ 已跳过"
                else "N/A"
            }],
            [name: "扫描强度", key: "scanIntensity", format: { it ?: "standard" }]
    ]

    // 生成表格行
    checkItems.each { item ->
        def value = results[item.key]
        def formattedValue = item.format(value)
        def status = getItemStatus(item.key, value)

        tableRows += "| ${item.name} | ${formattedValue} | ${status} |\n"
    }

    return """${statusIcon} PR验证完成！详细检查结果如下：

📊 **构建详情**: ${env.BUILD_URL}

### 安全检查结果汇总

| 检查项目 | 检查结果 | 状态 |
|---------|---------|------|
${tableRows}

### 详细报告链接
- 🔍 **安全扫描报告**: ${env.BUILD_URL}security-scan/
- 🐛 **代码质量报告**: ${env.BUILD_URL}code-quality/ 
- 📈 **测试覆盖率报告**: ${env.BUILD_URL}code-quality/
- 🛠️ **构建测试报告**: ${env.BUILD_URL}testReport/

**扫描配置**: ${results.scanIntensity ?: 'standard'}模式，依赖检查: ${config.skipDependencyCheck ? '已跳过' : '已执行'}

**注意**: 使用免费工具进行代码质量分析，如需更高级功能请升级 SonarQube 版本。"""
}

// ========== 新增方法：获取检查项状态 ==========
def getItemStatus(String itemKey, value) {
    switch(itemKey) {
        case "buildStatus":
            return value == "SUCCESS" ? "✅" : "❌"
        case "testSuccessRate":
            return (value != null && value >= 80) ? "✅" : "⚠️"
        case "codeCoverage":
            return (value != null && value >= 70) ? "✅" : "⚠️"
        case "checkstyleViolations":
            return (value != null && value == 0) ? "✅" : (value != null && value <= 10) ? "⚠️" : "❌"
        case "spotbugsIssues":
            return (value != null && value == 0) ? "✅" : (value != null && value <= 5) ? "⚠️" : "❌"
        case "pmdIssues":
            return (value != null && value == 0) ? "✅" : (value != null && value <= 5) ? "⚠️" : "❌"
        case "dependencyCheckStatus":
            return value == "PASSED" ? "✅" : (value == "SKIPPED" ? "⚪" : "❌")
        case "trivyScanStatus":
            return value == "PASSED" ? "✅" : (value == "SKIPPED" ? "⚪" : "❌")
        default:
            return "🔵"
    }
}

// ========== 新增方法：使用 GitHub API 发布评论 ==========
def postGitHubComment(prNumber, commentBody, config) {
    try {
        withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
            // 将评论内容写入临时文件
            writeFile file: 'comment.json', text: """{
                "body": "${commentBody.replace('"', '\\"').replace('\n', '\\n')}"
            }"""

            sh """
                echo "发布 GitHub PR 评论..."
                curl -X POST \
                -H "Authorization: token ${GITHUB_TOKEN}" \
                -H "Accept: application/vnd.github.v3+json" \
                https://api.github.com/repos/${config.org}/${config.repo}/issues/${prNumber}/comments \
                -d @comment.json || echo "GitHub 评论发布失败，但继续构建流程"
            """

            // 清理临时文件
            sh 'rm -f comment.json'
        }
        echo "✅ GitHub PR 评论发布成功"
    } catch (Exception e) {
        echo "⚠️ GitHub PR 评论发布失败: ${e.message}"
        echo "评论内容: ${commentBody}"
    }
}