package org.yakiv

class SecurityTools implements Serializable {
    def steps
    def env

    SecurityTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    // === 新增：快速SonarQube扫描方法（2分钟超时）===
    def fastSonarScan(Map config) {
        steps.withCredentials([steps.string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                    // 重试配置
                    def maxRetries = 2  // 减少重试次数
                    def retryDelay = 20  // 减少重试延迟
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
                            
                            # 使用快速扫描配置
                            timeout 120s mvn sonar:sonar \\
                            -Dsonar.host.url=${env.SONAR_URL} \\
                            -Dsonar.login=\${SONAR_TOKEN} \\
                            -Dsonar.projectKey=${config.projectKey} \\
                            -Dsonar.projectName='${config.projectName}' \\
                            -Dsonar.sources=src/main/java \\
                            -Dsonar.tests=src/test/java \\
                            -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \\
                            -s \$MAVEN_SETTINGS \\
                            # === 快速扫描优化参数 ===
                            -Dsonar.exclusions=**/test/**,**/target/**,**/node_modules/**,**/*.json,**/*.xml,**/*.md \\
                            -Dsonar.coverage.exclusions=**/test/** \\
                            -Dsonar.cpd.exclusions=**/test/**,**/*.json,**/*.xml \\
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
                            steps.echo "❌ 第 ${attempt} 次快速 SonarQube 扫描失败"

                            if (attempt < maxRetries) {
                                steps.echo "⏳ 等待 ${retryDelay} 秒后重试..."
                                steps.sleep(retryDelay)
                                retryDelay = Math.min(retryDelay * 1.5, 60)  // 最大60秒
                            }
                            attempt++
                        }
                    }

                    if (!success) {
                        steps.echo "💥 快速 SonarQube 扫描失败，已重试 ${maxRetries} 次"
                        steps.echo "🔧 建议检查:"
                        steps.echo "   - SonarQube 服务器状态 (${env.SONAR_URL})"
                        steps.echo "   - 网络连接"
                        steps.echo "   - SonarQube 令牌权限"
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

    // === 新增：快速依赖检查方法 ===
    def fastDependencyCheck() {
        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh """
                echo "=== 开始快速依赖检查 ==="
                
                # 快速依赖检查 - 只检查直接依赖和高危漏洞
                mvn org.owasp:dependency-check-maven:check -DskipTests -s \$MAVEN_SETTINGS \\
                -DdependencyCheck.format=HTML \\
                -DdependencyCheck.failBuildOnCVSS=9 \\
                -DdependencyCheck.analyze.direct=true \\
                -DdependencyCheck.analyze.transitive=false \\
                -DdependencyCheck.cveValidForHours=168
                
                echo "✅ 快速依赖检查完成"
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
                        mvn sonar:sonar \
                        -Dsonar.projectKey=${config.projectName}-pr-${config.changeId} \
                        -Dsonar.projectName='${config.projectName} PR ${config.changeId}' \
                        -Dsonar.pullrequest.key=${config.changeId} \
                        -Dsonar.pullrequest.branch=${config.changeBranch} \
                        -Dsonar.pullrequest.base=${config.changeTarget} \
                        -Dsonar.sources=src/main/java \
                        -Dsonar.tests= \
                        -Dsonar.exclusions=**/test/**,**/target/** \
                        -s \$MAVEN_SETTINGS
                    """
                }
            }

            // 确保在项目目录中执行
            steps.dir(env.WORKSPACE) {
                steps.sh """
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \$MAVEN_SETTINGS \
                    -DdependencyCheck.failBuildOnCVSS=9
                """
                steps.sh """
                    mvn spotbugs:spotbugs -DskipTests -s \$MAVEN_SETTINGS
                """
                steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif .'
            }
        }
    }
}