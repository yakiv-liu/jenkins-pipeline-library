package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env

    DeployTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def deployToEnvironment(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            prepareAnsibleEnvironment(config.environment, config)

            def extraVars = [
                    project_name: config.projectName,
                    app_version: config.version,
                    deploy_env: config.environment,
                    harbor_url: config.harborUrl,
                    enable_rollback: true,
                    app_port: config.appPort,
                    app_dir: getAppDir(config.environment),
                    backup_dir: config.backupDir ?: '/opt/backups',
                    git_commit: env.GIT_COMMIT ?: 'unknown'
            ]

            // === 修改点：添加Harbor凭据（如果配置了） ===
            if (config.harborUsername && config.harborPassword) {
                extraVars.harbor_username = config.harborUsername
                extraVars.harbor_password = config.harborPassword
            }

            steps.ansiblePlaybook(
                    playbook: 'ansible-playbooks/deploy-with-rollback.yml',
                    inventory: "inventory/${config.environment}",
                    extraVars: extraVars,
                    credentialsId: 'ansible-ssh-key',
                    disableHostKeyChecking: true
            )
        }
    }

    def executeRollback(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            prepareAnsibleEnvironment(config.environment, config)

            def extraVars = [
                    project_name: config.projectName,
                    rollback_version: config.version,
                    deploy_env: config.environment,
                    harbor_url: config.harborUrl,
                    app_port: config.appPort,
                    app_dir: getAppDir(config.environment),
                    backup_dir: config.backupDir ?: '/opt/backups'
            ]

            // === 修改点：添加Harbor凭据（如果配置了） ===
            if (config.harborUsername && config.harborPassword) {
                extraVars.harbor_username = config.harborUsername
                extraVars.harbor_password = config.harborPassword
            }

            steps.ansiblePlaybook(
                    playbook: 'ansible-playbooks/rollback.yml',
                    inventory: "inventory/${config.environment}",
                    extraVars: extraVars,
                    credentialsId: 'ansible-ssh-key',
                    disableHostKeyChecking: true
            )
        }
    }

    def prepareAnsibleEnvironment(String environment, Map config) {
        steps.sh 'mkdir -p ansible-playbooks inventory'

        // 从配置文件读取要复制的文件列表
        copyAnsibleFilesFromConfig()

        generateInventoryFile(environment, config)
        setupSSHKey()
    }

    def copyAnsibleFilesFromConfig() {
        try {
            // 从配置文件读取文件列表
            def fileListContent = steps.libraryResource('ansible/file-list.conf')
            def filesToCopy = fileListContent.readLines().findAll { it.trim() && !it.startsWith('#') }

            filesToCopy.each { filePath ->
                try {
                    def content = steps.libraryResource("ansible/${filePath}")
                    def fileName = filePath.substring(filePath.lastIndexOf('/') + 1)
                    steps.writeFile file: "ansible-playbooks/${fileName}", text: content
                } catch (Exception e) {
                    steps.echo "复制文件失败: ${filePath} - ${e.getMessage()}"
                    throw e
                }
            }
        } catch (Exception e) {
            // 如果配置文件不存在，使用默认文件列表
            copyDefaultAnsibleFiles()
        }
    }

    def copyDefaultAnsibleFiles() {
        // 默认文件列表
        def defaultFiles = [
                'playbooks/deploy-with-rollback.yml',
                'playbooks/rollback.yml'
        ]

        defaultFiles.each { filePath ->
            try {
                def content = steps.libraryResource("ansible/${filePath}")
                def fileName = filePath.substring(filePath.lastIndexOf('/') + 1)
                steps.writeFile file: "ansible-playbooks/${fileName}", text: content
            } catch (Exception e) {
                steps.echo "复制文件失败: ${filePath} - ${e.getMessage()}"
                throw e
            }
        }
    }

    def generateInventoryFile(String environment, Map config) {
        def envHost = getEnvironmentHost(config, environment)
        def appPort = config.appPort ?: 8080

        def inventoryContent = """
            [${environment}]
            ${envHost} ansible_user=root ansible_ssh_private_key_file=/tmp/ansible-key
            
            [${environment}:vars]
            app_port=${appPort}
            app_dir=${getAppDir(environment)}
            backup_dir=${config.backupDir ?: '/opt/backups'}
        """

        steps.writeFile file: "inventory/${environment}", text: inventoryContent.trim()
    }

    private def getEnvironmentHost(Map config, String environment) {
        if (config.environmentHosts?."${environment}"?.host) {
            return config.environmentHosts[environment].host
        }

        switch(environment) {
            case 'staging': return '192.168.233.8'
            case 'pre-prod': return '192.168.233.9'
            case 'prod': return '192.168.233.10'
            default: return 'localhost'
        }
    }

    def setupSSHKey() {
        steps.withCredentials([steps.sshUserPrivateKey(
                credentialsId: 'ansible-ssh-key',
                keyFileVariable: 'SSH_KEY_FILE'
        )]) {
            steps.sh 'cp $SSH_KEY_FILE /tmp/ansible-key && chmod 600 /tmp/ansible-key'
        }
    }

    def healthCheck(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            def targetHost = getEnvironmentHost(config, config.environment)
            def url = "http://${targetHost}:${config.appPort ?: 8080}"

            steps.sh """
                for i in {1..30}; do
                    curl -f ${url}/health && curl -f ${url}/info | grep \"version\":\"${config.version}\" && exit 0
                    sleep 10
                done
                exit 1
            """
        }
    }

    private def getHealthCheckUrl(environment, projectName, Map config) {
        "http://${getEnvironmentHost(config, environment)}:${config.appPort ?: 8080}"
    }

    private def getAppDir(String environment) {
        switch(environment) {
            case 'staging': return '/opt/apps/staging'
            case 'pre-prod': return '/opt/apps/pre-prod'
            case 'prod': return '/opt/apps/prod'
            default: return '/opt/apps'
        }
    }
}