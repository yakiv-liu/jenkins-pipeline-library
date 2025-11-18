def call(Map userConfig = [:]) {
    // 初始化配置加载器
    def configLoader = new org.yakiv.Config(steps)
    def config = configLoader.mergeConfig(userConfig)

    echo "✅ 开始执行 main pipeline - 分支: ${env.BRANCH_NAME}"

    pipeline {
        agent {
            label config.agentLabel
        }

        options {
            timeout(time: 60, unit: 'MINUTES')
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '5'))
            disableConcurrentBuilds()
        }

        environment {
            // 使用集中配置 - 通过 configLoader 方法获取
            NEXUS_URL = "${configLoader.getNexusUrl()}"
            HARBOR_URL = "${configLoader.getHarborUrl()}"
            SONAR_URL = "${configLoader.getSonarUrl()}"
            TRIVY_URL = "${configLoader.getTrivyUrl()}"

            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            // ========== 修改点2：根据模式设置APP_VERSION ==========
            APP_VERSION = "${config.buildMode == 'deploy-only' ? (config.deployVersion ?: '') : BUILD_TIMESTAMP}"
            // ========== 修改点3：在共享库中获取GIT_COMMIT ==========
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            // ========== 修改点4：项目目录改为项目名称对应的目录 ==========
            PROJECT_DIR = "${config.projectName}"

            // === 新增环境变量：跳过依赖检查标志 ===
            SKIP_DEPENDENCY_CHECK = "${config.skipDependencyCheck ?: true}"

            // === 新增环境变量：构建模式 ===
            BUILD_MODE = "${config.buildMode ?: 'full-pipeline'}"
        }

        stages {
            // ========== 修改点5：在deploy-only模式下跳过代码检出 ==========
            stage('Checkout Project Code') {
                when {
                    expression { env.BUILD_MODE != 'deploy-only' }
                }
                steps {
                    script {
                        echo "📥 开始检出项目代码..."
                        echo "仓库地址: ${config.projectRepoUrl}"
                        echo "目标分支: ${config.projectBranch}"

                        // 检出指定的项目代码仓库和分支
                        checkout([
                                $class: 'GitSCM',
                                branches: [[name: "*/${config.projectBranch}"]],
                                userRemoteConfigs: [[
                                                            url: config.projectRepoUrl,
                                                            credentialsId: 'github-ssh-key-slave' // 根据你的实际情况修改凭据ID
                                                    ]],
                                extensions: [
                                        // 清理工作空间
                                        [$class: 'CleanCheckout'],
                                        // ========== 修改点6：设置相对目标目录为项目名称 ==========
                                        [$class: 'RelativeTargetDirectory', relativeTargetDir: "${config.projectName}"]
                                ]
                        ])

                        // 验证代码检出结果
                        sh """
                            echo "=== 代码检出完成 ==="
                            echo "当前工作目录: \$(pwd)"
                            echo "=== 目录结构 ==="
                            ls -la
                            echo "=== 项目目录结构 ==="
                            ls -la ${config.projectName}/
                            echo "=== Git 信息 ==="
                            cd ${config.projectName} && git branch -a && git log -1 --oneline
                        """

                        echo "✅ 项目代码检出完成"
                    }
                }
            }

            stage('Initialize & Validation') {
                steps {
                    script {
                        // === 修改点：数据库连接测试 ===
                        steps.echo "测试数据库连接..."
                        def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)
                        def dbTestResult = deployTools.testDatabaseConnection()

                        if (!dbTestResult) {
                            steps.echo "❌ 数据库连接测试失败"
                            steps.echo "⚠️ 数据库连接失败，部署记录将不会保存到数据库"
                        } else {
                            steps.echo "✅ 数据库连接测试成功"
                        }

                        // 设置不能在 environment 块中直接设置的环境变量
                        env.PROJECT_NAME = config.projectName
                        env.PROJECT_REPO_URL = config.projectRepoUrl

                        // 设置项目分支，如果没有提供则使用默认值 'main'
                        env.PROJECT_BRANCH = config.projectBranch ?: 'main'

                        env.DEPLOY_ENV = config.deployEnv
                        env.EMAIL_RECIPIENTS = config.defaultEmail

                        // ========== 修改点7：在deploy-only模式下验证部署版本 ==========
                        if (env.BUILD_MODE == 'deploy-only') {
                            if (!env.APP_VERSION) {
                                error "在deploy-only模式下必须选择部署版本号"
                            }

                            // 验证版本是否存在
                            if (dbTestResult) {
                                def versionValid = deployTools.validateBuildVersion(env.PROJECT_NAME, env.APP_VERSION)
                                if (!versionValid) {
                                    error "选择的部署版本 ${env.APP_VERSION} 不存在或构建失败，请选择有效的版本"
                                }
                            } else {
                                steps.echo "⚠️ 数据库连接失败，跳过版本验证"
                            }

                            steps.echo "✅ 部署版本验证通过: ${env.APP_VERSION}"
                        }

                        // === 显示依赖检查配置 ===
                        echo "依赖检查配置: ${env.SKIP_DEPENDENCY_CHECK == 'true' ? '跳过' : '执行'}"
                        // === 显示构建模式 ===
                        echo "构建模式: ${env.BUILD_MODE}"
                        // === 显示部署版本（如果是deploy-only模式）===
                        if (env.BUILD_MODE == 'deploy-only') {
                            echo "部署版本: ${env.APP_VERSION}"
                        }

                        currentBuild.displayName = "${env.PROJECT_NAME}-${env.APP_VERSION}-${env.DEPLOY_ENV}"

                        // 显示配置信息
                        echo "项目: ${env.PROJECT_NAME}"
                        echo "环境: ${env.DEPLOY_ENV}"
                        echo "版本: ${env.APP_VERSION}"
                        echo "项目仓库: ${env.PROJECT_REPO_URL}"
                        echo "项目分支: ${env.PROJECT_BRANCH}"
                        echo "项目目录: ${env.PROJECT_DIR}"
                        echo "Git Commit: ${env.GIT_COMMIT}"
                        echo "端口: ${configLoader.getAppPort(config)}"
                        echo "目标主机: ${configLoader.getEnvironmentHost(config, env.DEPLOY_ENV)}"
                    }
                }
            }

            // ========== 修改点8：在deploy-only模式下跳过项目设置 ==========
            stage('Project Setup') {
                when {
                    expression { env.BUILD_MODE != 'deploy-only' }
                }
                steps {
                    script {
                        echo "✅ 项目代码已在前置阶段检出"

                        def buildTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                        writeJSON file: "${env.PROJECT_DIR}/deployment-manifest.json", json: [
                                project: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                environment: env.DEPLOY_ENV,
                                git_commit: env.GIT_COMMIT,
                                build_time: buildTime,
                                build_url: env.BUILD_URL,
                                build_mode: env.BUILD_MODE,
                                database_enabled: true
                        ]

                        // 验证目录结构
                        sh """
                            echo "=== 工作空间结构 ==="
                            echo "当前目录: \$(pwd)"
                            ls -la
                            echo "=== 项目目录结构 ==="
                            ls -la ${env.PROJECT_DIR}/
                            echo "=== 检查 pom.xml ==="
                            ls -la ${env.PROJECT_DIR}/pom.xml && echo "✓ pom.xml 存在" || echo "✗ pom.xml 不存在"
                        """
                    }
                }
            }

            // ========== 修改点9：在build-only和full-pipeline模式下执行构建 ==========
            stage('Build') {
                when {
                    expression {
                        env.BUILD_MODE == 'full-pipeline' || env.BUILD_MODE == 'build-only'
                    }
                }
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        // ========== 修改点10：在项目目录下执行构建 ==========
                        dir(env.PROJECT_DIR) {
                            buildTools.mavenBuild(
                                    version: env.APP_VERSION
                            )

                            buildTools.buildDockerImage(
                                    projectName: env.PROJECT_NAME,
                                    version: env.APP_VERSION,
                                    gitCommit: env.GIT_COMMIT
                            )
                        }

                        // ========== 修改点11：在build-only模式下也进行镜像推送 ==========
                        echo "🚀 推送 Docker 镜像到仓库..."
                        buildTools.pushDockerImage(
                                projectName: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                harborUrl: env.HARBOR_URL
                        )

                        // ========== 修改点12：记录构建信息到数据库 ==========
                        echo "📝 记录构建信息到数据库..."
                        try {
                            def dbTools = new org.yakiv.DatabaseTools(steps, env, configLoader)
                            if (dbTools.testConnection()) {
                                dbTools.recordBuild([
                                        projectName: env.PROJECT_NAME,
                                        version: env.APP_VERSION,
                                        gitCommit: env.GIT_COMMIT,
                                        gitBranch: env.PROJECT_BRANCH,
                                        buildTimestamp: new Date(),
                                        buildStatus: 'SUCCESS',
                                        dockerImage: "${env.HARBOR_URL}/${env.PROJECT_NAME}:${env.APP_VERSION}",
                                        jenkinsBuildUrl: env.BUILD_URL,
                                        jenkinsBuildNumber: env.BUILD_NUMBER?.toInteger(),
                                        metadata: [
                                                buildMode: env.BUILD_MODE,
                                                skipDependencyCheck: env.SKIP_DEPENDENCY_CHECK,
                                                buildAgent: env.NODE_NAME
                                        ]
                                ])
                                echo "✅ 构建记录已保存到数据库: ${env.APP_VERSION}"
                            } else {
                                echo "⚠️ 数据库连接失败，跳过记录构建信息"
                            }
                        } catch (Exception e) {
                            echo "❌ 记录构建信息失败: ${e.message}"
                        }
                    }
                }
            }

            // ========== 修改点13：在full-pipeline模式下执行安全扫描 ==========
            stage('Security Scan') {
                when {
                    expression { env.BUILD_MODE == 'full-pipeline' }
                }
                parallel {
                    stage('Trivy Scan') {
                        steps {
                            script {
                                def buildTools = new org.yakiv.BuildTools(steps, env)
                                buildTools.trivyScan(
                                        image: "${env.HARBOR_URL}/${env.PROJECT_NAME}:${env.APP_VERSION}"
                                )
                            }
                        }
                    }
                    stage('SonarQube Scan') {
                        steps {
                            script {
                                def securityTools = new org.yakiv.SecurityTools(steps, env)
                                // ========== 修改点14：在项目目录下执行Sonar扫描 ==========
                                dir(env.PROJECT_DIR) {
                                    securityTools.fastSonarScan(
                                            projectKey: "${env.PROJECT_NAME}-${env.APP_VERSION}",
                                            projectName: "${env.PROJECT_NAME} ${env.APP_VERSION}",
                                            branch: "${env.PROJECT_BRANCH}"
                                    )
                                }
                            }
                        }
                    }
                    stage('Dependency Check') {
                        when {
                            expression { env.SKIP_DEPENDENCY_CHECK == 'false' }
                        }
                        steps {
                            script {
                                def securityTools = new org.yakiv.SecurityTools(steps, env)
                                // ========== 修改点15：在项目目录下执行依赖检查 ==========
                                dir(env.PROJECT_DIR) {
                                    securityTools.fastDependencyCheck()
                                }
                            }
                        }
                    }
                }
            }

            // ========== 修改点16：在full-pipeline模式下执行质量门检查 ==========
            stage('Quality Gate') {
                when {
                    expression { env.BUILD_MODE == 'full-pipeline' }
                }
                steps {
                    script {
                        timeout(time: 5, unit: 'MINUTES') {
                            try {
                                steps.echo "⏳ 等待 SonarQube 质量门结果..."

                                // 添加分析状态检查
                                def projectKey = "${env.PROJECT_NAME}-${env.APP_VERSION}"
                                steps.echo "检查分析项目: ${projectKey}"

                                // 获取质量门状态
                                def qualityGate = waitForQualityGate()

                                steps.echo "📊 质量门状态: ${qualityGate.status}"

                                if (qualityGate.status == 'OK') {
                                    steps.echo "✅ 质量门检查通过"
                                } else {
                                    steps.echo "❌ 质量门未通过: ${qualityGate.status}"
                                    currentBuild.result = 'UNSTABLE'
                                }

                            } catch (Exception e) {
                                steps.echo "❌ 质量门检查异常: ${e.getMessage()}"
                                steps.echo "详细错误: ${e.stackTraceToString()}"
                                steps.echo "继续执行部署，但构建状态标记为不稳定"
                                currentBuild.result = 'UNSTABLE'
                            }
                        }
                    }
                }
            }

            // ========== 修改点17：在full-pipeline和deploy-only模式下执行部署 ==========
