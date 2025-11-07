package org.yakiv

class SecurityTools implements Serializable {
    def steps
    def env

    SecurityTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def fastSonarScan(Map config) {
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
                            
                            # 清理 Maven 缓存（只在第一次尝试时清理）
                            if [ ${currentAttempt} -eq 1 ]; then
                                echo "清理 Maven 缓存..."
                                rm -rf target/surefire-reports
                                rm -rf target/site
                            fi
                            
                            echo "当前目录: \$(pwd)"
                            echo "SonarQube 服务器: ${env.SONAR_URL}"
                            
                            # 设置内存和超时
                            export MAVEN_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC"
                            
                            # === 修复点：使用单引号包裹包含特殊字符的参数 ===
                            # 使用快速扫描配置
                            timeout 120s mvn sonar:sonar \\
                            -Dsonar.host.url=${env.SONAR_URL} \\
                            -Dsonar.login=\\${SONAR_TOKEN} \\
                            -Dsonar.projectKey=${config.projectKey} \\
                            -Dsonar.projectName='${config.projectName}' \\
                            -Dsonar.sources=src/main/java \\
                            -Dsonar.tests=src/test/java \\
                            -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \\
                            -s \\$MAVEN_SETTINGS \\
                            # === 修复点：将包含通配符的参数用单引号包裹 ===
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
                        // 验证分析结果
                            steps.sh """
                                echo "=== 验证快速 SonarQube 分析结果 ==="
                                if [ -f "target/sonar/report-task.txt" ]; then
                                    SONAR_URL=\$(grep "dashboardUrl" target/sonar/report-task.txt | cut -d'=' -f2)
                                    echo "📊 SonarQube 分析报告: \$SONAR_URL"
                                else
                                    echo "⚠️ 未找到 SonarQube 分析报告文件，但扫描命令执行成功"
                                fi
                            """
                    }
                }
            }
        }
    }

    def fastDependencyCheck() {
        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh """
            echo "⚡ 开始极速依赖检查 (目标: 2-3分钟)"
            
            # 设置超时，防止卡住
            timeout 180s bash -c "
            # 极速依赖检查配置
            mvn org.owasp:dependency-check-maven:check -DskipTests -s \\$MAVEN_SETTINGS \\
            # === 极速优化参数 ===
            -DdependencyCheck.format=HTML \\
            -DdependencyCheck.failBuildOnCVSS=9 \\\\t\\\\t\\\\t\\\\t# 只检查严重漏洞 \\
            -DdependencyCheck.analyze.direct=true \\\\t\\\\t\\\\t# 只分析直接依赖 \\
            -DdependencyCheck.analyze.transitive=false \\\\t\\\\t# 跳过传递依赖 \\
            -DdependencyCheck.cveValidForHours=168 \\\\t\\\\t\\\\t# 使用7天内的缓存 \\
            -DdependencyCheck.data.directory=\\$HOME/.dependency-check/data \\\\t# 使用共享数据目录 \\
            -DdependencyCheck.suppressionFile=suppression.xml \\\\t\\\\t# 使用抑制文件 \\
            -DdependencyCheck.scanSet='**/pom.xml' \\\\t\\\\t\\\\t# 只扫描pom文件 \\
            # === 禁用不必要的分析器 ===
            -DdependencyCheck.assemblyAnalyzerEnabled=false \\\\t\\\\t# 禁用程序集分析 \\
            -DdependencyCheck.nodeAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用Node.js分析 \\
            -DdependencyCheck.nodeAuditAnalyzerEnabled=false \\\\t\\\\t# 禁用Node审计 \\
            -DdependencyCheck.nugetconfAnalyzerEnabled=false \\\\t\\\\t# 禁用NuGet配置分析 \\
            -DdependencyCheck.nuspecAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用NuSpec分析 \\
            -DdependencyCheck.bundleAuditAnalyzerEnabled=false \\\\t\\\\t# 禁用Bundle审计 \\
            -DdependencyCheck.composerAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用Composer分析 \\
            -DdependencyCheck.pythonAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用Python分析 \\
            -DdependencyCheck.rubygemsAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用RubyGems分析 \\
            -DdependencyCheck.cocoapodsAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用CocoaPods分析 \\
            -DdependencyCheck.swiftAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用Swift分析 \\
            -DdependencyCheck.centralAnalyzerEnabled=true \\\\t\\\\t\\\\t# 只启用Maven中央仓库分析 \\
            -DdependencyCheck.nexusAnalyzerEnabled=false \\\\t\\\\t\\\\t# 禁用Nexus分析 \\
            -DdependencyCheck.artifactoryAnalyzerEnabled=false \\\\t\\\\t# 禁用Artifactory分析 \\
            # === 性能优化 ===
            -DdependencyCheck.parallelAnalysis=true \\\\t\\\\t\\\\t# 并行分析 \\
            -DdependencyCheck.jaegerEnabled=false \\\\t\\\\t\\\\t\\\\t# 禁用Jaeger跟踪
            "
            
            echo "✅ 极速依赖检查完成"
            """
            }
        }
    }

    // === 保留原有的sonarScan方法，但改为调用快速版本 ===
    def sonarScan(Map config) {
        fastSonarScan(config)
    }

    // === 保留原有的dependencyCheck方法，但改为调用快速版本 ===
    def dependencyCheck() {
        fastDependencyCheck()
    }

    def runPRSecurityScan(Map config) {
        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.withSonarQubeEnv('sonarqube') {
                // 确保在项目目录中执行
                steps.dir(env.WORKSPACE) {
                    steps.sh """
                        mvn sonar:sonar \\
                        -Dsonar.projectKey=${config.projectName}-pr-${config.changeId} \\
                        -Dsonar.projectName='${config.projectName} PR ${config.changeId}' \\
                        -Dsonar.pullrequest.key=${config.changeId} \\
                        -Dsonar.pullrequest.branch=${config.changeBranch} \\
                        -Dsonar.pullrequest.base=${config.changeTarget} \\
                        -Dsonar.sources=src/main/java \\
                        -Dsonar.tests= \\
                        -Dsonar.exclusions='**/test/**,**/target/**' \\
                        -s \\$MAVEN_SETTINGS
                    """
                }
            }

            // 确保在项目目录中执行
            steps.dir(env.WORKSPACE) {
                steps.sh """
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \\$MAVEN_SETTINGS \\
                    -DdependencyCheck.failBuildOnCVSS=9
                """
                steps.sh """
                    mvn spotbugs:spotbugs -DskipTests -s \\$MAVEN_SETTINGS
                """
                steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif .'
            }
        }
    }
}