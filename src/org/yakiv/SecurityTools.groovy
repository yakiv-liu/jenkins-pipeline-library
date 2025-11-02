package org.yakiv

class SecurityTools implements Serializable {
    def steps
    
    SecurityTools(steps = null) {
        this.steps = steps ?: new hudson.model.Build(steps)
    }
    
    def sonarScan(Map config) {
        steps.withSonarQubeEnv('sonarqube') {
            steps.sh """
                mvn sonar:sonar \
                -Dsonar.projectKey=${config.projectKey} \
                -Dsonar.projectName='${config.projectName}' \
                -Dsonar.sources=src/main/java \
                -Dsonar.tests=src/test/java \
                -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
            """
        }
    }
    
    def dependencyCheck() {
        steps.sh 'mvn org.owasp:dependency-check-maven:check -DskipTests'
        steps.sh 'mvn spotbugs:spotbugs -DskipTests'
    }
    
    def runPRSecurityScan(Map config) {
        // PR专用的安全扫描方法
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
                -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
            """
        }

        steps.sh 'mvn org.owasp:dependency-check-maven:check -DskipTests'
        steps.sh 'mvn spotbugs:spotbugs -DskipTests'
        steps.sh 'trivy filesystem --format sarif --output trivy-report.sarif .'
    }
}
