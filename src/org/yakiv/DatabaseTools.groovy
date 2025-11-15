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
     * 获取数据库连接（使用已知驱动路径）
     */
    def getConnection() {
        try {
            def dbUrl = configLoader.getDatabaseUrl()
            def dbUser = configLoader.getDatabaseUsername()
            def dbPassword = configLoader.getDatabasePassword()
            def dbDriver = configLoader.getDatabaseDriver()

            steps.echo "连接数据库: ${dbUrl.replace(dbPassword, '***')}"
            steps.echo "使用驱动: ${dbDriver}"

            // 使用已知路径加载驱动
            def driverInstance = loadDriverFromKnownPath(dbDriver)
            if (!driverInstance) {
                steps.echo "❌ 无法加载数据库驱动"
                return null
            }

            // 建立连接
            def connection = establishConnectionWithDriver(driverInstance, dbUrl, dbUser, dbPassword)
            if (!connection) {
                steps.echo "❌ 无法建立数据库连接"
                return null
            }

            steps.echo "✅ 数据库连接建立成功"
            return new Sql(connection)

        } catch (Exception e) {
            steps.echo "❌ 数据库连接失败: ${e.message}"
            return null
        }
    }

    /**
     * 从已知路径加载驱动
     */
    private def loadDriverFromKnownPath(String driverClassName) {
        try {
            // 首先尝试直接加载（如果已经加载过）
            steps.echo "尝试直接加载驱动: ${driverClassName}"
            return Class.forName(driverClassName).newInstance()
        } catch (ClassNotFoundException e) {
            steps.echo "驱动类未找到，从已知路径加载..."

            // 使用已知路径加载驱动
            def driverPath = "/tmp/jenkins-libs/postgresql.jar"

            // 检查文件是否存在
            def fileExists = steps.sh(
                    script: "if [ -f \"${driverPath}\" ]; then echo \"EXISTS\"; else echo \"NOT_EXISTS\"; fi",
                    returnStdout: true
            ).trim() == "EXISTS"

            if (!fileExists) {
                steps.echo "❌ 驱动文件不存在: ${driverPath}"
                steps.echo "💡 请确保已手动下载驱动到该路径"
                return null
            }

            try {
                // 使用URLClassLoader动态加载
                def driverFile = new File(driverPath)
                def urlClassLoader = new URLClassLoader(
                        [driverFile.toURI().toURL()] as URL[],
                        this.class.classLoader
                )

                steps.echo "✅ 从已知路径加载驱动成功: ${driverPath}"
                return urlClassLoader.loadClass(driverClassName).newInstance()

            } catch (Exception ex) {
                steps.echo "❌ 从已知路径加载驱动失败: ${ex.message}"
                return null
            }
        } catch (Exception e) {
            steps.echo "❌ 驱动加载失败: ${e.message}"
            return null
        }
    }

    /**
     * 使用驱动实例建立连接
     */
    private def establishConnectionWithDriver(driverInstance, String url, String user, String password) {
        try {
            steps.echo "通过驱动实例建立连接..."
            def props = new Properties()
            props.setProperty("user", user)
            props.setProperty("password", password)

            def connection = driverInstance.connect(url, props)
            if (connection != null) {
                steps.echo "✅ 通过驱动实例连接成功"
                return connection
            }
        } catch (Exception e) {
            steps.echo "❌ 驱动实例连接失败: ${e.message}"
        }

        // 备选方案：尝试注册到DriverManager
        try {
            steps.echo "尝试注册驱动到DriverManager..."
            DriverManager.registerDriver(driverInstance)
            def connection = DriverManager.getConnection(url, user, password)
            steps.echo "✅ 通过DriverManager连接成功"
            return connection
        } catch (Exception e) {
            steps.echo "❌ DriverManager连接失败: ${e.message}"
        }

        return null
    }

    /**
     * 记录部署信息到数据库
     */
    def recordDeployment(Map config) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "⚠️ 数据库连接不可用，跳过记录部署信息"
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
                    config.buildUrl,
                    consoleUrl,
                    config.status ?: 'IN_PROGRESS'
            ])

            steps.echo "✅ 部署元数据已保存到数据库"

        } catch (Exception e) {
            steps.echo "❌ 保存部署记录失败: ${e.message}"
        } finally {
            sql?.close()
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
     * 测试数据库连接
     */
    def testConnection() {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 数据库连接不可用"
                return false
            }

            // 简单的测试查询
            def result = sql.firstRow("SELECT 1 as test_value")
            def success = result?.test_value == 1

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
     * 获取部署记录列表
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

    // 其他方法保持不变...
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
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

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
}