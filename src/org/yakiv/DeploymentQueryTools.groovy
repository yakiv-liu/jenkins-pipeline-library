package org.yakiv

class DeploymentQueryTools implements Serializable {
    def steps
    def env
    def configLoader
    def dbTools

    DeploymentQueryTools(steps, env, configLoader) {
        this.steps = steps
        this.env = env
        this.configLoader = configLoader
        this.dbTools = new DatabaseTools(steps, env, configLoader)
    }

    /**
     * 显示部署历史
     */
    def showDeploymentHistory(String projectName, String environment, int limit = 10) {
        steps.echo "📊 部署历史 - ${projectName} / ${environment}"

        def records = dbTools.getDeploymentRecords(projectName, environment, limit)

        if (records.isEmpty()) {
            steps.echo "暂无部署记录"
            return
        }

        steps.echo "=" * 80
        records.each { record ->
            def statusIcon = getStatusIcon(record.status)
            steps.echo "${statusIcon} ${record.version} - ${record.status} - ${record.deploy_time}"
            steps.echo "   时长: ${record.deployment_duration ?: 'N/A'}秒 | Commit: ${record.git_commit?.substring(0, 8) ?: 'N/A'}"

            if (record.error_summary) {
                steps.echo "   错误: ${record.error_summary}"
            }

            steps.echo "   日志: ${record.jenkins_console_url}"
            steps.echo "-" * 40
        }
    }

    /**
     * 获取部署详情
     */
    def showDeploymentDetails(String projectName, String environment, String version) {
        steps.echo "🔍 部署详情 - ${projectName} / ${environment} / ${version}"

        def records = dbTools.getDeploymentRecords(projectName, environment, 50)
        def targetRecord = records.find { it.version == version }

        if (!targetRecord) {
            steps.echo "未找到指定的部署记录"
            return
        }

        steps.echo "=" * 80
        steps.echo "项目: ${targetRecord.project_name}"
        steps.echo "环境: ${targetRecord.environment}"
        steps.echo "版本: ${targetRecord.version}"
        steps.echo "状态: ${getStatusIcon(targetRecord.status)} ${targetRecord.status}"
        steps.echo "时间: ${targetRecord.deploy_time}"
        steps.echo "时长: ${targetRecord.deployment_duration ?: 'N/A'}秒"
        steps.echo "Git Commit: ${targetRecord.git_commit ?: 'N/A'}"

        if (targetRecord.error_summary) {
            steps.echo "错误摘要: ${targetRecord.error_summary}"
        }

        steps.echo "Jenkins 构建: ${targetRecord.jenkins_build_url}"
        steps.echo "详细日志: ${targetRecord.jenkins_console_url}"
        steps.echo "=" * 80

        steps.echo "💡 提示: 查看完整日志请访问上面的 Jenkins 链接"
    }

    private def getStatusIcon(String status) {
        def icons = [
                'SUCCESS': '✅',
                'FAILED': '❌',
                'IN_PROGRESS': '🔄',
                'ROLLBACK': '↩️'
        ]
        return icons[status] ?: '📝'
    }
}