def call(Map userConfig = [:]) {
    def config = org.yakiv.Config.mergeConfig(userConfig)

    pipeline {
        agent {
            label config.agentLabel
        }

        parameters {
            string(
                    name: 'PROJECT_NAME',
                    defaultValue: config.projectName ?: 'demo-helloworld',
                    description: '项目名称'
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

        environment {
            NEXUS_URL = config.nexusUrl
            HARBOR_URL = config.harborUrl
            SONAR_URL = config.sonarUrl
            TRIVY_URL = config.trivyUrl

            BUILD_TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
            VERSION_SUFFIX = "${params.IS_RELEASE ? '' : '-SNAPSHOT'}"
            APP_VERSION = "${BUILD_TIMESTAMP}${VERSION_SUFFIX}"
            GIT_COMMIT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Build') {
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        buildTools.mavenBuild(
                                version: env.APP_VERSION
//                                isRelease: params.IS_RELEASE
                        )
                    }
                }
            }

            stage('Security Scan') {
                parallel {
                    stage('Code Quality') {
                        steps {
                            script {
                                def securityTools = new org.yakiv.SecurityTools(steps, env)
                                securityTools.sonarScan(
                                        projectKey: "${params.PROJECT_NAME}-${env.APP_VERSION}",
                                        projectName: "${params.PROJECT_NAME} ${env.APP_VERSION}"
                                )
                            }
                        }
                    }
                    stage('Dependency Check') {
                        steps {
                            script {
                                def securityTools = new org.yakiv.SecurityTools(steps, env)
                                securityTools.dependencyCheck()
                            }
                        }
                    }
                }
            }

            stage('Build Image') {
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        buildTools.buildDockerImage(
                                projectName: params.PROJECT_NAME,
                                version: env.APP_VERSION,
                                gitCommit: env.GIT_COMMIT
                        )
                    }
                }
            }

            stage('Scan Image') {
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        buildTools.trivyScan(
                                image: "${env.HARBOR_URL}/${params.PROJECT_NAME}:${env.APP_VERSION}"
                        )
                    }
                }
            }

            stage('Push Image') {
                steps {
                    script {
                        def buildTools = new org.yakiv.BuildTools(steps, env)
                        buildTools.pushDockerImage(
                                projectName: params.PROJECT_NAME,
                                version: env.APP_VERSION,
                                harborUrl: env.HARBOR_URL
                        )
                    }
                }
            }
        }

        post {
            always {
                script {
                    def notificationTools = new org.yakiv.NotificationTools(steps)
                    notificationTools.sendBuildNotification(
                            project: params.PROJECT_NAME,
                            version: env.APP_VERSION,
                            status: currentBuild.result,
                            recipients: params.EMAIL_RECIPIENTS,
                            buildUrl: env.BUILD_URL
//                            isRelease: params.IS_RELEASE
                    )

                    archiveArtifacts artifacts: '**/target/*.jar,**/trivy-report.html', fingerprint: true
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