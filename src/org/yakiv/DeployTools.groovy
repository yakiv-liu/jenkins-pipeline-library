package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env
    def configLoader
    // === 修改点1：添加数据库工具实例 ===
    def dbTools

    DeployTools(steps, env, configLoader) {
        this.steps = steps
        this.env = env
        this.configLoader = configLoader
        // === 修改点2：初始化数据库工具 ===
        this.dbTools = new DatabaseTools(steps, env, configLoader)
    }

    def deployToEnvironment(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            prepareAnsibleEnvironment(config.environment, config)

            // === 修改点：移除 Harbor 凭据传递，因为宿主机已经配置好 ===
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

            steps.ansiblePlaybook(
                    playbook: 'ansible-playbooks/deploy-with-rollback.yml',
                    inventory: "inventory/${config.environment}",
                    extraVars: extraVars,
                    credentialsId: 'ansible-ssh-key',
                    disableHostKeyChecking: true
            )

            // === 修改点：只在数据库连接正常时记录部署信息 ===
            steps.echo "检查数据库连接状态..."
            if (dbTools.testConnection()) {
                steps.echo "开始记录部署信息到数据库..."
                try {
                    dbTools.recordDeployment([
                            projectName: config.projectName,
                            environment: config.environment,
                            version: config.version,
                            gitCommit: env.GIT_COMMIT,
                            buildUrl: env.BUILD_URL,
                            buildTimestamp: new Date(),
                            jenkinsBuildNumber: env.BUILD_NUMBER?.toInteger(),
                            jenkinsJobName: env.JOB_NAME,
                            deployUser: env.CHANGE_AUTHOR ?: env.APPROVER ?: 'system',
                            metadata: [
                                    appPort: config.appPort,
                                    harborUrl: config.harborUrl,
                                    gitBranch: env.PROJECT_BRANCH,
                                    buildMode: env.BUILD_MODE,
                                    deployEnv: config.environment
                            ]
                    ])
                } catch (Exception e) {
                    steps.echo "⚠️ 部署记录保存失败，但不影响部署流程: ${e.message}"
                }
            } else {
                steps.echo "⚠️ 数据库连接不可用，跳过部署记录"
            }
        }
    }

    def executeRollback(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            // === 修改点：只在数据库连接正常时验证版本 ===
            if (dbTools.testConnection()) {
                steps.echo "验证回滚版本: ${config.version}"
                def versionValid = dbTools.validateRollbackVersion(
                        config.projectName,
                        config.environment,
                        config.version
                )

                if (!versionValid) {
                    error "回滚版本 ${config.version} 不存在或部署状态异常，无法执行回滚"
                }
            } else {
                steps.echo "⚠️ 数据库连接不可用，跳过回滚版本验证"
                // 您可以选择在这里报错或继续执行
                // error "数据库连接失败，无法验证回滚版本"
            }

            prepareAnsibleEnvironment(config.environment, config)

            // === 修改点：移除 Harbor 凭据传递，因为宿主机已经配置好 ===
            def extraVars = [
                    project_name: config.projectName,
                    rollback_version: config.version,
                    deploy_env: config.environment,
                    harbor_url: config.harborUrl,
                    app_port: config.appPort,
                    app_dir: getAppDir(config.environment),
                    backup_dir: config.backupDir ?: '/opt/backups'
            ]

            steps.ansiblePlaybook(
                    playbook: 'ansible-playbooks/rollback.yml',
                    inventory: "inventory/${config.environment}",
                    extraVars: extraVars,
                    credentialsId: 'ansible-ssh-key',
                    disableHostKeyChecking: true
            )

            // === 修改点：只在数据库连接正常时记录回滚信息 ===
            if (dbTools.testConnection()) {
                steps.echo "开始记录回滚信息到数据库..."
                try {
                    def currentVersion = dbTools.getLatestVersion(config.projectName, config.environment)
                    dbTools.recordRollback([
                            projectName: config.projectName,
                            environment: config.environment,
                            rollbackVersion: config.version,
                            currentVersion: currentVersion ?: 'unknown',
                            buildUrl: env.BUILD_URL,
                            jenkinsBuildNumber: env.BUILD_NUMBER?.toInteger(),
                            jenkinsJobName: env.JOB_NAME,
                            rollbackUser: env.ROLLBACK_APPROVER ?: 'system',
                            reason: "Manual rollback via Jenkins",
                            status: 'SUCCESS'
                    ])
                } catch (Exception e) {
                    steps.echo "⚠️ 回滚记录保存失败，但不影响回滚流程: ${e.message}"
                }
            } else {
                steps.echo "⚠️ 数据库连接不可用，跳回过滚记录"
            }
        }
    }

    // === 修改点6：新增获取可回滚版本的方法 ===
    def getAvailableRollbackVersions(String projectName, String environment, int limit = 10) {
        return dbTools.getRollbackVersions(projectName, environment, limit)
    }

    // === 修改点7：新增验证回滚版本的方法 ===
    def validateRollbackVersion(String projectName, String environment, String version) {
        return dbTools.validateRollbackVersion(projectName, environment, version)
    }

    // === 修改点8：新增数据库初始化方法 ===
//    def initializeDatabase() {
//        return dbTools.initializeDatabase()
//    }

    // === 修改点9：新增数据库连接测试方法 ===
    def testDatabaseConnection() {
        return dbTools.testConnection()
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
        def appPort = config.appPort ?: 8085

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
            def url = "http://${targetHost}:${config.appPort ?: 8085}"

            steps.sh """
                for i in {1..30}; do
                    curl -f ${url}/health && exit 0
                    sleep 10
                done
                exit 1
            """
        }
    }

    private def getHealthCheckUrl(environment, projectName, Map config) {
        "http://${getEnvironmentHost(config, environment)}:${config.appPort ?: 8085}"
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