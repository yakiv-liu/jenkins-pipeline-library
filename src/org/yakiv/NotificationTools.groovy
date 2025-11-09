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
            // === 修改点：添加详细的调试信息 ===
            steps.echo "=== 邮件模板调试信息 ==="
            steps.echo "configLoader 是否为 null: ${configLoader == null}"

            if (configLoader != null) {
                steps.echo "configLoader 类型: ${configLoader.getClass().name}"
                // 测试是否能调用其他方法
                try {
                    def testColor = configLoader.getStatusColor('SUCCESS')
                    steps.echo "能调用 getStatusColor: ${testColor != null}"
                } catch (Exception e) {
                    steps.echo "调用 getStatusColor 失败: ${e.message}"
                }
            }

            // 确定流水线类型
            def pipelineType = config.pipelineType ?: determinePipelineType(config)
            def status = config.status ?: 'UNKNOWN'
            def finalStatus = (status == null) ? 'SUCCESS' : status

            // 获取邮件模板 - 添加详细调试
            steps.echo "开始获取邮件模板..."
            def template = null
            if (configLoader != null) {
                try {
                    template = configLoader.getEmailTemplate('pipeline')
                    steps.echo "getEmailTemplate 调用完成，结果: ${template != null ? '非空' : 'null'}"
                    if (template != null) {
                        steps.echo "模板内容 - subject: ${template.subject != null}"
                        steps.echo "模板内容 - body: ${template.body != null}"
                    }
                } catch (Exception e) {
                    steps.echo "调用 getEmailTemplate 失败: ${e.message}"
                    steps.echo "异常堆栈: ${e.stackTraceToString()}"
                }
            } else {
                steps.echo "configLoader 为 null，无法获取模板"
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

            // === 修复点：使用最简化的 emailext 参数 ===
            steps.emailext(
                    subject: subject,
                    body: body,
                    to: config.recipients
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
                    to: config.recipients
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
                    mimeType: 'text/html'
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
                    mimeType: 'text/html'
            )

            steps.echo "✅ 回滚通知邮件发送成功"

        } catch (Exception e) {
            steps.echo "❌ 回滚通知邮件发送失败: ${e.getMessage()}"
        }
    }
}