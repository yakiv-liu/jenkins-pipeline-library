def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)
    
    pipeline {
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
            string(
                name: 'VERSION',
                description: '部署版本号'
            )
            string(
                name: 'EMAIL_RECIPIENTS',
                defaultValue: config.defaultEmail,
                description: '邮件接收人（多个用逗号分隔）'
            )
        }
        
        environment {
            HARBOR_URL = config.harborUrl
            BACKUP_DIR = config.backupDir
        }
        
        stages {
            stage('Validate') {
                steps {
                    script {
                        if (!params.VERSION) {
                            error "部署版本号不能为空"
                        }
                        
                        if (params.DEPLOY_ENV == 'prod') {
                            input message: "确认部署到生产环境?\n项目: ${params.PROJECT_NAME}\n版本: ${params.VERSION}",
                            ok: '确认部署',
                            submitterParameter: 'APPROVER'
                        }
                    }
                }
            }
            
            stage('Deploy') {
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools()
                        
                        if (params.DEPLOY_ENV == 'pre-prod') {
                            input message: "确认部署到预生产环境?\n项目: ${params.PROJECT_NAME}\n版本: ${params.VERSION}",
                            ok: '确认部署',
                            submitterParameter: 'PREPROD_APPROVER'
                        }
                        
                        deployTools.deployToEnvironment(
                            projectName: params.PROJECT_NAME,
                            environment: params.DEPLOY_ENV,
                            version: params.VERSION,
                            harborUrl: env.HARBOR_URL
                        )
                        
                        // 记录部署
                        sh """
                            echo "${params.VERSION}" > ${env.BACKUP_DIR}/${params.PROJECT_NAME}-${params.DEPLOY_ENV}.version
                            echo "${params.VERSION},$(date -Iseconds),${params.DEPLOY_ENV},${env.BUILD_URL}" >> ${env.BACKUP_DIR}/${params.PROJECT_NAME}-deployments.log
                        """
                    }
                }
            }
            
            stage('Verify') {
                steps {
                    script {
                        def deployTools = new org.yakiv.DeployTools()
                        deployTools.healthCheck(
                            environment: params.DEPLOY_ENV,
                            projectName: params.PROJECT_NAME,
                            version: params.VERSION
                        )
                    }
                }
            }
        }
        
        post {
            always {
                script {
                    def notificationTools = new org.yakiv.NotificationTools()
                    notificationTools.sendDeployNotification(
                        project: params.PROJECT_NAME,
                        environment: params.DEPLOY_ENV,
                        version: params.VERSION,
                        status: currentBuild.result,
                        recipients: params.EMAIL_RECIPIENTS,
                        buildUrl: env.BUILD_URL
                    )
                }
            }
        }
    }
}
