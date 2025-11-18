def call(Map userConfig = [:]) {
    // 初始化配置加载器
    def configLoader = new org.yakiv.Config(steps)
    def config = configLoader.mergeConfig(userConfig)

    echo "✅ 开始执行 master pipeline - 分支: ${env.BRANCH_NAME}"

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
            BACKUP_DIR = "${env.WORKSPACE}/backups"

            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
//            VERSION_SUFFIX = "${config.isRelease ? '' : '-SNAPSHOT'}"
//            APP_VERSION = "${BUILD_TIMESTAMP}${VERSION_SUFFIX}"
            APP_VERSION = "${BUILD_TIMESTAMP}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            PROJECT_DIR = "."

            // 跳过依赖检查标志
            SKIP_DEPENDENCY_CHECK = "${config.skipDependencyCheck ?: true}"

            // 定义顺序部署的环境列表
            DEPLOYMENT_ENVIRONMENTS = "staging,pre-prod"
        }

        stages {
            stage('Initialize & Validation') {
                steps {
                    script {
                        // 设置环境变量
                        env.PROJECT_NAME = config.projectName
                        env.PROJECT_REPO_URL = config.projectRepoUrl
                        env.PROJECT_BRANCH = config.projectBranch ?: 'master'
//                        env.IS_RELEASE = config.isRelease.toString()
                        env.ROLLBACK = config.rollback.toString()
                        env.ROLLBACK_VERSION = config.rollbackVersion ?: ''
                        env.EMAIL_RECIPIENTS = config.defaultEmail

                        echo "依赖检查配置: ${env.SKIP_DEPENDENCY_CHECK == 'true' ? '跳过' : '执行'}"

                        // 参数验证
                        if (env.ROLLBACK.toBoolean()) {
                            error "master pipeline 不支持回滚操作，请使用手动部署进行回滚"
                        }

                        currentBuild.displayName = "${env.PROJECT_NAME}-${env.APP_VERSION}"

                        // 显示配置信息
                        echo "项目: ${env.PROJECT_NAME}"
                        echo "版本: ${env.APP_VERSION}"
                        echo "项目仓库: ${env.PROJECT_REPO_URL}"
                        echo "项目分支: ${env.PROJECT_BRANCH}"
                        echo "端口: ${configLoader.getAppPort(config)}"
                        echo "顺序部署环境: ${env.DEPLOYMENT_ENVIRONMENTS}"
                    }
                }
            }

            stage('Checkout & Setup') {
                steps {
                    script {
                        echo "✅ 代码已自动检出（Jenkinsfile在项目仓库中）"

                        def buildTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                        writeJSON file: 'deployment-manifest.json', json: [
                                project: env.PROJECT_NAME,
                                version: env.APP_VERSION,
                                git_commit: env.GIT_COMMIT,
                                build_time: buildTime,
                                build_url: env.BUILD_URL,
//                                is_release: env.IS_RELEASE.toBoolean(),
                                pipeline_type: 'MASTER',
                                deployment_environments: env.DEPLOYMENT_ENVIRONMENTS
                        ]

                        // 验证目录结构
                        sh """
                            echo "=== 工作空间结构 ==="
                            echo "当前目录: \$(pwd)"
                            ls -la
                            echo "=== 检查 pom.xml ==="
                            ls -la pom.xml && echo "✓ pom.xml 存在" || echo "✗ pom.xml 不存在"
                        """
                    }
                }
            }

            stage('Build & Security Scan') {
                stages {
                    stage('Build') {
                        steps {
                            script {
                                def buildTools = new org.yakiv.BuildTools(steps, env)
                                buildTools.mavenBuild(
                                        version: env.APP_VERSION
//                                        isRelease: env.IS_RELEASE.toBoolean()
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
                steps {
                    script {
                        timeout(time: 5, unit: 'MINUTES') {
                            try {
                                steps.echo "⏳ 等待 SonarQube 质量门结果..."
                                def projectKey = "${env.PROJECT_NAME}-${env.APP_VERSION}"
                                steps.echo "检查分析项目: ${projectKey}"

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
                                steps.echo "继续执行部署，但构建状态标记为不稳定"
                                currentBuild.result = 'UNSTABLE'
                            }
                        }
                    }
                }
            }

            stage('Sequential Deployment') {
                steps {
                    script {
                        def environments = env.DEPLOYMENT_ENVIRONMENTS.split(',').collect { it.trim() }

                        environments.each { environment ->
                            stage("Deploy to ${environment.toUpperCase()}") {
                                script {
                                    echo "🚀 开始部署到 ${environment} 环境"
                                    env.DEPLOY_ENV = environment

                                    def deployTools = new org.yakiv.DeployTools(steps, env, configLoader)
                                    deployTools.deployToEnvironment(
                                            projectName: env.PROJECT_NAME,
                                            environment: environment,
                                            version: env.APP_VERSION,
                                            harborUrl: env.HARBOR_URL,
                                            appPort: configLoader.getAppPort(config),
                                            environmentHosts: config.environmentHosts
                                    )

                                    // 记录部署信息
                                    try {
                                        def deployTime = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX")
                                        steps.sh """
                                            mkdir -p ${env.BACKUP_DIR}
                                            echo "${env.APP_VERSION},${env.GIT_COMMIT},${deployTime},${environment},${env.BUILD_URL}" >> "${env.BACKUP_DIR}/${env.PROJECT_NAME}-deployments.log"
                                        """
                                        echo "✅ 成功部署到 ${environment} 环境"
                                    } catch (Exception e) {
                                        echo "警告：部署记录保存失败: ${e.getMessage()}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    def notificationTools = new org.yakiv.NotificationTools(steps, env, configLoader)

                    def pipelineType = 'MASTER_DEPLOYMENT'
                    if (currentBuild.result == 'ABORTED') {
                        pipelineType = 'ABORTED'
                    }

                    notificationTools.sendPipelineNotification(
                            project: env.PROJECT_NAME,
                            environment: env.DEPLOYMENT_ENVIRONMENTS,
                            version: env.APP_VERSION,
                            status: currentBuild.result,
                            recipients: env.EMAIL_RECIPIENTS,
                            buildUrl: env.BUILD_URL,
                            isRollback: false,
                            pipelineType: pipelineType,
                            attachLog: (currentBuild.result != 'SUCCESS' && currentBuild.result != null)
                    )

                    // 归档制品
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