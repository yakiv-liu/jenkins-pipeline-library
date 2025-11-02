package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env

    DeployTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def deployToEnvironment(Map config) {
        steps.ansiblePlaybook(
                playbook: 'ansible-playbooks/deploy-with-rollback.yml',
                inventory: "inventory/${config.environment}",
                extraVars: [
                        project_name: config.projectName,
                        app_version: config.version,
                        deploy_env: config.environment,
                        harbor_url: config.harborUrl,
                        enable_rollback: true
                ],
                credentialsId: 'ansible-ssh-key'
        )
    }

    def executeRollback(Map config) {
        steps.ansiblePlaybook(
                playbook: 'ansible-playbooks/rollback.yml',
                inventory: "inventory/${config.environment}",
                extraVars: [
                        project_name: config.projectName,
                        rollback_version: config.version,
                        deploy_env: config.environment,
                        harbor_url: config.harborUrl
                ],
                credentialsId: 'ansible-ssh-key'
        )
    }

    def healthCheck(Map config) {
        def url = getHealthCheckUrl(config.environment, config.projectName)

        steps.sh """
            curl -f ${url}/health || exit 1
            curl -f ${url}/info | grep \"version\":\"${config.version}\" || exit 1
        """
    }

    def validateRollbackVersion(Map config) {
        steps.withCredentials([steps.usernamePassword(
                credentialsId: 'harbor-creds',
                passwordVariable: 'HARBOR_PASSWORD',
                usernameVariable: 'HARBOR_USERNAME'
        )]) {
            steps.sh """
                curl -u ${env.HARBOR_USERNAME}:${env.HARBOR_PASSWORD} \
                -s "https://${config.harborUrl}/api/v2/projects/${config.projectName}/repositories/${config.projectName}/artifacts?q=tags=${config.version}" | grep -q ${config.version} || exit 1
            """
        }
    }

    private def getHealthCheckUrl(environment, projectName) {
        switch(environment) {
            case 'staging':
                return "http://staging-server"
            case 'pre-prod':
                return "http://preprod-server"
            case 'prod':
                return "http://prod-server"
            default:
                return "http://${environment}-server"
        }
    }
}