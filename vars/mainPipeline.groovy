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
            // ========== 修改点2：在共享库中获取GIT_COMMIT ==========
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            // ========== 修改点3：项目目录改为项目名称对应的目录 ==========
            PROJECT_DIR = "${config.projectName}"

            // === 新增环境变量：跳过依赖检查标志 ===
            SKIP_DEPENDENCY_CHECK = "${config.skipDependencyCheck ?: true}"

            // === 新增环境变量：构建模式 ===
            BUILD_MODE = "${config.buildMode ?: 'full-pipeline'}"
        }

        stages {
            // ========== 修改点4：新增Checkout阶段 ==========
            stage('Checkout Project Code') {
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
                                        // ========== 修改点5：设置相对目标目录为项目名称 ==========
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
                        env.ROLLBACK = config.rollback.toString()
                        env.ROLLBACK_VERSION = config.rollbackVersion ?: ''
                        env.EMAIL_RECIPIENTS = config.defaultEmail

                        // === 修改点：回滚时验证版本（仅在数据库连接正常时）===
                        if (env.ROLLBACK.toBoolean() && env.ROLLBACK_VERSION) {
                            if (dbTestResult) {
                                steps.echo "验证回滚版本: ${env.ROLLBACK_VERSION}"
                                def versionValid = deployTools.validateRollbackVersion(
                                        env.PROJECT_NAME,
                                        env.DEPLOY_ENV,
                                        env.ROLLBACK_VERSION
                                )

                                if (!versionValid) {
                                    error "回滚版本 ${env.ROLLBACK_VERSION} 不存在或无效，请检查版本号"
                                }
                            } else {
                                steps.echo "⚠️ 数据库连接失败，跳过回滚版本验证"
                                // 您可以选择在这里报错或继续执行
                                // error "数据库连接失败，无法验证回滚版本"
                            }
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
                        echo "项目目录: ${env.PROJECT_DIR}"
                        echo "Git Commit: ${env.GIT_COMMIT}"
                        echo "端口: ${configLoader.getAppPort(config)}"
                        echo "目标主机: ${configLoader.getEnvironmentHost(config, env.DEPLOY_ENV)}"
                    }
                }
            }

            // ========== 修改点6：重命名并简化原来的Checkout & Setup阶段 ==========
            stage('Project Setup') {
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
                                rollback_enabled: (env.BUILD_MODE != 'build-only'),
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

            stage('Build') {
                when {
                    expression { !env.ROLLBACK.toBoolean() }
                }
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        // ========== 修改点7：在项目目录下执行构建 ==========
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
                                // ========== 修改点8：在项目目录下执行Sonar扫描 ==========
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
                                // ========== 修改点9：在项目目录下执行依赖检查 ==========
                                dir(env.PROJECT_DIR) {
                                    securityTools.fastDependencyCheck()
                                }
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

                            steps.echo "👤 部署审批人: ${env.APPROVER}"
                        }

                        // 记录部署配置
                        steps.echo "📋 部署配置:"
                        steps.echo "  - 项目: ${env.PROJECT_NAME}"
                        steps.echo "  - 环境: ${env.DEPLOY_ENV}"
                        steps.echo "  - 版本: ${env.APP_VERSION}"
                        steps.echo "  - 分支: ${env.PROJECT_BRANCH}"
                        steps.echo "  - 项目目录: ${env.PROJECT_DIR}"
                        steps.echo "  - Git Commit: ${env.GIT_COMMIT}"

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

                        deployTools.deployToEnvironmentWithAutoRollback(deployConfig)

                        steps.echo "✅ 部署流程完成"
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

                        steps.echo "🔄 开始执行回滚操作"
                        steps.echo "  - 项目: ${env.PROJECT_NAME}"
                        steps.echo "  - 环境: ${env.DEPLOY_ENV}"
                        steps.echo "  - 回滚版本: ${env.ROLLBACK_VERSION}"
                        steps.echo "  - 审批人: ${env.ROLLBACK_APPROVER ?: '手动触发'}"

                        deployTools.executeRollback([
                                projectName: env.PROJECT_NAME,
                                environment: env.DEPLOY_ENV,
                                version: env.ROLLBACK_VERSION,
                                harborUrl: env.HARBOR_URL,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts
                        ])

                        steps.echo "✅ 回滚操作完成"
                    }
                }
            }

            // === 修改点：移除独立的 Post-Deployment Validation 阶段 ===
            // 健康检查已经在 Ansible playbook 中完成，不需要单独的阶段
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
                        pipelineType = 'BUILD_ONLY'
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

                    // === 修改点：添加部署历史查询 ===
                    try {
                        def queryTools = new org.yakiv.DeploymentQueryTools(steps, env, configLoader)
                        queryTools.showDeploymentHistory(env.PROJECT_NAME, env.DEPLOY_ENV, 3)
                    } catch (Exception e) {
                        steps.echo "⚠️ 显示部署历史失败: ${e.message}"
                    }

                    // === 修改点：添加备份文件到归档 ===
                    def artifactsToArchive = ["${env.PROJECT_DIR}/deployment-manifest.json"]

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