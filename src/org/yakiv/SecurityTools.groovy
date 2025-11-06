package org.yakiv

class SecurityTools implements Serializable {
    def steps
    def env

    SecurityTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def sonarScan(Map config) {
        steps.withCredentials([steps.string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                    // 重试配置
                    def maxRetries = 3
                    def retryDelay = 30  // 秒
                    def attempt = 1
                    def success = false
                    def lastError = null

                    while (attempt <= maxRetries && !success) {
                        try {
                            // 使用 Groovy 变量而不是 shell 算术表达式
                            def currentAttempt = attempt
                            steps.sh """
                            echo "=== 第 ${currentAttempt}/${maxRetries} 次尝试 SonarQube 扫描 ==="
                            
                            # 清理 Maven 缓存（只在第一次尝试时清理）
                            if [ ${currentAttempt} -eq 1 ]; then
                                echo "清理 Maven 缓存..."
                                rm -rf target/surefire-reports
                                rm -rf target/site
                            fi
                            
                            echo "当前目录: \$(pwd)"
                            echo "SonarQube 服务器: ${env.SONAR_URL}"
                            
                            # 设置内存
                            export MAVEN_OPTS="-Xmx1024m -Xms512m -Xss4m -XX:MaxMetaspaceSize=512m"
                            
                            # 使用显式令牌认证
                            mvn sonar:sonar \
                            -Dsonar.host.url=${env.SONAR_URL} \
                            -Dsonar.login=\${SONAR_TOKEN} \
                            -Dsonar.projectKey=${config.projectKey} \
                            -Dsonar.projectName='${config.projectName}' \
                            -Dsonar.sources=src/main/java \
                            -Dsonar.tests=src/test/java \
                            -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                            -s \$MAVEN_SETTINGS \
                            -Dsonar.verbose=true
                            
                            echo "✅ 第 ${currentAttempt} 次 SonarQube 扫描成功"
                        """
                            success = true
                            steps.echo "🎉 SonarQube 扫描完成"

                        } catch (Exception e) {
                            lastError = e
                            steps.echo "❌ 第 ${attempt} 次 SonarQube 扫描失败"

                            if (attempt < maxRetries) {
                                steps.echo "⏳ 等待 ${retryDelay} 秒后重试..."
                                steps.sleep(retryDelay)

                                // 每次重试后增加等待时间（指数退避）
                                retryDelay = Math.min(retryDelay * 1.5, 120)  // 最大120秒
                            }
                            attempt++
                        }
                    }

                    if (!success) {
                        steps.echo "💥 SonarQube 扫描失败，已重试 ${maxRetries} 次"
                        steps.echo "🔧 建议检查:"
                        steps.echo "   - SonarQube 服务器状态 (${env.SONAR_URL})"
                        steps.echo "   - 网络连接"
                        steps.echo "   - SonarQube 令牌权限"
                        throw lastError
                    } else {
                        // 验证分析结果
                        steps.sh """
                        echo "=== 验证 SonarQube 分析结果 ==="
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

    def dependencyCheck() {
        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            // 使用环境变量动态确定项目目录
            steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                steps.sh """
                mvn org.owasp:dependency-check-maven:check -DskipTests -s \$MAVEN_SETTINGS
            """
                steps.sh """
                mvn spotbugs:spotbugs -DskipTests -s \$MAVEN_SETTINGS
            """
            }
        }
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
                        -Dsonar.tests=src/test/java \
                        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                        -s \$MAVEN_SETTINGS
                    """
                }
            }

            // 确保在项目目录中执行
            steps.dir(env.WORKSPACE) {
                steps.sh """
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \$MAVEN_SETTINGS
                """
                steps.sh """
                    mvn spotbugs:spotbugs -DskipTests -s \$MAVEN_SETTINGS
                """
                steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif .'
            }
        }
    }
}