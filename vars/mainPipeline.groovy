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
            buildDiscarder(logRotator(numToKeepStr: '20'))
            disableConcurrentBuilds()
        }

        environment {
            // 使用集中配置
            NEXUS_URL = configLoader.getNexusUrl()
            HARBOR_URL = configLoader.getHarborUrl()
            SONAR_URL = configLoader.getSonarUrl()
            TRIVY_URL = configLoader.getTrivyUrl()
            BACKUP_DIR = configLoader.getBackupDir()

            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            VERSION_SUFFIX = "${config.isRelease ? '' : '-SNAPSHOT'}"
            APP_VERSION = "${BUILD_TIMESTAMP}${VERSION_SUFFIX}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()

            // 从配置中获取参数值
            PROJECT_NAME = config.projectName
            DEPLOY_ENV = config.deployEnv
            IS_RELEASE = config.isRelease
            ROLLBACK = config.rollback
            ROLLBACK_VERSION = config.rollbackVersion
            EMAIL_RECIPIENTS = config.defaultEmail
        }

        stages {
            stage('Initialize & Validation') {
                steps {
                    script {
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
                        echo "端口: ${configLoader.getAppPort(config)}"
                        echo "目标主机: ${configLoader.getEnvironmentHost(config, env.DEPLOY_ENV)}"
                    }
                }
            }

            // 其他阶段保持不变...
            stage('Checkout & Setup') {
                steps {
                    checkout scm
                    script {
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
                    }
                }
            }

            stage('Build & Security Scan') {
                when {
                    expression { !env.ROLLBACK.toBoolean() }
                }
                parallel {
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
                        steps {
                            script {
                                def securityTools = new org.yakiv.SecurityTools(steps, env)
                                securityTools.sonarScan(
                                        projectKey: "${env.PROJECT_NAME}-${env.APP_VERSION}",
                                        projectName: "${env.PROJECT_NAME} ${env.APP_VERSION}"
                                )
                                securityTools.dependencyCheck()
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
                        timeout(time: 10, unit: 'MINUTES') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error "质量门未通过: ${qg.status}"
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

                        deployTools.deployToEnvironment(
                                projectName: env.PROJECT_NAME,
                                environment: env.DEPLOY_ENV,
                                version: env.APP_VERSION,
                                harborUrl: env.HARBOR_URL,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts
                        )

                        script {
                            def deployTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                            writeFile file: "${env.BACKUP_DIR}/${env.PROJECT_NAME}-${env.DEPLOY_ENV}.version", text: env.APP_VERSION
                            writeFile file: "${env.BACKUP_DIR}/${env.PROJECT_NAME}-deployments.log",
                                    text: "${env.APP_VERSION},${env.GIT_COMMIT},${deployTime},${env.DEPLOY_ENV},${env.BUILD_URL}\n",
                                    append: true
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

                        deployTools.executeRollback(
                                projectName: env.PROJECT_NAME,
                                environment: env.DEPLOY_ENV,
                                version: env.ROLLBACK_VERSION,
                                harborUrl: env.HARBOR_URL,
                                appPort: configLoader.getAppPort(config),
                                environmentHosts: config.environmentHosts
                        )

                        script {
                            def rollbackTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                            writeFile file: "${env.BACKUP_DIR}/${env.PROJECT_NAME}-${env.DEPLOY_ENV}.version", text: env.ROLLBACK_VERSION
                            writeFile file: "${env.BACKUP_DIR}/${env.PROJECT_NAME}-rollbacks.log",
                                    text: "${env.ROLLBACK_VERSION},${env.DEPLOY_ENV},rollback,${rollbackTime},${env.BUILD_URL}\n",
                                    append: true
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
                    def notificationTools = new org.yakiv.NotificationTools(steps)
                    notificationTools.sendPipelineNotification(
                            project: env.PROJECT_NAME,
                            environment: env.DEPLOY_ENV,
                            version: env.ROLLBACK.toBoolean() ? env.ROLLBACK_VERSION : env.APP_VERSION,
                            status: currentBuild.result,
                            recipients: env.EMAIL_RECIPIENTS,
                            buildUrl: env.BUILD_URL,
                            isRollback: env.ROLLBACK.toBoolean()
                    )

                    archiveArtifacts artifacts: 'deployment-manifest.json,trivy-report.html', fingerprint: true
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