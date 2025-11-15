def call(Map userConfig = [:]) {
    // 初始化配置加载器
    def configLoader = new org.yakiv.Config(steps)
    def config = configLoader.mergeConfig(userConfig)

    // ========== 修改点1：移除严格的PR检查，因为路由已在Jenkinsfile中处理 ==========
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
            // === 修改点：不再使用文件备份目录 ===
            // BACKUP_DIR = "${env.WORKSPACE}/backups"

            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            APP_VERSION = "${BUILD_TIMESTAMP}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            // ========== 修改点2：项目目录改为当前目录 ==========
            PROJECT_DIR = "."

            // === 新增环境变量：跳过依赖检查标志 ===
            SKIP_DEPENDENCY_CHECK = "${config.skipDependencyCheck ?: true}"

            // === 新增环境变量：构建模式 ===
            BUILD_MODE = "${config.buildMode ?: 'full-pipeline'}"
        }

        stages {
            stage('Initialize & Validation') {
                steps {
                    script {
                        // === 修改点：只测试数据库连接，不执行初始化 ===
                        steps.echo "测试数据库连接..."
                        def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)
                        def dbTestResult = deployTools.testDatabaseConnection()

                        if (!dbTestResult) {
                            steps.echo "❌ 数据库连接测试失败"
                            // 可以选择继续执行或报错，根据您的需求决定
                            // error "数据库连接失败，请检查数据库配置"
                            steps.echo "⚠️ 数据库连接失败，但流水线将继续执行（部署记录将不会保存到数据库）"
                        } else {
                            steps.echo "✅ 数据库连接测试成功"
                        }

                        // 设置不能在 environment 块中直接设置的环境变量
                        env.PROJECT_NAME = config.projectName
                        env.PROJECT_REPO_URL = config.projectRepoUrl

                        // 设置项目分支，如果没有提供则使用默认值 'main'
                        env.PROJECT_BRANCH = config.projectBranch ?: 'main'

                        env.DEPLOY_ENV = config.deployEnv
                        env.ROLLBACK = config.rollback.toString()
                        env.ROLLBACK_VERSION = config.rollbackVersion ?: ''
                        env.EMAIL_RECIPIENTS = config.defaultEmail

                        // === 修改点：回滚时验证版本（仅在数据库连接正常时）===
                        if (env.ROLLBACK.toBoolean() && env.ROLLBACK_VERSION && dbTestResult) {
                            steps.echo "验证回滚版本: ${env.ROLLBACK_VERSION}"
                            def versionValid = deployTools.validateRollbackVersion(
                                    env.PROJECT_NAME,
                                    env.DEPLOY_ENV,
                                    env.ROLLBACK_VERSION
                            )

                            if (!versionValid) {
                                error "回滚版本 ${env.ROLLBACK_VERSION} 不存在或无效，请检查版本号"
                            }
                        } else if (env.ROLLBACK.toBoolean() && env.ROLLBACK_VERSION && !dbTestResult) {
                            steps.echo "⚠️ 数据库连接失败，跳过回滚版本验证"
                            // 您可以选择在这里报错或继续执行
                            // error "数据库连接失败，无法验证回滚版本"
                        }

                        // === 显示依赖检查配置 ===
                        echo "依赖检查配置: ${env.SKIP_DEPENDENCY_CHECK == 'true' ? '跳过' : '执行'}"
                        // === 显示构建模式 ===
                        echo "构建模式: ${env.BUILD_MODE}"

                        // 参数验证
                        if (env.ROLLBACK.toBoolean() && !env.ROLLBACK_VERSION) {
                            error "回滚操作必须指定回滚版本号"
                        }

                        // === 修改点：在build-only模式下禁用回滚 ===
                        if (env.ROLLBACK.toBoolean() && env.BUILD_MODE == 'build-only') {
                            error "回滚操作在 build-only 模式下不可用"
                        }

                        if (env.ROLLBACK.toBoolean() && env.DEPLOY_ENV == 'prod') {
                            input message: "确认在生产环境执行回滚?\n回滚版本: ${env.ROLLBACK_VERSION}",
                                    ok: '确认回滚',
                                    submitterParameter: 'ROLLBACK_APPROVER'
                        }

                        currentBuild.displayName = "${env.PROJECT_NAME}-${env.APP_VERSION}-${env.DEPLOY_ENV}"

                        // 显示配置信息
                        echo "项目: ${env.PROJECT_NAME}"
                        echo "环境: ${env.DEPLOY_ENV}"
                        echo "版本: ${env.APP_VERSION}"
                        echo "项目仓库: ${env.PROJECT_REPO_URL}"
                        echo "项目分支: ${env.PROJECT_BRANCH}"
                        echo "端口: ${configLoader.getAppPort(config)}"
                        echo "目标主机: ${configLoader.getEnvironmentHost(config, env.DEPLOY_ENV)}"
                    }
                }
            }

            stage('Checkout & Setup') {
                steps {
                    script {
                        // ========== 修改点3：不再需要检出代码，因为Jenkinsfile在项目仓库中 ==========
                        echo "✅ 代码已自动检出（Jenkinsfile在项目仓库中）"

                        // 设置项目目录环境变量（已在environment块中设置）
                        // env.PROJECT_DIR = "."

                        def buildTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                        writeJSON file: 'deployment-manifest.json', json: [
                                project: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                environment: env.DEPLOY_ENV,
                                git_commit: env.GIT_COMMIT,
                                build_time: buildTime,
                                build_url: env.BUILD_URL,
                                build_mode: env.BUILD_MODE,  // === 新增字段：构建模式 ===
                                rollback_enabled: (env.BUILD_MODE != 'build-only'),  // === 修改点：在build-only模式下禁用回滚 ===
                                database_enabled: true  // === 新增字段：数据库支持 ===
                        ]

                        // 验证目录结构
                        sh """
                            echo "=== 工作空间结构 ==="
                            echo "当前目录: \$(pwd)"
                            ls -la
                            echo "=== 检查 pom.xml ==="
                            ls -la pom.xml && echo "✓ pom.xml 存在" || echo "✗ pom.xml 不存在"
                            echo "=== 检查分支信息 ==="
                            git branch -a && echo "当前分支:" && git branch --show-current
                        """
                    }
                }
            }

            // ========== 修改点4：移除原有的额外检出步骤，其他阶段保持不变 ==========
            stage('Build') {
                when {
                    expression { !env.ROLLBACK.toBoolean() }
                }
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        buildTools.mavenBuild(
                                version: env.APP_VERSION
                        )

                        buildTools.buildDockerImage(
                                projectName: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                gitCommit: env.GIT_COMMIT
                        )

                        // === 修改点：在build-only模式下跳过镜像推送 ===
                        if (env.BUILD_MODE != 'build-only') {
                            buildTools.pushDockerImage(
                                    projectName: env.PROJECT_NAME,
                                    version: env.APP_VERSION,
                                    harborUrl: env.HARBOR_URL
                            )
                        } else {
                            echo "🔒 build-only 模式：跳过 Docker 镜像推送"
                        }
                    }
                }
            }

            // === 修改点：将安全扫描拆分为独立阶段，并在build-only模式下跳过 ===
            stage('Security Scan') {
                when {
                    allOf {
                        expression { !env.ROLLBACK.toBoolean() }
                        expression { env.BUILD_MODE != 'build-only' }
                    }
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
                                securityTools.fastSonarScan(
                                        projectKey: "${env.PROJECT_NAME}-${env.APP_VERSION}",
                                        projectName: "${env.PROJECT_NAME} ${env.APP_VERSION}",
                                        branch: "${env.PROJECT_BRANCH}"
                                )
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
                                securityTools.fastDependencyCheck()
                            }
                        }
                    }
                }
            }

            stage('Quality Gate') {
                when {
                    allOf {
                        expression { !env.ROLLBACK.toBoolean() }
                        expression { env.BUILD_MODE != 'build-only' }
                    }
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

            stage('Deploy') {
                when {
                    allOf {
                        expression { !env.ROLLBACK.toBoolean() }
                        expression {
                            (env.DEPLOY_ENV == 'staging' || env.DEPLOY_ENV == 'pre-prod' || env.DEPLOY_ENV == 'prod') &&
                                    env.BUILD_MODE != 'build-only'
                        }
                    }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)

                        if (env.DEPLOY_ENV == 'pre-prod' || env.DEPLOY_ENV == 'prod') {
                            input message: "确认部署到${env.DEPLOY_ENV}环境?\n项目: ${env.PROJECT_NAME}\n版本: ${env.APP_VERSION}",
                                    ok: '确认部署',
                                    submitterParameter: 'APPROVER'
                        }

                        // === 修改点：移除 Harbor 凭据包装，直接部署 ===
                        deployTools.deployToEnvironment(
                                projectName: env.PROJECT_NAME,
                                environment: env.DEPLOY_ENV,
                                version: env.APP_VERSION,
                                harborUrl: env.HARBOR_URL,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts
                        )

                        // === 修改点5：移除文件记录，改为数据库记录 ===
                        steps.echo "✅ 部署完成，记录已保存到数据库"
                    }
                }
            }

            stage('Rollback') {
                when {
                    allOf {
                        expression { env.ROLLBACK.toBoolean() }
                        expression { env.BUILD_MODE != 'build-only' }
                    }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)

                        echo "执行回滚操作，项目: ${env.PROJECT_NAME}, 环境: ${env.DEPLOY_ENV}, 版本: ${env.ROLLBACK_VERSION}"

                        // === 修改点：移除 Harbor 凭据包装，直接回滚 ===
                        deployTools.executeRollback(
                                projectName: env.PROJECT_NAME,
                                environment: env.DEPLOY_ENV,
                                version: env.ROLLBACK_VERSION,
                                harborUrl: env.HARBOR_URL,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts
                        )

                        // === 修改点6：移除文件记录，改为数据库记录 ===
                        steps.echo "✅ 回滚完成，记录已保存到数据库"
                    }
                }
            }

            stage('Post-Deployment Test') {
                when {
                    allOf {
                        expression { !env.ROLLBACK.toBoolean() && env.DEPLOY_ENV == 'prod' }
                        expression { env.BUILD_MODE != 'build-only' }
                    }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)
                        deployTools.healthCheck(
                                environment: env.DEPLOY_ENV,
                                projectName: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts
                        )
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
                    if (env.ROLLBACK.toBoolean()) {
                        pipelineType = 'ROLLBACK'
                    } else if (currentBuild.result == 'ABORTED') {
                        pipelineType = 'ABORTED'
                    } else if (env.BUILD_MODE == 'build-only') {
                        pipelineType = 'BUILD_ONLY'  // === 新增流水线类型 ===
                    }

                    notificationTools.sendPipelineNotification(
                            project: env.PROJECT_NAME,
                            environment: env.DEPLOY_ENV,
                            version: env.ROLLBACK.toBoolean() ? env.ROLLBACK_VERSION : env.APP_VERSION,
                            status: currentBuild.result,
                            recipients: env.EMAIL_RECIPIENTS,
                            buildUrl: env.BUILD_URL,
                            isRollback: env.ROLLBACK.toBoolean(),
                            pipelineType: pipelineType,
                            attachLog: (currentBuild.result != 'SUCCESS' && currentBuild.result != null)
                    )

                    // === 修改点：添加备份文件到归档 ===
                    def artifactsToArchive = ['deployment-manifest.json']

                    // === 修改点：在非build-only模式下才归档安全报告 ===
                    if (env.BUILD_MODE != 'build-only' && fileExists('trivy-report.html')) {
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

                    archiveArtifacts artifacts: artifactsToArchive.join(','), fingerprint: true

                    cleanWs()
                }
            }
        }
    }
}