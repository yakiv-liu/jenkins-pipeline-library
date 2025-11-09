package org.yakiv

class NotificationTools implements Serializable {
    def steps
    def env
    def configLoader

    NotificationTools(steps, env = null, configLoader = null) {
        this.steps = steps
        this.env = env
        this.configLoader = configLoader
    }

    def sendPipelineNotification(Map config) {
        try {
            // 确定流水线类型
            def pipelineType = config.pipelineType ?: determinePipelineType(config)
            def status = config.status ?: 'UNKNOWN'
            def finalStatus = (status == null) ? 'SUCCESS' : status

            // 获取邮件模板 - 添加空值检查
            def template = null
            if (configLoader != null) {
                template = configLoader.getEmailTemplate('pipeline')
            }

            def subject, body

            if (template) {
                // 准备模板变量
                def templateVars = [
                        status: finalStatus,
                        project: config.project,
                        environment: config.environment,
                        pipelineType: pipelineType,
                        version: config.version,
                        buildUrl: config.buildUrl,
                        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss z"),
                        statusColor: configLoader?.getStatusColor(finalStatus) ?: '#007cba',
                        headerColor: configLoader?.getHeaderColor(finalStatus) ?: '#007cba',
                        rollbackInfo: config.isRollback ? '<div class="info-item"><strong>🔄 类型:</strong> 回滚操作</div>' : ''
                ]

                // 渲染模板
                subject = renderTemplate(template.subject, templateVars)
                body = renderTemplate(template.body, templateVars)

                steps.echo "✅ 使用自定义邮件模板"
            } else {
                // 当模板不存在时，依赖系统配置中的默认模板
                steps.echo "⚠️ 未找到自定义邮件模板，使用系统配置的默认模板"

                // 构建简单的主题和内容，系统会自动应用默认模板
                subject = "[${finalStatus}] ${config.project} - ${config.environment} - ${pipelineType}"
                body = """
                    项目: ${config.project}
                    环境: ${config.environment}
                    流水线类型: ${pipelineType}
                    版本: ${config.version}
                    状态: ${finalStatus}
                    构建链接: ${config.buildUrl}
                    类型: ${config.isRollback ? '回滚操作' : '部署操作'}
                    时间: ${new Date().format("yyyy-MM-dd HH:mm:ss z")}
                """
            }

            steps.echo "准备发送邮件给: ${config.recipients}"
            steps.echo "邮件主题: ${subject}"

            // 发送邮件 - 使用您配置的QQ邮箱凭据
            steps.emailext(
                    subject: subject,
                    body: body,
                    to: config.recipients,
                    mimeType: template ? 'text/html' : 'text/plain',
                    attachLog: config.attachLog ?: (finalStatus != 'SUCCESS'),
                    compressLog: true,
                    recipientProviders: [[$class: 'RequesterRecipientProvider']],
                    replyTo: config.replyTo ?: '',
                    from: 'jenkins@yourcompany.com',
                    credentialsId: 'qq-email-credentials'
            )

            steps.echo "✅ 邮件发送成功给: ${config.recipients}"

        } catch (Exception e) {
            steps.echo "❌ 邮件发送失败: ${e.getMessage()}"
            steps.echo "详细错误: ${e.stackTraceToString()}"
            // 不抛出异常，避免影响流水线状态
        }
    }

    private def determinePipelineType(Map config) {
        if (config.isRollback) {
            return 'ROLLBACK'
        } else if (config.status == 'ABORTED') {
            return 'ABORTED'
        } else {
            return 'DEPLOYMENT'
        }
    }

    private def renderTemplate(String template, Map variables) {
        def result = template
        variables.each { key, value ->
            result = result.replaceAll("\\{${key}\\}", value?.toString() ?: '')
        }
        return result
    }

