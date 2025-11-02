package org.yakiv

class BuildTools implements Serializable {
    def steps
    def env
    
    BuildTools(steps = null) {
        this.steps = steps ?: new hudson.model.Build(steps)
        this.env = steps?.env ?: [:]
    }
    
    def mavenBuild(Map config) {
        steps.sh """
            mvn -s settings.xml clean deploy \
            -Drevision=${config.version} \
            -DskipTests=false \
            ${config.isRelease ? '-P release' : '-P snapshot'}
        """
    }
    
    def buildDockerImage(Map config) {
        steps.sh """
            docker build \
            --build-arg PROJECT_NAME=${config.projectName} \
            --build-arg APP_VERSION=${config.version} \
            --build-arg GIT_COMMIT=${config.gitCommit} \
            -t ${env.HARBOR_URL}/${config.projectName}:${config.version} \
            -t ${env.HARBOR_URL}/${config.projectName}:latest \
            .
        """
    }
    
    def trivyScan(Map config) {
        steps.sh """
            trivy image --format template --template @html.tpl -o trivy-report.html ${config.image}
        """
    }
    
    def pushDockerImage(Map config) {
        steps.withCredentials([steps.usernamePassword(
            credentialsId: 'harbor-creds',
            passwordVariable: 'HARBOR_PASSWORD',
            usernameVariable: 'HARBOR_USERNAME'
        )]) {
            steps.sh """
                docker login -u ${env.HARBOR_USERNAME} -p ${env.HARBOR_PASSWORD} ${config.harborUrl}
                docker push ${config.harborUrl}/${config.projectName}:${config.version}
                docker push ${config.harborUrl}/${config.projectName}:latest
            """
        }
    }
    
    def runPRBuildAndTest() {
        // PR专用的构建测试方法
        steps.sh '''
            mvn clean compile test -T 1C \
            -Dmaven.test.failure.ignore=false
        '''

        steps.sh 'mvn surefire-report:report jacoco:report'
        steps.sh 'mvn package -DskipTests'
    }
}
