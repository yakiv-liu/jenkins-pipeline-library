package org.yakiv

class SecurityTools implements Serializable {
    def steps
    def env

    SecurityTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def fastSonarScan(Map config) {
        steps.withSonarQubeEnv('sonarqube') {
            steps.withCredentials([steps.string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                    steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                        def maxRetries = 2
                        def retryDelay = 20
                        def attempt = 1
                        def success = false
                        def lastError = null

                        while (attempt <= maxRetries && !success) {
                            try {
                                def currentAttempt = attempt
                                steps.sh """
                            echo "=== 第 ${currentAttempt}/${maxRetries} 次尝试快速 SonarQube 扫描 ==="
                            
                            if [ ${currentAttempt} -eq 1 ]; then
                                echo "清理 Maven 缓存..."
                                rm -rf target/surefire-reports
                                rm -rf target/site
                            fi
                            
                            echo "当前目录: \$(pwd)"
                            echo "SonarQube 服务器: ${env.SONAR_URL}"
                            echo "扫描分支: ${config.branch}"
                            
                            export MAVEN_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC"
                            
                            # SonarQube 扫描仍然保留 120 秒超时（防止卡住）
                            timeout 120s mvn sonar:sonar \\
                            -Dsonar.host.url=${env.SONAR_URL} \\
                            -Dsonar.login=${env.SONAR_TOKEN} \\
                            -Dsonar.projectKey=${config.projectKey} \\
                            -Dsonar.projectName='${config.projectName}' \\
                            -Dsonar.sources=src/main/java \\
                            -Dsonar.tests=src/test/java \\
                            -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \\
                            -s \${MAVEN_SETTINGS} \\
                            -Dsonar.exclusions='**/test/**,**/target/**,**/node_modules/**,**/*.json,**/*.xml,**/*.md' \\
                            -Dsonar.coverage.exclusions='**/test/**' \\
                            -Dsonar.cpd.exclusions='**/test/**,**/*.json,**/*.xml' \\
                            -Dsonar.scm.disabled=true \\
                            -Dsonar.java.binaries=target/classes \\
                            -Dsonar.analysis.mode=publish \\
                            -T 2C \\
                            -Dsonar.verbose=false
                            
                            echo "✅ 第 ${currentAttempt} 次快速 SonarQube 扫描成功"
                            """
                                success = true
                                steps.echo "🎉 快速 SonarQube 扫描完成"

                            } catch (Exception e) {
                                lastError = e
                                steps.echo "❌ 第 ${attempt} 次快速 SonarQube 扫描失败: ${e.getMessage()}"

                                if (attempt < maxRetries) {
                                    steps.echo "⏳ 等待 ${retryDelay} 秒后重试..."
                                    steps.sleep(retryDelay)
                                    retryDelay = Math.min(retryDelay * 1.5, 60)
                                }
                                attempt++
                            }
                        }

                        if (!success) {
                            steps.echo "💥 快速 SonarQube 扫描失败，已重试 ${maxRetries} 次"
                            throw lastError
                        } else {
                            steps.sh """
                        echo "=== 验证快速 SonarQube 分析结果 ==="
                        if [ -f "target/sonar/report-task.txt" ]; then
                            SONAR_URL=\$(grep "dashboardUrl" target/sonar/report-task.txt | cut -d'=' -f2)
                            echo "📊 SonarQube 分析报告: \$SONAR_URL"
                            echo "📋 分析任务ID: \$(grep 'ceTaskId' target/sonar/report-task.txt | cut -d'=' -f2)"
                        else
                            echo "⚠️ 未找到 SonarQube 分析报告文件，但扫描命令执行成功"
                        fi
                        """
                        }
                    }
                }
            }
        }
    }

    def fastDependencyCheck(Boolean skip = false) {
        if (skip) {
            steps.echo "⏭️ 跳过依赖检查（配置为跳过此步骤）"
            return
        }

        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh """
            echo "🔍 开始依赖检查（无超时限制）"
            echo "注意：首次运行需要下载漏洞数据库，可能需要较长时间（10-30分钟）"
            
            # === 修改点：修正 MAVEN_SETTINGS 引用 ===
            mvn org.owasp:dependency-check-maven:check -DskipTests -s \"\${MAVEN_SETTINGS}\" \\
            -DdependencyCheck.format=HTML \\
            -DdependencyCheck.failBuildOnCVSS=9 \\
            -DdependencyCheck.analyze.direct=true \\
            -DdependencyCheck.analyze.transitive=false \\
            -DdependencyCheck.cveValidForHours=168 \\
            -DdependencyCheck.data.directory=/var/jenkins_home/dependency-check-data \\
            -DdependencyCheck.suppressionFile=suppression.xml \\
            -DdependencyCheck.scanSet='**/pom.xml' \\
            -DdependencyCheck.assemblyAnalyzerEnabled=false \\
            -DdependencyCheck.nodeAnalyzerEnabled=false \\
            -DdependencyCheck.nodeAuditAnalyzerEnabled=false \\
            -DdependencyCheck.nugetconfAnalyzerEnabled=false \\
            -DdependencyCheck.nuspecAnalyzerEnabled=false \\
            -DdependencyCheck.bundleAuditAnalyzerEnabled=false \\
            -DdependencyCheck.composerAnalyzerEnabled=false \\
            -DdependencyCheck.pythonAnalyzerEnabled=false \\
            -DdependencyCheck.rubygemsAnalyzerEnabled=false \\
            -DdependencyCheck.cocoapodsAnalyzerEnabled=false \\
            -DdependencyCheck.swiftAnalyzerEnabled=false \\
            -DdependencyCheck.centralAnalyzerEnabled=true \\
            -DdependencyCheck.nexusAnalyzerEnabled=false \\
            -DdependencyCheck.artifactoryAnalyzerEnabled=false \\
            -DdependencyCheck.parallelAnalysis=true
            
            echo "✅ 依赖检查完成"
            """
            }
        }
    }

    def fastDependencyCheckWithCache(Boolean skip = false) {
        if (skip) {
            steps.echo "⏭️ 跳过依赖检查（配置为跳过此步骤）"
            return
        }

        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh """
                echo "⚡ 开始快速依赖检查（使用缓存）"
                
                # 检查预下载数据库
                if [ -d "/var/jenkins_home/dependency-check-data" ] && [ -f "/var/jenkins_home/dependency-check-data/dc.h2.db" ]; then
                    echo "✅ 使用预下载的漏洞数据库"
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \\$MAVEN_SETTINGS \\
                    -DdependencyCheck.format=HTML \\
                    -DdependencyCheck.failBuildOnCVSS=9 \\
                    -DdependencyCheck.analyze.direct=true \\
                    -DdependencyCheck.analyze.transitive=false \\
                    -DdependencyCheck.data.directory=/var/jenkins_home/dependency-check-data \\
                    -DdependencyCheck.autoUpdate=false \\
                    -DdependencyCheck.suppressionFile=suppression.xml \\
                    -DdependencyCheck.scanSet='**/pom.xml' \\
                    -DdependencyCheck.assemblyAnalyzerEnabled=false \\
                    -DdependencyCheck.nodeAnalyzerEnabled=false \\
                    -DdependencyCheck.nodeAuditAnalyzerEnabled=false \\
                    -DdependencyCheck.nugetconfAnalyzerEnabled=false \\
                    -DdependencyCheck.nuspecAnalyzerEnabled=false \\
                    -DdependencyCheck.bundleAuditAnalyzerEnabled=false \\
                    -DdependencyCheck.composerAnalyzerEnabled=false \\
                    -DdependencyCheck.pythonAnalyzerEnabled=false \\
                    -DdependencyCheck.rubygemsAnalyzerEnabled=false \\
                    -DdependencyCheck.cocoapodsAnalyzerEnabled=false \\
                    -DdependencyCheck.swiftAnalyzerEnabled=false \\
                    -DdependencyCheck.centralAnalyzerEnabled=true \\
                    -DdependencyCheck.nexusAnalyzerEnabled=false \\
                    -DdependencyCheck.artifactoryAnalyzerEnabled=false \\
                    -DdependencyCheck.parallelAnalysis=true
                else
                    echo "⚠️ 预下载数据库不存在，执行完整扫描"
                    # 调用完整版本
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \\$MAVEN_SETTINGS \\
                    -DdependencyCheck.format=HTML \\
                    -DdependencyCheck.failBuildOnCVSS=9 \\
                    -DdependencyCheck.analyze.direct=true \\
                    -DdependencyCheck.analyze.transitive=false \\
                    -DdependencyCheck.suppressionFile=suppression.xml
                fi
                
                echo "✅ 依赖检查完成"
                """
            }
        }
    }

    // 保留原有的方法
    def sonarScan(Map config) {
        fastSonarScan(config)
    }

    def dependencyCheck(Boolean skip = false) {
        // 可以选择使用哪个版本
        fastDependencyCheck(skip)  // 无超时版本
        // fastDependencyCheckWithCache(skip)  // 使用缓存的快速版本
    }

    // ========== 修改点1：重构 runPRSecurityScan 方法，支持免费工具分析 ==========
    def runPRSecurityScan(Map params = [:]) {
        // 参数处理逻辑
        def config = [:]

        if (params.containsKey('changeId')) {
            steps.echo "⚠️ 检测到旧版参数格式，进行兼容性转换"
            config.projectName = params.projectName
            config.isPR = true
            config.prNumber = params.changeId
            config.branchName = params.changeBranch
            config.skipDependencyCheck = params.skipDependencyCheck ?: false
            config.scanIntensity = params.scanIntensity ?: 'standard'
            config.sonarqubeCommunityEdition = params.sonarqubeCommunityEdition ?: false
            config.targetBranch = params.changeTarget ?: 'main'
        } else {
            // 使用新版参数格式
            config = params
        }

        // 设置默认值
        def projectName = config.projectName ?: 'unknown'
        def isPR = config.isPR ?: false
        def prNumber = config.prNumber
        def branchName = config.branchName
        def skipDependencyCheck = config.skipDependencyCheck ?: true
        def scanIntensity = config.scanIntensity ?: 'standard'
        def sonarqubeCommunityEdition = config.sonarqubeCommunityEdition ?: false
        def targetBranch = config.targetBranch ?: 'main'

        steps.echo "开始安全扫描..."
        steps.echo "项目: ${projectName}"
        steps.echo "是否为 PR: ${isPR}"
        steps.echo "PR 编号: ${prNumber}"
        steps.echo "源分支名称: ${branchName}"
        steps.echo "目标分支: ${targetBranch}"
        steps.echo "SonarQube 社区版: ${sonarqubeCommunityEdition}"
        steps.echo "跳过依赖检查: ${skipDependencyCheck}"
        steps.echo "扫描强度: ${scanIntensity}"

        try {
            // 运行依赖检查
            if (!skipDependencyCheck) {
                steps.echo "🔍 运行依赖检查..."
                dependencyCheck(false)
            } else {
                steps.echo "⏭️ 跳过依赖检查"
            }

            // 运行 Trivy 扫描
            steps.echo "🔍 运行 Trivy 扫描..."
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif . || echo "Trivy 扫描失败但继续构建"'
            }

            // ========== 修改点2：根据 SonarQube 版本选择不同的分析工具 ==========
            if (sonarqubeCommunityEdition) {
                steps.echo "⚠️ SonarQube 社区版：使用免费工具进行代码分析"
                runFreeCodeAnalysis(projectName, branchName, isPR, prNumber, scanIntensity)
            } else {
                steps.echo "✅ SonarQube 企业版：使用完整的 PR 分析"
                runSonarQubeEnterpriseScan(projectName, branchName, isPR, prNumber, targetBranch, scanIntensity)
            }

            steps.echo "✅ 安全扫描完成"
        } catch (Exception e) {
            steps.echo "❌ 安全扫描失败: ${e.message}"
            throw e
        }
    }

    // ========== 修改点3：新增免费代码分析方法 ==========
    def runFreeCodeAnalysis(String projectName, String branchName, boolean isPR, String prNumber, String scanIntensity) {
        steps.echo "运行免费代码分析工具..."

        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh """
                    echo "=== 开始免费代码分析 ==="
                    echo "项目: ${projectName}"
                    echo "分支: ${branchName}"
                    echo "扫描强度: ${scanIntensity}"
                """

                // Checkstyle - 代码风格检查
                steps.echo "🔍 运行 Checkstyle 代码风格检查..."
                steps.sh """
                    mvn checkstyle:checkstyle -s \${MAVEN_SETTINGS} || echo "Checkstyle 检查失败但继续构建"
                """

                // SpotBugs - 代码缺陷检测
                steps.echo "🔍 运行 SpotBugs 代码缺陷检测..."
                steps.sh """
                    mvn spotbugs:spotbugs -s \${MAVEN_SETTINGS} || echo "SpotBugs 检查失败但继续构建"
                """

                // JaCoCo - 代码覆盖率
                steps.echo "🔍 运行 JaCoCo 代码覆盖率分析..."
                steps.sh """
                    mvn jacoco:prepare-agent test jacoco:report -s \${MAVEN_SETTINGS} || echo "JaCoCo 检查失败但继续构建"
                """

                // PMD - 代码质量分析
                steps.echo "🔍 运行 PMD 代码质量分析..."
                steps.sh """
                    mvn pmd:pmd -s \${MAVEN_SETTINGS} || echo "PMD 检查失败但继续构建"
                """

                // 根据扫描强度调整分析深度
                if (scanIntensity == 'deep') {
                    steps.echo "🔍 深度扫描模式：运行额外分析..."
                    steps.sh """
                        # 复制检测
                        mvn pmd:cpd -s \${MAVEN_SETTINGS} || echo "CPD 检查失败但继续构建"
                        
                        # 架构检查
                        mvn validate -s \${MAVEN_SETTINGS} || echo "架构检查失败但继续构建"
                    """
                }

                steps.sh """
                    echo "=== 免费代码分析完成 ==="
                    echo "报告位置:"
                    echo "- Checkstyle: target/checkstyle-result.xml"
                    echo "- SpotBugs: target/spotbugs.xml" 
                    echo "- JaCoCo: target/site/jacoco/index.html"
                    echo "- PMD: target/pmd.xml"
                """
            }
        }

        steps.echo "✅ 免费代码分析完成"
    }

    // ========== 修改点4：企业版 SonarQube 扫描方法 ==========
    def runSonarQubeEnterpriseScan(String projectName, String branchName, boolean isPR, String prNumber, String targetBranch, String scanIntensity) {
        steps.echo "运行 SonarQube 企业版扫描..."

        steps.withSonarQubeEnv('sonarqube') {
            steps.withCredentials([steps.string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                    steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                        // 根据扫描强度调整参数
                        def sonarExclusions = '**/test/**,**/target/**'
                        def sonarSources = 'src/main/java'

                        if (scanIntensity == 'fast') {
                            sonarExclusions += ',**/*.md,**/*.json,**/*.xml'
                            steps.echo "🔍 快速扫描模式：跳过文档和配置文件"
                        } else if (scanIntensity == 'deep') {
                            sonarSources += ',src/test/java'
                            steps.echo "🔍 深度扫描模式：包含测试代码分析"
                        }

                        def sonarCmd = "mvn sonar:sonar"
                        def sonarParams = [
                                "sonar.projectKey=${projectName}-pr-${prNumber}",
                                "sonar.projectName='${projectName} PR ${prNumber}'",
                                "sonar.sources=${sonarSources}",
                                "sonar.exclusions='${sonarExclusions}'",
                                "sonar.host.url=${env.SONAR_URL}",
                                "sonar.login=${env.SONAR_TOKEN}"
                        ]

                        // 企业版：使用完整的 PR 分析参数
                        if (isPR) {
                            sonarParams << "sonar.pullrequest.key=${prNumber}"
                            sonarParams << "sonar.pullrequest.branch=${branchName}"
                            sonarParams << "sonar.pullrequest.base=${targetBranch}"
                            steps.echo "🔍 PR 分析：${branchName} -> ${targetBranch}"
                        } else {
                            // 分支构建：使用分支分析
                            sonarParams << "sonar.branch.name=${branchName}"
                        }

                        // 构建完整的命令
                        sonarParams.each { param ->
                            sonarCmd += " -D${param}"
                        }
                        sonarCmd += " -s \${MAVEN_SETTINGS}"

                        steps.sh """
                            echo "执行 SonarQube 企业版扫描..."
                            ${sonarCmd}
                        """
                    }
                }
            }
        }

        steps.echo "✅ SonarQube 企业版扫描完成"
    }
}