    def sendBuildNotification(Map config) {
        try {
            def template = configLoader?.getEmailTemplate('build')
            def status = config.status ?: 'UNKNOWN'
            def finalStatus = (status == null) ? 'SUCCESS' : status

            def subject, body

            if (template) {
                def templateVars = [
                        status: finalStatus,
                        project: config.project,
                        version: config.version,
                        buildUrl: config.buildUrl,
                        isRelease: config.isRelease ? 'Yes' : 'No',
                        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss z")
                ]

                subject = renderTemplate(template.subject, templateVars)
                body = renderTemplate(template.body, templateVars)
            } else {
                subject = "[${finalStatus}] Build - ${config.project}"
                body = """
                    项目: ${config.project}
                    版本: ${config.version}
                    状态: ${finalStatus}
                    构建链接: ${config.buildUrl}
                    发布版本: ${config.isRelease ? '是' : '否'}
                    时间: ${new Date().format("yyyy-MM-dd HH:mm:ss z")}
                """
            }

            steps.emailext(
                    subject: subject,
                    body: body,
                    to: config.recipients,
                    mimeType: template ? 'text/html' : 'text/plain',
                    credentialsId: 'qq-email-credentials'
            )

            steps.echo "✅ 构建通知邮件发送成功"

        } catch (Exception e) {
            steps.echo "❌ 构建通知邮件发送失败: ${e.getMessage()}"
        }
    }

    def sendDeployNotification(Map config) {
        try {
            def template = configLoader?.getEmailTemplate('deploy')
            def status = config.status ?: 'UNKNOWN'
            def finalStatus = (status == null) ? 'SUCCESS' : status

            def subject, body

            if (template) {
                def templateVars = [
                        status: finalStatus,
                        project: config.project,
                        environment: config.environment,
                        version: config.version,
                        buildUrl: config.buildUrl,
                        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss z")
                ]

                subject = renderTemplate(template.subject, templateVars)
                body = renderTemplate(template.body, templateVars)
            } else {
                subject = "[${finalStatus}] Deploy - ${config.project} - ${config.environment}"
                body = """
                    项目: ${config.project}
                    环境: ${config.environment}
                    版本: ${config.version}
                    状态: ${finalStatus}
                    构建链接: ${config.buildUrl}
                    时间: ${new Date().format("yyyy-MM-dd HH:mm:ss z")}
                """
            }

            steps.emailext(
                    subject: subject,
                    body: body,
                    to: config.recipients,
                    mimeType: template ? 'text/html' : 'text/plain',
                    credentialsId: 'qq-email-credentials'
            )

            steps.echo "✅ 部署通知邮件发送成功"

        } catch (Exception e) {
            steps.echo "❌ 部署通知邮件发送失败: ${e.getMessage()}"
        }
    }

    def sendRollbackNotification(Map config) {
        try {
            def template = configLoader?.getEmailTemplate('rollback')
            def status = config.status ?: 'UNKNOWN'
            def finalStatus = (status == null) ? 'SUCCESS' : status

            def subject, body

            if (template) {
                def templateVars = [
                        status: finalStatus,
                        project: config.project,
                        environment: config.environment,
                        version: config.version,
                        buildUrl: config.buildUrl,
                        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss z")
                ]

                subject = renderTemplate(template.subject, templateVars)
                body = renderTemplate(template.body, templateVars)
            } else {
                subject = "[${finalStatus}] Rollback - ${config.project} - ${config.environment}"
                body = """
                    项目: ${config.project}
                    环境: ${config.environment}
                    回滚版本: ${config.version}
                    状态: ${finalStatus}
                    构建链接: ${config.buildUrl}
                    时间: ${new Date().format("yyyy-MM-dd HH:mm:ss z")}
                """
            }

            steps.emailext(
                    subject: subject,
                    body: body,
                    to: config.recipients,
                    mimeType: template ? 'text/html' : 'text/plain',
                    credentialsId: 'qq-email-credentials'
            )

            steps.echo "✅ 回滚通知邮件发送成功"

        } catch (Exception e) {
            steps.echo "❌ 回滚通知邮件发送失败: ${e.getMessage()}"
        }
    }
}