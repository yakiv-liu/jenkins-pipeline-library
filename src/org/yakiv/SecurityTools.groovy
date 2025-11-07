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
                            
                            if [ ${currentAttempt} -eq 1 ]; then
                                echo "清理 Maven 缓存..."
                                rm -rf target/surefire-reports
                                rm -rf target/site
                            fi
                            
                            echo "当前目录: \$(pwd)"
                            echo "SonarQube 服务器: ${env.SONAR_URL}"
                            
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

    // 可选：保留快速版本（使用预下载数据库）
    def fastDependencyCheckWithCache() {
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

    def dependencyCheck() {
        // 可以选择使用哪个版本
        fastDependencyCheck()  // 无超时版本
        // fastDependencyCheckWithCache()  // 使用缓存的快速版本
    }

    def runPRSecurityScan(Map config) {
        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.withSonarQubeEnv('sonarqube') {
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
                        -s \${MAVEN_SETTINGS}
                    """
                }
            }

            steps.dir(env.WORKSPACE) {
                steps.sh """
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \${MAVEN_SETTINGS} \\
                    -DdependencyCheck.failBuildOnCVSS=9
                """
                steps.sh """
                    mvn spotbugs:spotbugs -DskipTests -s \${MAVEN_SETTINGS}
                """
                steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif .'
            }
        }
    }
}