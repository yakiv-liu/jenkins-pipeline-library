package org.yakiv

import groovy.sql.Sql

class DatabaseTools implements Serializable {
    def steps
    def env
    def configLoader

    DatabaseTools(steps, env, configLoader) {
        this.steps = steps
        this.env = env
        this.configLoader = configLoader
    }

    /**
     * 获取数据库连接
     */
    def getConnection() {
        try {
            def dbUrl = configLoader.getDatabaseUrl()
            def dbUser = configLoader.getDatabaseUsername()
            def dbPassword = configLoader.getDatabasePassword()
            def dbDriver = configLoader.getDatabaseDriver()

            steps.echo "连接数据库: ${dbUrl.replace(dbPassword, '***')}"

            return Sql.newInstance(
                    dbUrl,
                    dbUser,
                    dbPassword,
                    dbDriver
            )
        } catch (Exception e) {
            steps.echo "❌ 数据库连接失败: ${e.message}"
            throw e
        }
    }

    /**
     * 初始化数据库表
     */
//    def initializeDatabase() {
//        def sql = null
//        try {
//            sql = getConnection()
//
//            // 创建部署记录表
//            def createDeploymentTable = """
//                CREATE TABLE IF NOT EXISTS deployment_records (
//                    id SERIAL PRIMARY KEY,
//                    project_name VARCHAR(100) NOT NULL,
//                    environment VARCHAR(50) NOT NULL,
//                    version VARCHAR(100) NOT NULL,
//                    git_commit VARCHAR(50),
//                    build_url VARCHAR(500),
//                    build_timestamp TIMESTAMP NOT NULL,
//                    deploy_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
//                    status VARCHAR(20) DEFAULT 'SUCCESS',
//                    jenkins_build_number INTEGER,
//                    jenkins_job_name VARCHAR(200),
//                    deploy_user VARCHAR(100),
//                    metadata JSONB
//                )
//            """
//
//            // 创建回滚记录表
//            def createRollbackTable = """
//                CREATE TABLE IF NOT EXISTS rollback_records (
//                    id SERIAL PRIMARY KEY,
//                    project_name VARCHAR(100) NOT NULL,
//                    environment VARCHAR(50) NOT NULL,
//                    rollback_version VARCHAR(100) NOT NULL,
//                    current_version VARCHAR(100) NOT NULL,
//                    rollback_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
//                    build_url VARCHAR(500),
//                    jenkins_build_number INTEGER,
//                    jenkins_job_name VARCHAR(200),
//                    rollback_user VARCHAR(100),
//                    reason TEXT,
//                    status VARCHAR(20) DEFAULT 'SUCCESS'
//                )
//            """
//
//            sql.execute(createDeploymentTable)
//            sql.execute(createRollbackTable)
//
//            // 创建索引
//            try {
//                sql.execute("CREATE INDEX IF NOT EXISTS idx_deployment_project_env ON deployment_records(project_name, environment)")
//                sql.execute("CREATE INDEX IF NOT EXISTS idx_deployment_version ON deployment_records(version)")
//                sql.execute("CREATE INDEX IF NOT EXISTS idx_deployment_time ON deployment_records(deploy_time)")
//                sql.execute("CREATE INDEX IF NOT EXISTS idx_rollback_project_env ON rollback_records(project_name, environment)")
//                sql.execute("CREATE INDEX IF NOT EXISTS idx_rollback_time ON rollback_records(rollback_time)")
//                sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_deployment_unique ON deployment_records(project_name, environment, version)")
//            } catch (Exception e) {
//                steps.echo "⚠️ 创建索引失败（可能已存在）: ${e.message}"
//            }
//
//            steps.echo "✅ 数据库表初始化完成"
//
//        } catch (Exception e) {
//            steps.echo "❌ 数据库初始化失败: ${e.message}"
//            throw e
//        } finally {
//            sql?.close()
//        }
//    }