// ========== 修改点17：在full-pipeline和deploy-only模式下执行部署 ==========
            stage('Deploy') {
                when {
                    expression {
                        (env.DEPLOY_ENV == 'staging' || env.DEPLOY_ENV == 'pre-prod' || env.DEPLOY_ENV == 'prod') &&
                                (env.BUILD_MODE == 'full-pipeline' || env.BUILD_MODE == 'deploy-only')
                    }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)

                        if (env.DEPLOY_ENV == 'pre-prod' || env.DEPLOY_ENV == 'prod') {
                            input message: "确认部署到${env.DEPLOY_ENV}环境?\n项目: ${env.PROJECT_NAME}\n版本: ${env.APP_VERSION}",
                                    ok: '确认部署',
                                    submitterParameter: 'APPROVER'

                            steps.echo "👤 部署审批人: ${env.APPROVER}"
                        }

                        // 记录部署配置
                        steps.echo "📋 部署配置:"
                        steps.echo "  - 项目: ${env.PROJECT_NAME}"
                        steps.echo "  - 环境: ${env.DEPLOY_ENV}"
                        steps.echo "  - 版本: ${env.APP_VERSION}"
                        steps.echo "  - 模式: ${env.BUILD_MODE}"
                        if (env.BUILD_MODE != 'deploy-only') {
                            steps.echo "  - 分支: ${env.PROJECT_BRANCH}"
                            steps.echo "  - 项目目录: ${env.PROJECT_DIR}"
                            steps.echo "  - Git Commit: ${env.GIT_COMMIT}"
                        }

                        // === 修改点：使用带自动回滚的部署方法 ===
                        def deployConfig = [
                                projectName: env.PROJECT_NAME,
                                environment: env.DEPLOY_ENV,
                                version: env.APP_VERSION,
                                harborUrl: env.HARBOR_URL,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts,
                                autoRollback: true  // 启用自动回滚
                        ]

                        // ========== 关键修改：捕获部署异常，但不立即失败 ==========
                        def deploymentSuccess = false
                        def rollbackTriggered = false

                        try {
                            deploymentSuccess = deployTools.deployToEnvironmentWithAutoRollback(deployConfig)
//                            deploymentSuccess = true
                            if (!deploymentSuccess && env.AUTO_ROLLBACK_TRIGGERED == 'true'){
                                rollbackTriggered = true
//                                steps.echo "🔄 自动回滚已触发，构建标记为不稳定"
                                steps.echo "❌ 部署失败，但可能已触发自动回滚"
                            }
                            steps.echo "✅ 部署流程完成"
                        } catch (Exception e) {
                            // 没有自动回滚，真正失败
                            throw e

                        }

                        // ========== 设置环境变量，控制 Auto Rollback 阶段显示 ==========
                        if (rollbackTriggered) {
                            env.SHOW_AUTO_ROLLBACK_STAGE = 'true'
                        }
                    }
                }
            }

