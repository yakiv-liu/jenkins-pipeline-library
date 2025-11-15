package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env
    def configLoader
    def dbTools
    // === 新增：当前部署记录跟踪 ===
    def currentDeploymentRecordId = null

    DeployTools(steps, env, configLoader) {
        this.steps = steps
        this.env = env
        this.configLoader = configLoader
        // === 修改点：初始化数据库工具 ===
        this.dbTools = new DatabaseTools(steps, env, configLoader)
    }

    /**
     * 部署到环境 - 基础方法
     */
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

            // === 修改点：部署成功后记录到数据库 ===
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
        }
    }

    /**
     * 执行回滚操作
     */
    def executeRollback(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            // === 修改点：回滚前验证版本是否存在 ===
            steps.echo "验证回滚版本: ${config.version}"
            def versionValid = false

            if (dbTools.testConnection()) {
                versionValid = dbTools.validateRollbackVersion(
                        config.projectName,
                        config.environment,
                        config.version
                )
            } else {
                steps.echo "⚠️ 数据库连接失败，跳过版本验证"
                versionValid = true // 假设版本有效，继续执行
            }

            if (!versionValid) {
                error "回滚版本 ${config.version} 不存在或部署状态异常，无法执行回滚"
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

            // === 修改点：回滚成功后记录到数据库 ===
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
        }
    }

    /**
     * 增强的部署方法 - 包含自动回滚功能
     */
    def deployToEnvironmentWithAutoRollback(Map config) {
        def startTime = System.currentTimeMillis()

        try {
            // === 记录开始信息到 Jenkins 日志 ===
            steps.echo "🚀 开始部署流程"
            steps.echo "项目: ${config.projectName}"
            steps.echo "环境: ${config.environment}"
            steps.echo "版本: ${config.version}"
            steps.echo "构建: ${env.BUILD_URL}"

            // 记录部署元数据到数据库
            recordDeploymentMetadata(config, startTime, 'IN_PROGRESS')

            // 执行部署
            deployToEnvironment(config)

            // === 记录成功信息 ===
            def duration = (System.currentTimeMillis() - startTime) / 1000
            steps.echo "✅ 部署成功完成 - 耗时: ${duration}秒"

            // 更新数据库状态
            updateDeploymentStatus(config, 'SUCCESS', null, duration as Long)

            return true

        } catch (Exception deployError) {
            // === 记录失败信息到 Jenkins 日志 ===
            def duration = (System.currentTimeMillis() - startTime) / 1000
            steps.echo "❌ 部署失败: ${deployError.message}"
            steps.echo "⏱️ 部署耗时: ${duration}秒"

            // 记录详细的错误信息到 Jenkins 日志
            steps.echo "🔍 错误详情:"
            steps.echo deployError.message
            if (deployError.stackTrace) {
                steps.echo "📋 堆栈跟踪:"
                deployError.stackTrace.each { stackLine ->
                    steps.echo "    ${stackLine}"
                }
            }

            // 更新数据库状态
            updateDeploymentStatus(config, 'FAILED', deployError.message, duration as Long)

            // 自动回滚逻辑
            def autoRollbackEnabled = config.autoRollback != false

            if (autoRollbackEnabled && dbTools.testConnection()) {
                steps.echo "🚨 部署失败，开始自动回滚..."
                def rollbackSuccess = executeAutoRollback(config)

                if (rollbackSuccess) {
                    steps.echo "✅ 自动回滚成功完成"
                    // 记录自动回滚成功
                    recordAutoRollbackSuccess(config)
                } else {
                    steps.echo "❌ 自动回滚失败"
                    recordAutoRollbackFailure(config)
                    throw deployError
                }
            } else {
                if (!autoRollbackEnabled) {
                    steps.echo "⚠️ 自动回滚未启用，跳过回滚"
                } else {
                    steps.echo "⚠️ 数据库连接失败，无法执行自动回滚"
                }
                throw deployError
            }
        }
    }

    /**
     * 执行自动回滚（当部署失败时）
     */
    def executeAutoRollback(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            steps.echo "开始自动回滚流程..."

            // === 修改点：获取上一个成功版本 ===
            def previousVersion = null
            if (dbTools.testConnection()) {
                previousVersion = dbTools.getPreviousSuccessfulVersion(
                        config.projectName,
                        config.environment,
                        config.version
                )
            }

            if (!previousVersion) {
                steps.echo "❌ 自动回滚失败：没有找到可用的上一个成功版本"
                return false
            }

            def rollbackVersion = previousVersion.version
            steps.echo "自动回滚到版本: ${rollbackVersion}"

            prepareAnsibleEnvironment(config.environment, config)

            def extraVars = [
                    project_name: config.projectName,
                    rollback_version: rollbackVersion,
                    deploy_env: config.environment,
                    harbor_url: config.harborUrl,
                    app_port: config.appPort,
                    app_dir: getAppDir(config.environment),
                    backup_dir: config.backupDir ?: '/opt/backups'
            ]

            try {
                steps.ansiblePlaybook(
                        playbook: 'ansible-playbooks/rollback.yml',
                        inventory: "inventory/${config.environment}",
                        extraVars: extraVars,
                        credentialsId: 'ansible-ssh-key',
                        disableHostKeyChecking: true
                )

                // 记录自动回滚信息
                if (dbTools.testConnection()) {
                    steps.echo "记录自动回滚信息到数据库..."
                    try {
                        dbTools.recordRollback([
                                projectName: config.projectName,
                                environment: config.environment,
                                rollbackVersion: rollbackVersion,
                                currentVersion: config.version,
                                buildUrl: env.BUILD_URL,
                                jenkinsBuildNumber: env.BUILD_NUMBER?.toInteger(),
                                jenkinsJobName: env.JOB_NAME,
                                rollbackUser: 'auto-rollback-system',
                                reason: "Automatic rollback due to deployment failure",
                                status: 'SUCCESS'
                        ])
                    } catch (Exception e) {
                        steps.echo "⚠️ 自动回滚记录保存失败: ${e.message}"
                    }
                }

                steps.echo "✅ 自动回滚完成: ${config.projectName} ${config.environment} -> ${rollbackVersion}"
                return true

            } catch (Exception e) {
                steps.echo "❌ 自动回滚执行失败: ${e.message}"

                // 记录自动回滚失败
                if (dbTools.testConnection()) {
                    try {
                        dbTools.recordRollback([
                                projectName: config.projectName,
                                environment: config.environment,
                                rollbackVersion: rollbackVersion,
                                currentVersion: config.version,
                                buildUrl: env.BUILD_URL,
                                jenkinsBuildNumber: env.BUILD_NUMBER?.toInteger(),
                                jenkinsJobName: env.JOB_NAME,
                                rollbackUser: 'auto-rollback-system',
                                reason: "Automatic rollback failed: ${e.message}",
                                status: 'FAILED'
                        ])
                    } catch (Exception ex) {
                        steps.echo "⚠️ 自动回滚失败记录保存失败: ${ex.message}"
                    }
                }

                return false
            }
        }
    }

    /**
     * 记录部署元数据到数据库
     */
    private def recordDeploymentMetadata(Map config, Long startTime, String status) {
        if (!dbTools.testConnection()) {
            steps.echo "⚠️ 数据库连接失败，跳过记录部署元数据"
            return
        }

        try {
            def deploymentData = [
                    projectName: config.projectName,
                    environment: config.environment,
                    version: config.version,
                    gitCommit: env.GIT_COMMIT,
                    buildUrl: env.BUILD_URL,
                    buildTimestamp: new Date(startTime),
                    jenkinsBuildNumber: env.BUILD_NUMBER?.toInteger(),
                    jenkinsJobName: env.JOB_NAME,
                    deployUser: env.CHANGE_AUTHOR ?: env.APPROVER ?: 'system',
                    status: status,
                    metadata: [
                            appPort: config.appPort,
                            harborUrl: config.harborUrl,
                            gitBranch: env.PROJECT_BRANCH,
                            buildMode: env.BUILD_MODE,
                            deployEnv: config.environment,
                            startTime: new Date(startTime).format("yyyy-MM-dd HH:mm:ss")
                    ]
            ]

            dbTools.recordDeployment(deploymentData)
            steps.echo "📊 部署元数据已记录到数据库"

        } catch (Exception e) {
            steps.echo "❌ 记录部署元数据失败: ${e.message}"
        }
    }

    /**
     * 更新部署状态
     */
    private def updateDeploymentStatus(Map config, String status, String errorSummary, Long duration) {
        if (!dbTools.testConnection()) {
            steps.echo "⚠️ 数据库连接失败，跳过更新部署状态"
            return
        }

        try {
            // === 修复点：处理 null 值和类型转换 ===
            def safeErrorSummary = errorSummary ?: ""
            def safeDuration = duration != null ? duration.longValue() : 0L

            dbTools.updateDeploymentStatus([
                    projectName: config.projectName,
                    environment: config.environment,
                    version: config.version,
                    status: status,
                    errorSummary: safeErrorSummary,
                    deploymentDuration: safeDuration
            ])

            steps.echo "📊 部署状态已更新: ${status}"

        } catch (Exception e) {
            steps.echo "❌ 更新部署状态失败: ${e.message}"
        }
    }

    /**
     * 记录自动回滚成功
     */
    private def recordAutoRollbackSuccess(Map config) {
        steps.echo "🔄 自动回滚执行成功"
        // 可以在数据库中标记回滚成功，或者保持部署失败状态
    }

    /**
     * 记录自动回滚失败
     */
    private def recordAutoRollbackFailure(Map config) {
        steps.echo "❌ 自动回滚执行失败"
        // 可以在数据库中标记回滚失败
    }

    // === 新增：获取可回滚版本的方法 ===
    def getAvailableRollbackVersions(String projectName, String environment, int limit = 10) {
        return dbTools.getRollbackVersions(projectName, environment, limit)
    }

    // === 新增：验证回滚版本的方法 ===
    def validateRollbackVersion(String projectName, String environment, String version) {
        return dbTools.validateRollbackVersion(projectName, environment, version)
    }

    // === 新增：数据库初始化方法 ===
