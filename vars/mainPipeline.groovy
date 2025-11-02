def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)
    
    pipeline {
        // 不指定具体agent，由项目自定义或使用默认any
        agent {
            label config.agentLabel
        }
        
        parameters {
            string(
                name: 'PROJECT_NAME',
                defaultValue: config.projectName ?: 'myapp',
                description: '项目名称'
            )
            choice(
                name: 'DEPLOY_ENV',
                choices: config.environments,
                description: '选择部署环境'
            )
            booleanParam(
                name: 'ROLLBACK',
                defaultValue: false,
                description: '是否执行回滚'
            )
            string(
                name: 'ROLLBACK_VERSION',
                defaultValue: '',
                description: '回滚版本号（格式: timestamp或tag）'
            )
            booleanParam(
                name: 'IS_RELEASE',
                defaultValue: false,
                description: '是否为正式发布版本'
            )
            string(
                name: 'EMAIL_RECIPIENTS',
                defaultValue: config.defaultEmail,
                description: '邮件接收人（多个用逗号分隔）'
            )
        }
        
        options {
            timeout(time: 60, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
            disableConcurrentBuilds()
        }
        
        environment {
            // 使用集中配置
            NEXUS_URL = config.nexusUrl
            HARBOR_URL = config.harborUrl
            SONAR_URL = config.sonarUrl
            TRIVY_URL = config.trivyUrl
            BACKUP_DIR = config.backupDir
            
            // 动态环境变量
            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            VERSION_SUFFIX = "${params.IS_RELEASE ? '' : '-SNAPSHOT'}"
            APP_VERSION = "${BUILD_TIMESTAMP}${VERSION_SUFFIX}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        }
        
        stages {
            stage('Initialize & Validation') {
                steps {
                    script {
                        // 参数验证
                        if (params.ROLLBACK && !params.ROLLBACK_VERSION) {
                            error "回滚操作必须指定回滚版本号"
                        }
                        
                        if (params.ROLLBACK && params.DEPLOY_ENV == 'prod') {
                            input message: "确认在生产环境执行回滚?\n回滚版本: ${params.ROLLBACK_VERSION}",
                            ok: '确认回滚',
                            submitterParameter: 'ROLLBACK_APPROVER'
                        }
                        
                        currentBuild.displayName = "${params.PROJECT_NAME}-${APP_VERSION}-${params.DEPLOY_ENV}"
                    }
                }
            }
            
            stage('Checkout & Setup') {
                steps {
                    checkout scm
                    script {
                        // 创建部署清单
                        writeJSON file: 'deployment-manifest.json', json: [
                            project: params.PROJECT_NAME,
                            version: APP_VERSION,
                            environment: params.DEPLOY_ENV,
                            git_commit: GIT_COMMIT,
                            build_time: sh(script: 'date -Iseconds', returnStdout: true).trim(),
                            build_url: env.BUILD_URL,
                            is_release: params.IS_RELEASE,
                            rollback_enabled: true
                        ]
                    }
                }
            }
            
            stage('Build & Security Scan') {
                when {
                    expression { !params.ROLLBACK }
                }
                parallel {
                    stage('Build') {
                        steps {
                            script {
                                def buildTools = new org.yakiv.BuildTools()
                                buildTools.mavenBuild(
                                    version: APP_VERSION,
                                    isRelease: params.IS_RELEASE
                                )
                                
                                buildTools.buildDockerImage(
                                    projectName: params.PROJECT_NAME,
                                    version: APP_VERSION,
                                    gitCommit: GIT_COMMIT
                                )
                                
                                buildTools.trivyScan(
                                    image: "${HARBOR_URL}/${params.PROJECT_NAME}:${APP_VERSION}"
                                )
                                
                                buildTools.pushDockerImage(
                                    projectName: params.PROJECT_NAME,
                                    version: APP_VERSION,
                                    harborUrl: HARBOR_URL
                                )
                            }
                        }
                    }
                    
                    stage('Security Scan') {
                        steps {
                            script {
                                def securityTools = new org.yakiv.SecurityTools()
                                securityTools.sonarScan(
                                    projectKey: "${params.PROJECT_NAME}-${APP_VERSION}",
                                    projectName: "${params.PROJECT_NAME} ${APP_VERSION}"
                                )
                                securityTools.dependencyCheck()
                            }
                        }
                    }
                }
            }
            
            stage('Quality Gate') {
                when {
                    expression { !params.ROLLBACK }
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
                        !params.ROLLBACK && 
                        (params.DEPLOY_ENV == 'staging' || params.DEPLOY_ENV == 'pre-prod' || params.DEPLOY_ENV == 'prod')
                    }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools()
                        
                        if (params.DEPLOY_ENV == 'pre-prod' || params.DEPLOY_ENV == 'prod') {
                            input message: "确认部署到${params.DEPLOY_ENV}环境?\n项目: ${params.PROJECT_NAME}\n版本: ${APP_VERSION}",
                            ok: '确认部署',
                            submitterParameter: 'APPROVER'
                        }
                        
                        deployTools.deployToEnvironment(
                            projectName: params.PROJECT_NAME,
                            environment: params.DEPLOY_ENV,
                            version: APP_VERSION,
                            harborUrl: HARBOR_URL
                        )
                        
                        // 记录部署
                        sh """
                            echo "${APP_VERSION}" > ${BACKUP_DIR}/${params.PROJECT_NAME}-${params.DEPLOY_ENV}.version
                            echo "${APP_VERSION},${GIT_COMMIT},$(date -Iseconds),${params.DEPLOY_ENV},${env.BUILD_URL}" >> ${BACKUP_DIR}/${params.PROJECT_NAME}-deployments.log
                        """
                    }
                }
            }
            
            stage('Rollback') {
                when {
                    expression { params.ROLLBACK }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools()
                        
                        echo "执行回滚操作，项目: ${params.PROJECT_NAME}, 环境: ${params.DEPLOY_ENV}, 版本: ${params.ROLLBACK_VERSION}"
                        
                        deployTools.executeRollback(
                            projectName: params.PROJECT_NAME,
                            environment: params.DEPLOY_ENV,
                            version: params.ROLLBACK_VERSION,
                            harborUrl: HARBOR_URL
                        )
                        
                        // 记录回滚
                        sh """
                            echo "${params.ROLLBACK_VERSION}" > ${BACKUP_DIR}/${params.PROJECT_NAME}-${params.DEPLOY_ENV}.version
                            echo "${params.ROLLBACK_VERSION},${params.DEPLOY_ENV},rollback,$(date -Iseconds),${env.BUILD_URL}" >> ${BACKUP_DIR}/${params.PROJECT_NAME}-rollbacks.log
                        """
                    }
                }
            }
            
            stage('Post-Deployment Test') {
                when {
                    expression { !params.ROLLBACK && params.DEPLOY_ENV == 'prod' }
                }
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools()
                        deployTools.healthCheck(
                            environment: params.DEPLOY_ENV,
                            projectName: params.PROJECT_NAME,
                            version: APP_VERSION
                        )
                    }
                }
            }
        }
        
        post {
            always {
                script {
                    def notificationTools = new org.yakiv.NotificationTools()
                    notificationTools.sendPipelineNotification(
                        project: params.PROJECT_NAME,
                        environment: params.DEPLOY_ENV,
                        version: params.ROLLBACK ? params.ROLLBACK_VERSION : APP_VERSION,
                        status: currentBuild.result,
                        recipients: params.EMAIL_RECIPIENTS,
                        buildUrl: env.BUILD_URL,
                        isRollback: params.ROLLBACK
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