    /**
     * 记录部署信息到数据库
     */
    def recordDeployment(Map config) {
        def sql = null
        try {
            sql = getConnection()

            def insertSql = """
                INSERT INTO deployment_records (
                    project_name, environment, version, git_commit, 
                    build_url, build_timestamp, jenkins_build_number,
                    jenkins_job_name, deploy_user, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """

            sql.executeInsert(insertSql, [
                    config.projectName,
                    config.environment,
                    config.version,
                    config.gitCommit,
                    config.buildUrl,
                    new java.sql.Timestamp(config.buildTimestamp.getTime()),
                    config.jenkinsBuildNumber,
                    config.jenkinsJobName,
                    config.deployUser,
                    groovy.json.JsonOutput.toJson(config.metadata ?: [:])
            ])

            steps.echo "✅ 部署记录已保存到数据库: ${config.projectName} ${config.environment} ${config.version}"

        } catch (Exception e) {
            steps.echo "❌ 保存部署记录到数据库失败: ${e.message}"
            // 不抛出异常，避免影响部署流程
        } finally {
            sql?.close()
        }
    }

    /**
     * 记录回滚信息到数据库
     */
    def recordRollback(Map config) {
        def sql = null
        try {
            sql = getConnection()

            def insertSql = """
                INSERT INTO rollback_records (
                    project_name, environment, rollback_version, current_version,
                    build_url, jenkins_build_number, jenkins_job_name,
                    rollback_user, reason, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """

            sql.executeInsert(insertSql, [
                    config.projectName,
                    config.environment,
                    config.rollbackVersion,
                    config.currentVersion,
                    config.buildUrl,
                    config.jenkinsBuildNumber,
                    config.jenkinsJobName,
                    config.rollbackUser,
                    config.reason,
                    config.status
            ])

            steps.echo "✅ 回滚记录已保存到数据库: ${config.projectName} ${config.environment} ${config.rollbackVersion}"

        } catch (Exception e) {
            steps.echo "❌ 保存回滚记录到数据库失败: ${e.message}"
            // 不抛出异常，避免影响回滚流程
        } finally {
            sql?.close()
        }
    }

    /**
     * 获取可回滚的版本列表
     */
    def getRollbackVersions(String projectName, String environment, int limit = 10) {
        def sql = null
        try {
            sql = getConnection()

            def query = """
                SELECT version, deploy_time, git_commit, build_url
                FROM deployment_records
                WHERE project_name = ? AND environment = ? AND status = 'SUCCESS'
                ORDER BY deploy_time DESC
                LIMIT ?
            """

            def results = sql.rows(query, [projectName, environment, limit])
            steps.echo "✅ 从数据库获取到 ${results.size()} 个可回滚版本"
            return results

        } catch (Exception e) {
            steps.echo "❌ 从数据库获取回滚版本失败: ${e.message}"
            return []
        } finally {
            sql?.close()
        }
    }

    /**
     * 验证回滚版本是否存在
     */
    def validateRollbackVersion(String projectName, String environment, String version) {
        def sql = null
        try {
            sql = getConnection()

            def query = """
                SELECT COUNT(*) as count
                FROM deployment_records
                WHERE project_name = ? AND environment = ? AND version = ? AND status = 'SUCCESS'
            """

            def result = sql.firstRow(query, [projectName, environment, version])
            def exists = result.count > 0

            if (exists) {
                steps.echo "✅ 回滚版本验证通过: ${version}"
            } else {
                steps.echo "❌ 回滚版本不存在: ${version}"
            }

            return exists

        } catch (Exception e) {
            steps.echo "❌ 验证回滚版本失败: ${e.message}"
            return false
        } finally {
            sql?.close()
        }
    }

    /**
     * 获取最新的部署版本
     */
    def getLatestVersion(String projectName, String environment) {
        def sql = null
        try {
            sql = getConnection()

            def query = """
                SELECT version
                FROM deployment_records
                WHERE project_name = ? AND environment = ? AND status = 'SUCCESS'
                ORDER BY deploy_time DESC
                LIMIT 1
            """

            def result = sql.firstRow(query, [projectName, environment])
            return result?.version

        } catch (Exception e) {
            steps.echo "❌ 获取最新版本失败: ${e.message}"
            return null
        } finally {
            sql?.close()
        }
    }

    /**
     * 测试数据库连接
     */
    def testConnection() {
        def sql = null
        try {
            sql = getConnection()
            def result = sql.firstRow("SELECT 1 as test")
            steps.echo "✅ 数据库连接测试成功"
            return true
        } catch (Exception e) {
            steps.echo "❌ 数据库连接测试失败: ${e.message}"
            return false
        } finally {
            sql?.close()
        }
    }
}