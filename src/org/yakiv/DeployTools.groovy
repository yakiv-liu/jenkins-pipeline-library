package org.yakiv

class DeployTools implements Serializable {
    def steps
    def env
    def configLoader
    def dbTools

    DeployTools(steps, env, configLoader) {
        this.steps = steps
        this.env = env
        this.configLoader = configLoader
        this.dbTools = new DatabaseTools(steps, env, configLoader)
    }

    /**
     * 部署到环境 - 基础方法
     */
    def deployToEnvironment(Map config) {
        // ========== 修改点1：在deploy-only模式下使用不同目录 ==========
        def workspaceDir = env.BUILD_MODE == 'deploy-only' ? "${env.WORKSPACE}" : "${env.WORKSPACE}/${env.PROJECT_DIR}"

        steps.dir(workspaceDir) {
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
                    // ========== 修改点2：在deploy-only模式下使用未知git commit ==========
                    git_commit: env.BUILD_MODE == 'deploy-only' ? 'deploy-only-no-commit' : (env.GIT_COMMIT ?: 'unknown')
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
                        gitCommit: env.BUILD_MODE == 'deploy-only' ? 'deploy-only' : env.GIT_COMMIT,
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
     * 增强的部署方法 - 包含自动回滚功能
     */
    def deployToEnvironmentWithAutoRollback(Map config) {
        def startTime = System.currentTimeMillis()

        try {
            steps.echo "🚀 开始部署流程"
            steps.echo "项目: ${config.projectName}"
            steps.echo "环境: ${config.environment}"
            steps.echo "版本: ${config.version}"
            steps.echo "构建: ${env.BUILD_URL}"

            // 记录部署元数据到数据库
            recordDeploymentMetadata(config, startTime, 'IN_PROGRESS')

            // 执行部署
            deployToEnvironment(config)

            def duration = (System.currentTimeMillis() - startTime) / 1000
            steps.echo "✅ 部署成功完成 - 耗时: ${duration}秒"

            // 更新数据库状态
            updateDeploymentStatus(config, 'SUCCESS', null, duration as Long)

            return true

        } catch (Exception deployError) {
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

                // ========== 新增：设置环境变量以触发回滚阶段显示 ==========
                env.AUTO_ROLLBACK_TRIGGERED = 'true'

                def rollbackSuccess = executeAutoRollback(config)

                if (rollbackSuccess) {
                    steps.echo "✅ 自动回滚成功完成"
                    // 记录自动回滚成功
                    recordAutoRollbackSuccess(config)

                    // ========== 新增：设置构建结果为不稳定，因为部署失败但回滚成功 ==========
                    currentBuild.result = 'UNSTABLE'
                    steps.echo "⚠️ 构建标记为不稳定：部署失败但自动回滚成功"
                } else {
                    steps.echo "❌ 自动回滚失败"
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
            steps.echo "🔄 开始自动回滚流程..."
            steps.echo "=== 自动回滚详细信息 ==="
            steps.echo "项目: ${config.projectName}"
            steps.echo "环境: ${config.environment}"
            steps.echo "失败版本: ${config.version}"
            steps.echo "开始时间: ${new Date().format('yyyy-MM-dd HH:mm:ss')}"

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
                env.ROLLBACK_VERSION = 'NONE_AVAILABLE'
                return false
            }

            def rollbackVersion = previousVersion.version
            steps.echo "🎯 找到可回滚版本: ${rollbackVersion}"
            steps.echo "构建时间: ${new Date(previousVersion.deploy_time.time).format('yyyy-MM-dd HH:mm:ss')}"
            steps.echo "Git Commit: ${previousVersion.git_commit}"

            // ========== 新增：设置回滚版本环境变量 ==========
            env.ROLLBACK_VERSION = rollbackVersion

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
                steps.echo "🚀 执行 Ansible 回滚 Playbook..."
                steps.ansiblePlaybook(
                        playbook: 'ansible-playbooks/rollback.yml',
                        inventory: "inventory/${config.environment}",
                        extraVars: extraVars,
                        credentialsId: 'ansible-ssh-key',
                        disableHostKeyChecking: true
                )

                steps.echo "✅ Ansible 回滚执行完成"

                // 记录自动回滚信息
                if (dbTools.testConnection()) {
                    steps.echo "📝 记录自动回滚信息到数据库..."
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
                                status: 'SUCCESS',
                                metadata: [
                                        originalDeployTime: new Date(),
                                        rollbackTrigger: 'auto',
                                        deploymentError: "Deployment failed for version ${config.version}"
                                ]
                        ])
                        steps.echo "✅ 自动回滚记录已保存到数据库"
                    } catch (Exception e) {
                        steps.echo "⚠️ 自动回滚记录保存失败: ${e.message}"
                    }
                }

                steps.echo "🎉 自动回滚完成: ${config.projectName} ${config.environment} -> ${rollbackVersion}"

                // ========== 新增：回滚后健康检查 ==========
                steps.echo "🔍 执行回滚后健康检查..."
                try {
                    enhancedHealthCheck(config)
                    steps.echo "✅ 回滚后健康检查通过"
                } catch (Exception e) {
                    steps.echo "⚠️ 回滚后健康检查失败，但回滚流程已完成: ${e.message}"
                }

                return true

            } catch (Exception e) {
                steps.echo "❌ 自动回滚执行失败: ${e.message}"
                steps.echo "详细错误信息: ${e.stackTrace.take(10).join('\n')}"

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
                                status: 'FAILED',
                                metadata: [
                                        errorDetails: e.message,
                                        stackTrace: e.stackTrace.take(5).join('; ')
                                ]
                        ])
                        steps.echo "⚠️ 自动回滚失败记录已保存"
                    } catch (Exception ex) {
                        steps.echo "⚠️ 自动回滚失败记录保存失败: ${ex.message}"
                    }
                }

                return false
            }
        }
    }

    // ... 其余方法保持不变 ...
}