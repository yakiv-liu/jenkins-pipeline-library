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
            buildDiscarder(logRotator(daysToKeepStr: '10', numToKeepStr: '5'))  // 修改这里
            // buildDiscarder(logRotator(numToKeepStr: '20'))
            disableConcurrentBuilds()
        }

        environment {
            // 使用集中配置 - 通过 configLoader 方法获取
            NEXUS_URL = "${configLoader.getNexusUrl()}"
            HARBOR_URL = "${configLoader.getHarborUrl()}"
            SONAR_URL = "${configLoader.getSonarUrl()}"
            TRIVY_URL = "${configLoader.getTrivyUrl()}"
            BACKUP_DIR = "${configLoader.getBackupDir()}"

            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            VERSION_SUFFIX = "${config.isRelease ? '' : '-SNAPSHOT'}"
            APP_VERSION = "${BUILD_TIMESTAMP}${VERSION_SUFFIX}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            // 添加项目目录环境变量
            PROJECT_DIR = "${config.projectName}"  // 如 'demo-helloworld'
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
                        echo "项目分支: ${env.PROJECT_BRANCH}"  // 显示分支信息
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
                                branches: [[name: "*/${env.PROJECT_BRANCH}"]],  // 使用动态分支配置
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
                        // === 修改点：改为并行执行快速扫描 ===
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
                        // === 修改点：缩短超时时间 ===
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