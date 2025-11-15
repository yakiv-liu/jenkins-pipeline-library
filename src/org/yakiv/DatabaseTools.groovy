package org.yakiv

import groovy.sql.Sql
import java.sql.DriverManager

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
     * 获取数据库连接（双重保险方案）
     */
    def getConnection() {
        try {
            def dbUrl = configLoader.getDatabaseUrl()
            def dbUser = configLoader.getDatabaseUsername()
            def dbPassword = configLoader.getDatabasePassword()
            def dbDriver = configLoader.getDatabaseDriver()

            steps.echo "连接数据库: ${dbUrl.replace(dbPassword, '***')}"
            steps.echo "使用驱动: ${dbDriver}"

            // 确保驱动类已加载
            try {
                Class.forName(dbDriver)
                steps.echo "✅ PostgreSQL 驱动类加载成功"
            } catch (ClassNotFoundException e) {
                steps.echo "❌ 无法加载 PostgreSQL 驱动类: ${e.message}"
                steps.echo "💡 请确保 PostgreSQL JDBC 驱动在 Jenkins 类路径中"
                return null
            }

            // 双重保险连接方案
            def connection = null

            // 方案1: 首先尝试 DriverManager（标准方式）
            try {
                connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
                steps.echo "✅ 通过 DriverManager 连接成功"
            } catch (Exception e) {
                steps.echo "⚠️ DriverManager 连接失败，使用备选方案: ${e.message}"

                // 方案2: 直接使用驱动实例（备选方案）
                try {
                    def driver = Class.forName(dbDriver).newInstance()
                    def props = new Properties()
                    props.setProperty("user", dbUser)
                    props.setProperty("password", dbPassword)
                    connection = driver.connect(dbUrl, props)
                    steps.echo "✅ 通过驱动实例连接成功"
                } catch (Exception e2) {
                    steps.echo "❌ 所有连接方案都失败: ${e2.message}"
                    return null
                }
            }

            return new Sql(connection)

        } catch (Exception e) {
            steps.echo "❌ 数据库连接失败: ${e.message}"
            return null
        }
    }

    /**
     * 记录部署信息到数据库（简化版）
     */
    def recordDeployment(Map config) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过记录部署信息"
                return
            }

            def insertSql = """
            INSERT INTO deployment_records (
                project_name, environment, version, git_commit, 
                build_url, build_timestamp, jenkins_build_number,
                jenkins_job_name, deploy_user, metadata,
                jenkins_build_url, jenkins_console_url, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
        """

            // 构建 Jenkins 控制台 URL
            def consoleUrl = "${config.buildUrl}console"

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
                    groovy.json.JsonOutput.toJson(config.metadata ?: [:]),
                    config.buildUrl,  // Jenkins 构建 URL
                    consoleUrl,       // Jenkins 控制台 URL
                    config.status ?: 'IN_PROGRESS'
            ])

            steps.echo "✅ 部署元数据已保存到数据库"

        } catch (Exception e) {
            steps.echo "❌ 保存部署记录到数据库失败: ${e.message}"
            // 记录详细错误信息以便调试
            steps.echo "详细错误: ${e.getStackTrace().find { it.contains('DatabaseTools') }}"
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 更新部署状态和摘要信息
     */
    def updateDeploymentStatus(Map config) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过更新部署状态"
                return
            }

            def updateSql = """
            UPDATE deployment_records 
            SET status = ?, error_summary = ?, deployment_duration = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE project_name = ? AND environment = ? AND version = ?
        """

            int affectedRows = sql.executeUpdate(updateSql, [
                    config.status,
                    config.errorSummary,
                    config.deploymentDuration,
                    config.projectName,
                    config.environment,
                    config.version
            ])

            if (affectedRows > 0) {
                steps.echo "✅ 部署状态更新完成: ${config.status} (影响行数: ${affectedRows})"
            } else {
                steps.echo "⚠️ 未找到匹配的部署记录来更新状态"
            }

        } catch (Exception e) {
            steps.echo "❌ 更新部署状态失败: ${e.message}"
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 获取部署记录列表（用于查询）
     */
    def getDeploymentRecords(String projectName, String environment, int limit = 20) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过查询部署记录"
                return []
            }

            def query = """
            SELECT 
                id, project_name, environment, version, status,
                deploy_time, jenkins_build_url, jenkins_console_url,
                error_summary, deployment_duration, git_commit
            FROM deployment_records
            WHERE project_name = ? AND environment = ?
            ORDER BY deploy_time DESC
            LIMIT ?
        """

            def results = sql.rows(query, [projectName, environment, limit])
            steps.echo "✅ 获取到 ${results.size()} 条部署记录"
            return results

        } catch (Exception e) {
            steps.echo "❌ 获取部署记录失败: ${e.message}"
            return []
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 记录回滚信息到数据库
     */
    def recordRollback(Map config) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过记录回滚信息"
                return
            }

            def insertSql = """
                INSERT INTO rollback_records (
                    project_name, environment, rollback_version, current_version,
                    build_url, jenkins_build_number, jenkins_job_name,
                    rollback_user, reason, status, rollback_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
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
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 获取可回滚的版本列表
     */
    def getRollbackVersions(String projectName, String environment, int limit = 10) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过获取回滚版本"
                return []
            }

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
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 验证回滚版本是否存在
     */
    def validateRollbackVersion(String projectName, String environment, String version) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过验证回滚版本"
                return false
            }

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
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 获取最新的部署版本
     */
    def getLatestVersion(String projectName, String environment) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过获取最新版本"
                return null
            }

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
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 测试数据库连接
     */
    def testConnection() {
        def sql = null
        try {
            sql = getConnection()
            if (sql == null) {
                steps.echo "❌ 数据库连接不可用"
                return false
            }

            def result = sql.firstRow("SELECT 1 as test")
            def success = result?.test == 1

            if (success) {
                steps.echo "✅ 数据库连接测试成功"
            } else {
                steps.echo "❌ 数据库连接测试失败：查询返回异常结果"
            }
            return success

        } catch (Exception e) {
            steps.echo "❌ 数据库连接测试失败: ${e.message}"
            return false
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

    /**
     * 测试数据库详细连接信息
     */
    def testDetailedConnection() {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                return false
            }

            // 执行更详细的测试查询
            def dbInfo = sql.firstRow("""
                SELECT 
                    current_database() as database,
                    current_user as user,
                    version() as version
            """)

            steps.echo "✅ 数据库连接详细信息:"
            steps.echo "   - 数据库: ${dbInfo.database}"
            steps.echo "   - 用户: ${dbInfo.user}"
            steps.echo "   - PostgreSQL 版本: ${dbInfo.version.split(',')[0]}"

            return true

        } catch (Exception e) {
            steps.echo "❌ 详细连接测试失败: ${e.message}"
            return false
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }
}