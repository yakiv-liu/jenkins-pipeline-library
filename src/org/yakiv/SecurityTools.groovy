package org.yakiv

class SecurityTools implements Serializable {
    def steps
    def env

    SecurityTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def sonarScan(Map config) {
        steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
            steps.withSonarQubeEnv('sonarqube') {
                // 在实际项目代码目录执行扫描
                steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                    steps.sh """
                    echo "=== 执行 SonarQube 扫描前的目录检查 ==="
                    echo "当前目录: \$(pwd)"
                    echo "文件列表:"
                    ls -la
                    echo "检查 pom.xml:"
                    ls -la pom.xml && echo "✓ pom.xml 存在" || echo "✗ pom.xml 不存在"
                    echo "=== 开始 SonarQube 扫描 ==="
                    
                    mvn sonar:sonar \
                    -Dsonar.projectKey=${config.projectKey} \
                    -Dsonar.projectName='${config.projectName}' \
                    -Dsonar.sources=src/main/java \
                    -Dsonar.tests=src/test/java \
                    -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                    -s \$MAVEN_SETTINGS
                """
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