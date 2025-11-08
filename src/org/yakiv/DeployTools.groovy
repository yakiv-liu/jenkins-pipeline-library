package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env

    DeployTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def deployToEnvironment(Map config) {
        // 在项目目录中准备 Ansible 环境
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
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
    }

    def executeRollback(Map config) {
        // 在项目目录中准备 Ansible 环境
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
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
    }

    def prepareAnsibleEnvironment(String environment, Map config) {
        steps.sh '''
            echo "当前工作目录: $(pwd)"
            echo "准备 Ansible 环境..."
        '''

        steps.sh '''
            mkdir -p ansible-playbooks
            mkdir -p inventory
        '''

        // === 动态复制整个 ansible 目录 ===
        copyAnsibleDirectory()

        // 动态生成 inventory 文件
        generateInventoryFile(environment, config)

        // 设置 SSH 密钥
        setupSSHKey()
    }

    def copyAnsibleDirectory() {
        steps.sh """
            echo "从共享库复制整个 Ansible 目录..."
        """

        try {
            // 定义要复制的 Ansible 目录结构
            def ansibleStructure = [
                    'ansible/playbooks/deploy-with-rollback.yml',
                    'ansible/playbooks/rollback.yml'
                    // 可以继续添加其他文件，如：
                    // 'ansible/templates/some-template.j2',
                    // 'ansible/roles/some-role/tasks/main.yml'
            ]

            // 复制每个文件
            ansibleStructure.each { resourcePath ->
                try {
                    def content = steps.libraryResource resourcePath
                    // 确保目标目录存在
                    def targetDir = resourcePath.split('/')[0..-2].join('/')
                    if (targetDir) {
                        steps.sh "mkdir -p ${targetDir}"
                    }
                    steps.writeFile file: resourcePath, text: content
                    steps.echo "✅ 复制 ${resourcePath}"
                } catch (Exception e) {
                    steps.echo "❌ 复制 ${resourcePath} 失败: ${e.getMessage()}"
                    throw e
                }
            }

            // 同时复制到 ansible-playbooks 目录以便向后兼容
            steps.sh """
                echo "复制 playbooks 到 ansible-playbooks 目录..."
                cp ansible/playbooks/*.yml ansible-playbooks/ 2>/dev/null || echo "没有可复制的 playbooks"
            """

            steps.echo "✅ 整个 Ansible 目录复制完成"
            steps.sh '''
                echo "最终目录结构:"
                find . -name "*.yml" -type f | head -20
                echo "ansible-playbooks 目录内容:"
                ls -la ansible-playbooks/ 2>/dev/null || echo "ansible-playbooks 目录不存在"
            '''
        } catch (Exception e) {
            steps.echo "❌ 复制 Ansible 目录失败: ${e.getMessage()}"
            throw e
        }
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

        steps.writeFile file: "inventory/${environment}", text: inventoryContent.trim()
        steps.echo "生成的 ${environment} inventory 文件:"
        steps.echo inventoryContent
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
                return '192.168.233.10'
            default:
                return 'localhost'
        }
    }

    def setupSSHKey() {
        steps.withCredentials([steps.sshUserPrivateKey(
                credentialsId: 'ansible-ssh-key',
                keyFileVariable: 'SSH_KEY_FILE',
                usernameVariable: 'SSH_USERNAME'
        )]) {
            steps.sh """
                cp \$SSH_KEY_FILE /tmp/ansible-key
                chmod 600 /tmp/ansible-key
                echo "SSH 密钥已设置"
            """
        }
    }

    def healthCheck(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            def url = getHealthCheckUrl(config.environment, config.projectName, config)

            steps.sh """
                echo "执行健康检查: ${url}"
                max_attempts=30
                attempt=1
                while [ \$attempt -le \$max_attempts ]; do
                    if curl -f ${url}/health; then
                        echo "健康检查通过"
                        break
                    else
                        echo "健康检查尝试 \$attempt/\$max_attempts 失败，等待 10 秒后重试..."
                        sleep 10
                        attempt=\$((attempt+1))
                    fi
                done
                if [ \$attempt -gt \$max_attempts ]; then
                    echo "健康检查失败，已达最大重试次数"
                    exit 1
                fi
                
                # 验证版本信息
                curl -f ${url}/info | grep \"version\":\"${config.version}\" || (echo "版本验证失败" && exit 1)
            """
        }
    }

    private def getHealthCheckUrl(environment, projectName, Map config) {
        def envHost = getEnvironmentHost(config, environment)
        def appPort = config.appPort ?: 8080
        return "http://${envHost}:${appPort}"
    }

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