package org.yakiv

class NotificationTools implements Serializable {
    def steps

    NotificationTools(steps) {
        this.steps = steps
    }

    def sendPipelineNotification(Map config) {
        def subject = "[${config.status}] ${config.project} - ${config.environment} - ${config.pipelineType}"
        def body = """
        Project: ${config.project}
        Environment: ${config.environment}
        Pipeline Type: ${config.pipelineType}
        Version: ${config.version}
        Status: ${config.status}
        Build: ${config.buildUrl}
        ${config.isRollback ? 'Type: Rollback' : 'Type: Deployment'}
        """

        steps.emailext(
                subject: subject,
                body: body,
                to: config.recipients,
                attachLog: config.attachLog ?: false
        )
    }

    def sendBuildNotification(Map config) {
        def subject = "[${config.status}] Build - ${config.project}"
        def body = """
        Project: ${config.project}
        Version: ${config.version}
        Status: ${config.status}
        Build: ${config.buildUrl}
        Release: ${config.isRelease ? 'Yes' : 'No'}
        """

        steps.emailext(
                subject: subject,
                body: body,
                to: config.recipients
        )
    }

    def sendDeployNotification(Map config) {
        def subject = "[${config.status}] Deploy - ${config.project} - ${config.environment}"
        def body = """
        Project: ${config.project}
        Environment: ${config.environment}
        Version: ${config.version}
        Status: ${config.status}
        Build: ${config.buildUrl}
        """

        steps.emailext(
                subject: subject,
                body: body,
                to: config.recipients
        )
    }

    def sendRollbackNotification(Map config) {
        def subject = "[${config.status}] Rollback - ${config.project} - ${config.environment}"
        def body = """
        Project: ${config.project}
        Environment: ${config.environment}
        Rollback Version: ${config.version}
        Status: ${config.status}
        Build: ${config.buildUrl}
        """

        steps.emailext(
                subject: subject,
                body: body,
                to: config.recipients
        )
    }
}