//    def initializeDatabase() {
//        return dbTools.initializeDatabase()
//    }

    // === 新增：数据库连接测试方法 ===
    def testDatabaseConnection() {
        return dbTools.testConnection()
    }

    // === 原有的辅助方法保持不变 ===
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

            steps.echo "从 file-list.conf 读取到 ${filesToCopy.size()} 个 Ansible 文件需要复制"

            filesToCopy.each { filePath ->
                try {
                    def content = steps.libraryResource("ansible/${filePath}")
                    def fileName = filePath.substring(filePath.lastIndexOf('/') + 1)
                    steps.writeFile file: "ansible-playbooks/${fileName}", text: content
                    steps.echo "✅ 复制 Ansible 文件: ${filePath}"
                } catch (Exception e) {
                    steps.echo "❌ 复制文件失败: ${filePath} - ${e.getMessage()}"
                    throw e
                }
            }

            steps.echo "✅ 所有 Ansible 文件复制完成"

        } catch (Exception e) {
            steps.echo "❌ 无法读取或处理 file-list.conf: ${e.getMessage()}"
            steps.echo "⚠️ 将使用默认的 Ansible 文件列表"
            copyDefaultAnsibleFiles()
        }
    }

    def copyDefaultAnsibleFiles() {
        // === 修改点：从配置文件中读取默认文件列表，而不是硬编码 ===
        try {
            // 尝试从默认配置文件读取
            def defaultFileListContent = steps.libraryResource('ansible/file-list.conf')
            def defaultFiles = defaultFileListContent.readLines().findAll { it.trim() && !it.startsWith('#') }

            steps.echo "使用默认文件列表，包含 ${defaultFiles.size()} 个文件"

            defaultFiles.each { filePath ->
                try {
                    def content = steps.libraryResource("ansible/${filePath}")
                    def fileName = filePath.substring(filePath.lastIndexOf('/') + 1)
                    steps.writeFile file: "ansible-playbooks/${fileName}", text: content
                    steps.echo "✅ 复制默认 Ansible 文件: ${filePath}"
                } catch (Exception e) {
                    steps.echo "❌ 复制默认文件失败: ${filePath} - ${e.getMessage()}"
                    throw e
                }
            }
        } catch (Exception e) {
            steps.echo "❌ 无法读取默认文件列表，使用硬编码的备份列表"
            // 如果连默认配置文件都读不到，使用硬编码的备份（但这种情况不应该发生）
            def backupFiles = [
                    'playbooks/deploy-with-rollback.yml',
                    'playbooks/rollback.yml'
            ]

            backupFiles.each { filePath ->
                try {
                    def content = steps.libraryResource("ansible/${filePath}")
                    def fileName = filePath.substring(filePath.lastIndexOf('/') + 1)
                    steps.writeFile file: "ansible-playbooks/${fileName}", text: content
                    steps.echo "✅ 复制备份 Ansible 文件: ${filePath}"
                } catch (Exception ex) {
                    steps.echo "❌ 复制备份文件失败: ${filePath} - ${ex.getMessage()}"
                    throw ex
                }
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
                echo "开始健康检查..."
                for i in 1 2 3 4 5 6; do
                    echo "尝试第 \\$i 次健康检查..."
                    if curl -f ${url}/health; then
                        echo "✅ 健康检查成功"
                        exit 0
                    fi
                    sleep 5
                done
                echo "❌ 健康检查失败"
                exit 1
            """
        }
    }

    /**
     * 增强的健康检查方法
     */
    def enhancedHealthCheck(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            def targetHost = getEnvironmentHost(config, config.environment)
            def url = "http://${targetHost}:${config.appPort ?: 8085}"

            def healthCheckSuccess = false
            def maxRetries = 6  // 总共30秒 (6 * 5秒)
            def retryCount = 0

            while (retryCount < maxRetries && !healthCheckSuccess) {
                try {
                    // === 修复点：使用 Groovy 变量而不是 Bash 算术表达式 ===
                    def currentAttempt = retryCount + 1
                    steps.sh """
                        echo "健康检查尝试 ${currentAttempt}/${maxRetries}"
                        # 检查基础连通性
                        curl -f -s -o /dev/null -w "HTTP状态码: %{http_code}\\\\n" ${url}/health
                        
                        # 检查应用特定端点（如果有）
                        curl -f -s -o /dev/null -w "应用状态: %{http_code}\\\\n" ${url}/hello || echo "应用特定端点检查跳过"
                        
                        # 检查数据库连接（如果应用有数据库健康检查）
                        curl -f -s -o /dev/null -w "数据库状态: %{http_code}\\\\n" ${url}/actuator/health || echo "执行器健康检查跳过"
                    """
                    healthCheckSuccess = true
                    steps.echo "✅ 健康检查通过"

                } catch (Exception e) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        // === 修复点：使用 Groovy 变量 ===
                        def remainingAttempts = maxRetries - retryCount
                        steps.echo "⚠️ 健康检查失败，5秒后重试... (剩余尝试次数: ${remainingAttempts})"
                        steps.sleep(5)
                    } else {
                        steps.echo "❌ 健康检查最终失败"
                        throw e
                    }
                }
            }

            if (!healthCheckSuccess) {
                throw new Exception("健康检查在 ${maxRetries} 次重试后仍然失败")
            }

            return true
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