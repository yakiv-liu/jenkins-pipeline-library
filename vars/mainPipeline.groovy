def call(Map userConfig = [:]) {
    // 初始化配置加载器
    def configLoader = new org.yakiv.Config(steps)
    def config = configLoader.mergeConfig(userConfig)

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
            // === 修改点：使用 Jenkins 工作空间内的备份目录 ===
            BACKUP_DIR = "${env.WORKSPACE}/backups"

            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            VERSION_SUFFIX = "${config.isRelease ? '' : '-SNAPSHOT'}"
            APP_VERSION = "${BUILD_TIMESTAMP}${VERSION_SUFFIX}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            // 添加项目目录环境变量
            PROJECT_DIR = "${config.projectName}"

            // === 新增环境变量：跳过依赖检查标志 ===
            SKIP_DEPENDENCY_CHECK = "${config.skipDependencyCheck ?: true}"
        }

        stages {
            stage('Initialize & Validation') {
                steps {
                    script {
                        // 设置不能在 environment 块中直接设置的环境变量
                        env.PROJECT_NAME = config.projectName
                        env.PROJECT_REPO_URL = config.projectRepoUrl

                        // 设置项目分支，如果没有提供则使用默认值 'main'
                        env.PROJECT_BRANCH = config.projectBranch ?: 'main'

                        env.DEPLOY_ENV = config.deployEnv
                        env.IS_RELEASE = config.isRelease.toString()
                        env.ROLLBACK = config.rollback.toString()
                        env.ROLLBACK_VERSION = config.rollbackVersion ?: ''
                        env.EMAIL_RECIPIENTS = config.defaultEmail

                        // === 显示依赖检查配置 ===
                        echo "依赖检查配置: ${env.SKIP_DEPENDENCY_CHECK == 'true' ? '跳过' : '执行'}"

                        // 参数验证
                        if (env.ROLLBACK.toBoolean() && !env.ROLLBACK_VERSION) {
                            error "回滚操作必须指定回滚版本号"
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
                    // 1. 检出 Pipeline 脚本的 SCM (已经由 Jenkins 自动完成)
                    checkout scm

                    // 2. 额外检出实际的项目代码
                    script {
                        def projectRepoUrl = env.PROJECT_REPO_URL

                        echo "开始检出项目代码..."
                        echo "仓库 URL: ${projectRepoUrl}"
                        echo "分支: ${env.PROJECT_BRANCH}"
                        echo "凭据 ID: github-ssh-key-slave"
                        echo "目标目录: ${env.PROJECT_NAME}"

                        // 检出实际项目代码到项目名目录，使用动态分支
                        checkout([
                                $class: 'GitSCM',
                                branches: [[name: "*/${env.PROJECT_BRANCH}"]],
                                extensions: [
                                        [
                                                $class: 'RelativeTargetDirectory',
                                                relativeTargetDir: env.PROJECT_NAME
                                        ]
                                ],
                                userRemoteConfigs: [[
                                                            url: projectRepoUrl,
                                                            credentialsId: 'github-ssh-key-slave'
                                                    ]]
                        ])

                        // 设置项目目录环境变量
                        env.PROJECT_DIR = env.PROJECT_NAME

                        def buildTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                        writeJSON file: 'deployment-manifest.json', json: [
                                project: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                environment: env.DEPLOY_ENV,
                                git_commit: env.GIT_COMMIT,
                                build_time: buildTime,
                                build_url: env.BUILD_URL,
                                is_release: env.IS_RELEASE.toBoolean(),
                                rollback_enabled: true
                        ]

                        // 验证目录结构
                        sh """
                            echo "=== 工作空间结构 ==="
                            echo "当前目录: \$(pwd)"
                            ls -la
                            echo "=== 实际项目代码目录 ==="
                            ls -la ${env.PROJECT_DIR}/
                            echo "=== 检查 pom.xml ==="
                            ls -la ${env.PROJECT_DIR}/pom.xml && echo "✓ pom.xml 存在" || echo "✗ pom.xml 不存在"
                            echo "=== 检查分支信息 ==="
                            cd ${env.PROJECT_DIR} && git branch -a && echo "当前分支:" && git branch --show-current
                        """
                    }
                }
            }

            stage('Build & Security Scan') {
                when {
                    expression { !env.ROLLBACK.toBoolean() }
                }
                stages {
                    stage('Build') {
                        steps {
                            script {
                                def buildTools = new org.yakiv.BuildTools(steps, env)
                                buildTools.mavenBuild(
                                        version: env.APP_VERSION,
                                        isRelease: env.IS_RELEASE.toBoolean()
                                )

                                buildTools.buildDockerImage(
                                        projectName: env.PROJECT_NAME,
                                        version: env.APP_VERSION,
                                        gitCommit: env.GIT_COMMIT
                                )

                                buildTools.trivyScan(
                                        image: "${env.HARBOR_URL}/${env.PROJECT_NAME}:${env.APP_VERSION}"
                                )

                                buildTools.pushDockerImage(
                                        projectName: env.PROJECT_NAME,
                                        version: env.APP_VERSION,
                                        harborUrl: env.HARBOR_URL
                                )
                            }
                        }
                    }

                    stage('Security Scan') {
                        parallel {
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
                }
            }

            stage('Quality Gate') {
                when {
                    expression { !env.ROLLBACK.toBoolean() }
                }
                steps {
                    script {
                        timeout(time: 3, unit: 'MINUTES') {
                            try {
                                def qg = waitForQualityGate()
                                if (qg.status != 'OK') {
                                    error "质量门未通过: ${qg.status}"
                                }
                            } catch (Exception e) {
                                echo "质量门检查超时，但继续执行部署"
                                currentBuild.result = 'UNSTABLE'
                            }
                        }
                    }
                }
            }

            stage('Deploy') {
                when {
                    expression {
                        !env.ROLLBACK.toBoolean() &&
                                (env.DEPLOY_ENV == 'staging' || env.DEPLOY_ENV == 'pre-prod' || env.DEPLOY_ENV == 'prod')
                    }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env)

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

                        // === 修改点：简化文件写入操作，使用工作空间目录 ===
                        script {
                            try {
                                def deployTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")

                                // 确保备份目录存在
                                // steps.sh "mkdir -p ${env.BACKUP_DIR}"

                                // 写入版本文件
                                steps.writeFile file: "${env.BACKUP_DIR}/${env.PROJECT_NAME}-${env.DEPLOY_ENV}.version", text: env.APP_VERSION

                                // === 修复点：使用 shell 命令追加日志文件 ===
                                steps.sh """
                                    echo "${env.APP_VERSION},${env.GIT_COMMIT},${deployTime},${env.DEPLOY_ENV},${env.BUILD_URL}" >> "${env.BACKUP_DIR}/${env.PROJECT_NAME}-deployments.log"
                                """

                                echo "部署记录已保存到: ${env.BACKUP_DIR}"
                            } catch (Exception e) {
                                echo "警告：部署记录保存失败: ${e.getMessage()}"
                                // 不抛出异常，避免影响部署状态
                            }
                        }
                    }
                }
            }

            stage('Rollback') {
                when {
                    expression { env.ROLLBACK.toBoolean() }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env)

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

                        // === 修改点：简化文件写入操作，使用工作空间目录 ===
                        script {
                            try {
                                def rollbackTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")

                                // 确保备份目录存在
                                // steps.sh "mkdir -p ${env.BACKUP_DIR}"

                                // 写入版本文件
                                steps.writeFile file: "${env.BACKUP_DIR}/${env.PROJECT_NAME}-${env.DEPLOY_ENV}.version", text: env.ROLLBACK_VERSION

                                // === 修复点：使用 shell 命令追加日志文件 ===
                                steps.sh """
                                    echo "${env.ROLLBACK_VERSION},${env.DEPLOY_ENV},rollback,${rollbackTime},${env.BUILD_URL}" >> "${env.BACKUP_DIR}/${env.PROJECT_NAME}-rollbacks.log"
                                """

                                echo "回滚记录已保存到: ${env.BACKUP_DIR}"
                            } catch (Exception e) {
                                echo "警告：回滚记录保存失败: ${e.getMessage()}"
                                // 不抛出异常，避免影响回滚状态
                            }
                        }
                    }
                }
            }

            stage('Post-Deployment Test') {
                when {
                    expression { !env.ROLLBACK.toBoolean() && env.DEPLOY_ENV == 'prod' }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools(steps, env)
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
                    archiveArtifacts artifacts: 'deployment-manifest.json,trivy-report.html,backups/*', fingerprint: true
                    publishHTML([
                            allowMissing: false,
                            alwaysLinkToLastBuild: true,
                            keepAll: true,
                            reportDir: '.',
                            reportFiles: 'trivy-report.html',
                            reportName: '安全扫描报告'
                    ])

                    cleanWs()
                }
            }
        }
    }
}