package org.yakiv

class SecurityTools implements Serializable {
    def steps
    def env

    SecurityTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def sonarScan(Map config) {
        // ========== 修改开始：添加 Config File Provider 和凭据管理 ==========
        steps.withCredentials([
                steps.usernamePassword(
                        credentialsId: 'nexus-credentials',  // 新增：Nexus 凭据
                        usernameVariable: 'NEXUS_USERNAME',
                        passwordVariable: 'NEXUS_PASSWORD'
                )
        ]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.withSonarQubeEnv('sonarqube') {
                    steps.sh """
                        mvn sonar:sonar \
                        -Dsonar.projectKey=${config.projectKey} \
                        -Dsonar.projectName='${config.projectName}' \
                        -Dsonar.sources=src/main/java \
                        -Dsonar.tests=src/test/java \
                        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
                        -s \$MAVEN_SETTINGS  # 新增：使用 Jenkins 管理的 settings.xml
                    """
                }
            }
        }
        // ========== 修改结束 ==========
    }

    def dependencyCheck() {
        // ========== 修改开始：添加 Config File Provider 和凭据管理 ==========
        steps.withCredentials([
                steps.usernamePassword(
                        credentialsId: 'nexus-credentials',  // 新增：Nexus 凭据
                        usernameVariable: 'NEXUS_USERNAME',
                        passwordVariable: 'NEXUS_PASSWORD'
                )
        ]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.sh """
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \$MAVEN_SETTINGS  # 新增：使用 Jenkins 管理的 settings.xml
                """
                steps.sh """
                    mvn spotbugs:spotbugs -DskipTests -s \$MAVEN_SETTINGS  # 新增：使用 Jenkins 管理的 settings.xml
                """
            }
        }
        // ========== 修改结束 ==========
    }

    def runPRSecurityScan(Map config) {
        // ========== 修改开始：添加 Config File Provider 和凭据管理 ==========
        steps.withCredentials([
                steps.usernamePassword(
                        credentialsId: 'nexus-credentials',  // 新增：Nexus 凭据
                        usernameVariable: 'NEXUS_USERNAME',
                        passwordVariable: 'NEXUS_PASSWORD'
                )
        ]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.withSonarQubeEnv('sonarqube') {
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
                        -s \$MAVEN_SETTINGS  # 新增：使用 Jenkins 管理的 settings.xml
                    """
                }

                steps.sh """
                    mvn org.owasp:dependency-check-maven:check -DskipTests -s \$MAVEN_SETTINGS  # 新增：使用 Jenkins 管理的 settings.xml
                """
                steps.sh """
                    mvn spotbugs:spotbugs -DskipTests -s \$MAVEN_SETTINGS  # 新增：使用 Jenkins 管理的 settings.xml
                """
                steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif .'
            }
        }
        // ========== 修改结束 ==========
    }
}