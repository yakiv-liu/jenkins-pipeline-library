package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env

    DeployTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def deployToEnvironment(Map config) {
        // 准备 Ansible 环境
        prepareAnsibleEnvironment(config.environment, config)

        steps.ansiblePlaybook(
                playbook: 'ansible-playbooks/deploy-with-rollback.yml',
                inventory: "inventory/${config.environment}",
                extraVars: [
                        project_name: config.projectName,
                        app_version: config.version,
                        deploy_env: config.environment,
                        harbor_url: config.harborUrl,
                        enable_rollback: true,
                        app_port: config.appPort,
                        app_dir: getAppDir(config.environment),
                        backup_dir: config.backupDir ?: '/opt/backups'
                ],
                credentialsId: 'ansible-ssh-key',
                disableHostKeyChecking: true
        )
    }

    def executeRollback(Map config) {
        // 准备 Ansible 环境
        prepareAnsibleEnvironment(config.environment, config)

        steps.ansiblePlaybook(
                playbook: 'ansible-playbooks/rollback.yml',
                inventory: "inventory/${config.environment}",
                extraVars: [
                        project_name: config.projectName,
                        rollback_version: config.version,
                        deploy_env: config.environment,
                        harbor_url: config.harborUrl,
                        app_port: config.appPort,
                        app_dir: getAppDir(config.environment),
                        backup_dir: config.backupDir ?: '/opt/backups'
                ],
                credentialsId: 'ansible-ssh-key',
                disableHostKeyChecking: true
        )
    }

    def prepareAnsibleEnvironment(String environment, Map config) {
        steps.sh '''
            mkdir -p ansible-playbooks
            mkdir -p inventory
        '''

        // 复制 playbooks
        steps.sh """
            # 复制 playbooks
            find ${steps.env.WORKSPACE}@libs/jenkins-pipeline-library/ansible/playbooks -name "*.yml" -exec cp {} ansible-playbooks/ \\;
        """

        // 动态生成 inventory 文件
        generateInventoryFile(environment, config)

        // 设置 SSH 密钥
        setupSSHKey()
    }

    def generateInventoryFile(String environment, Map config) {
        // 获取环境主机配置
        def envHost = getEnvironmentHost(config, environment)
        def appPort = config.appPort ?: 8080

        def inventoryContent = """
            [${environment}]
            ${envHost} ansible_user=root ansible_ssh_private_key_file=/tmp/ansible-key
            
            [${environment}:vars]
            deploy_user=appuser
            app_port=${appPort}
            app_dir=${getAppDir(environment)}
            backup_dir=${config.backupDir ?: '/opt/backups'}
        """

        steps.writeFile file: "inventory/${environment}", text: inventoryContent
        steps.echo "生成的 ${environment} inventory 文件，目标主机: ${envHost}, 端口: ${appPort}"
    }

    // 从配置中获取环境主机
    private def getEnvironmentHost(Map config, String environment) {
        if (config.environmentHosts &&
                config.environmentHosts[environment] &&
                config.environmentHosts[environment].host) {
            return config.environmentHosts[environment].host
        }

        // 如果没有配置，使用默认值
        switch(environment) {
            case 'staging':
                return '192.168.233.8'
            case 'pre-prod':
                return '192.168.233.9'
            case 'prod':
                return '192.168.233.7'
            default:
                return 'localhost'
        }
    }

    // 其他方法保持不变...
    def setupSSHKey() {
        steps.withCredentials([steps.sshUserPrivateKey(
                credentialsId: 'ansible-ssh-key',
                keyFileVariable: 'SSH_KEY_FILE',
                usernameVariable: 'SSH_USERNAME'
        )]) {
            steps.sh """
                cp \$SSH_KEY_FILE /tmp/ansible-key
                chmod 600 /tmp/ansible-key
                export ANSIBLE_SSH_ARGS="-o StrictHostKeyChecking=no -i /tmp/ansible-key"
            """
        }
    }

    def healthCheck(Map config) {
        def url = getHealthCheckUrl(config.environment, config.projectName, config)

        steps.sh """
            echo "执行健康检查: ${url}"
            curl -f ${url}/health || exit 1
            curl -f ${url}/info | grep \"version\":\"${config.version}\" || exit 1
        """
    }

    private def getHealthCheckUrl(environment, projectName, Map config) {
        def envHost = getEnvironmentHost(config, environment)
        def appPort = config.appPort ?: 8080
        return "http://${envHost}:${appPort}"
    }

    // 其他辅助方法...
    private def getAppDir(String environment) {
        switch(environment) {
            case 'staging':
                return '/opt/apps/staging'
            case 'pre-prod':
                return '/opt/apps/pre-prod'
            case 'prod':
                return '/opt/apps/prod'
            default:
                return '/opt/apps'
        }
    }
}