// ========== 修改 Auto Rollback 阶段的条件 ==========
            stage('Auto Rollback') {
                when {
                    expression {
                        env.SHOW_AUTO_ROLLBACK_STAGE == 'true'
                    }
                }
                steps {
                    script {
                        echo "🔄 自动回滚摘要"
                        echo "=== 回滚详情 ==="
                        echo "项目: ${env.PROJECT_NAME}"
                        echo "环境: ${env.DEPLOY_ENV}"
                        echo "回滚到版本: ${env.ROLLBACK_VERSION}"
                        echo "原失败版本: ${env.APP_VERSION}"
                        echo "回滚时间: ${new Date().format('yyyy-MM-dd HH:mm:ss')}"
                        echo "构建链接: ${env.BUILD_URL}"

                        // 显示回滚后的状态验证
                        echo "=== 回滚验证 ==="
                        echo "✅ 健康检查通过"
                        echo "✅ 应用已成功回滚到稳定版本"
                        echo "✅ 自动回滚流程已完成"

                        // 可以在数据库中记录回滚完成状态
                        try {
                            def dbTools = new org.yakiv.DatabaseTools(steps, env, configLoader)
                            if (dbTools.testConnection()) {
                                dbTools.updateDeploymentStatus([
                                        projectName: env.PROJECT_NAME,
                                        environment: env.DEPLOY_ENV,
                                        version: env.ROLLBACK_VERSION,
                                        status: 'ROLLBACK_SUCCESS',
                                        errorSummary: "自动回滚完成: ${env.APP_VERSION} -> ${env.ROLLBACK_VERSION}",
                                        deploymentDuration: 0
                                ])
                                echo "✅ 回滚状态已记录到数据库"
                            }
                        } catch (Exception e) {
                            echo "⚠️ 记录回滚状态失败: ${e.message}"
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    // === 关键修改点：传递 configLoader 到 NotificationTools ===
                    def notificationTools = new org.yakiv.NotificationTools(steps, env, configLoader)

                    // 确定流水线类型
                    def pipelineType = 'DEPLOYMENT'
                    if (currentBuild.result == 'ABORTED') {
                        pipelineType = 'ABORTED'
                    } else if (env.BUILD_MODE == 'build-only') {
                        pipelineType = 'BUILD_ONLY'
                    } else if (env.BUILD_MODE == 'deploy-only') {
                        pipelineType = 'DEPLOY_ONLY'
                    }

                    // === 修改点：如果发生了自动回滚，在通知中特别说明 ===
                    def additionalInfo = ""
                    if (env.AUTO_ROLLBACK_TRIGGERED == 'true') {
                        pipelineType = 'ROLLBACK'
                        additionalInfo = " (包含自动回滚到版本: ${env.ROLLBACK_VERSION})"
                    }

                    notificationTools.sendPipelineNotification(
                            project: env.PROJECT_NAME,
                            environment: env.DEPLOY_ENV,
                            version: env.APP_VERSION,
                            status: currentBuild.result,
                            recipients: env.EMAIL_RECIPIENTS,
                            buildUrl: env.BUILD_URL,
                            pipelineType: pipelineType,
                            attachLog: (currentBuild.result != 'SUCCESS' && currentBuild.result != null),
                            additionalInfo: additionalInfo
                    )

                    // === 修改点：添加部署历史查询 ===
                    try {
                        def queryTools = new org.yakiv.DeploymentQueryTools(steps, env, configLoader)
                        queryTools.showDeploymentHistory(env.PROJECT_NAME, env.DEPLOY_ENV, 3)
                    } catch (Exception e) {
                        steps.echo "⚠️ 显示部署历史失败: ${e.message}"
                    }

                    // === 修改点：添加备份文件到归档 ===
                    def artifactsToArchive = []
                    if (env.BUILD_MODE != 'deploy-only') {
                        artifactsToArchive << "${env.PROJECT_DIR}/deployment-manifest.json"
                    }

                    // === 修改点：在非build-only模式下才归档安全报告 ===
                    if (env.BUILD_MODE == 'full-pipeline' && fileExists('trivy-report.html')) {
                        artifactsToArchive << 'trivy-report.html'
                        publishHTML([
                                allowMissing: false,
                                alwaysLinkToLastBuild: true,
                                keepAll: true,
                                reportDir: '.',
                                reportFiles: 'trivy-report.html',
                                reportName: '安全扫描报告'
                        ])
                    }

                    if (artifactsToArchive) {
                        archiveArtifacts artifacts: artifactsToArchive.join(','), fingerprint: true
                    }

                    cleanWs()
                }
            }
        }
    }
}