package org.yakiv

class BuildTools implements Serializable {
    def steps
    def env

    BuildTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def mavenBuild(Map config) {
        steps.withCredentials([
                steps.usernamePassword(
                        credentialsId: 'nexus-credentials',
                        usernameVariable: 'NEXUS_USERNAME',
                        passwordVariable: 'NEXUS_PASSWORD'
                )
        ]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.sh """
                    mvn -s $MAVEN_SETTINGS clean deploy \
                        '-Drevision=${config.version}' \
                        '-DskipTests=${config.skipTests ?: false}' \
                        -P ${config.isRelease ? 'release' : 'snapshot'}
                """
            }
        }
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
        steps.sh '''
            mvn clean compile test -T 1C \
            -Dmaven.test.failure.ignore=false
        '''

        steps.sh 'mvn surefire-report:report jacoco:report'
        steps.sh 'mvn package -DskipTests'
    }
}