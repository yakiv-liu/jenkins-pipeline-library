package org.yakiv

import groovy.sql.Sql
import java.sql.DriverManager
import java.sql.Types

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
     * 记录部署信息到数据库（修复GString类型问题）
     */
    /**
     * 记录部署信息到数据库（使用 UPSERT 操作）
     */
    def recordDeployment(Map config) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "⚠️ 数据库连接不可用，跳过记录部署信息"
                return
            }

            def upsertSql = """
            INSERT INTO deployment_records (
                project_name, environment, version, git_commit, 
                build_url, build_timestamp, jenkins_build_number,
                jenkins_job_name, deploy_user, metadata,
                jenkins_build_url, jenkins_console_url, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (project_name, environment, version) 
            DO UPDATE SET
                git_commit = EXCLUDED.git_commit,
                build_url = EXCLUDED.build_url,
                build_timestamp = EXCLUDED.build_timestamp,
                jenkins_build_number = EXCLUDED.jenkins_build_number,
                jenkins_job_name = EXCLUDED.jenkins_job_name,
                deploy_user = EXCLUDED.deploy_user,
                metadata = EXCLUDED.metadata,
                jenkins_build_url = EXCLUDED.jenkins_build_url,
                jenkins_console_url = EXCLUDED.jenkins_console_url,
                status = EXCLUDED.status,
                update_time = CURRENT_TIMESTAMP
        """

            def consoleUrl = "${config.buildUrl}console"

            // 使用显式参数设置
            def stmt = sql.connection.prepareStatement(upsertSql)

            stmt.setString(1, config.projectName?.toString())
            stmt.setString(2, config.environment?.toString())
            stmt.setString(3, config.version?.toString())
            stmt.setString(4, config.gitCommit?.toString())
            stmt.setString(5, config.buildUrl?.toString())
            stmt.setTimestamp(6, new java.sql.Timestamp(config.buildTimestamp.getTime()))
            stmt.setInt(7, config.jenkinsBuildNumber as Integer)
            stmt.setString(8, config.jenkinsJobName?.toString())
            stmt.setString(9, config.deployUser?.toString())
            stmt.setObject(10, groovy.json.JsonOutput.toJson(config.metadata ?: [:]), java.sql.Types.OTHER)
            stmt.setString(11, config.buildUrl?.toString())
            stmt.setString(12, consoleUrl?.toString())
            stmt.setString(13, (config.status ?: 'IN_PROGRESS')?.toString())

            def result = stmt.executeUpdate()
            stmt.close()

            if (result > 0) {
                steps.echo "✅ 部署记录已保存或更新，影响行数: ${result}"
            }

        } catch (Exception e) {
            steps.echo "❌ 保存部署记录失败: ${e.message}"
            steps.echo "详细堆栈: ${e.stackTrace.take(5).join('\n')}" // 只显示前5行堆栈跟踪
        } finally {
            sql?.close()
        }
    }

    /**
     * 更新部署状态和摘要信息（修复类型问题）
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

            // 使用带有显式类型的方法
            def stmt = sql.connection.prepareStatement(updateSql)

            stmt.setString(1, config.status?.toString())
            stmt.setString(2, config.errorSummary?.toString())
            stmt.setObject(3, config.deploymentDuration) // 可能是整数或浮点数
            stmt.setString(4, config.projectName?.toString())
            stmt.setString(5, config.environment?.toString())
            stmt.setString(6, config.version?.toString())

            int affectedRows = stmt.executeUpdate()
            stmt.close()

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

            // 使用显式参数设置
            def stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setString(2, environment?.toString())
            stmt.setInt(3, limit)

            def rs = stmt.executeQuery()
            def results = []

            while (rs.next()) {
                results.add([
                        id: rs.getLong("id"),
                        project_name: rs.getString("project_name"),
                        environment: rs.getString("environment"),
                        version: rs.getString("version"),
                        status: rs.getString("status"),
                        deploy_time: rs.getTimestamp("deploy_time"),
                        jenkins_build_url: rs.getString("jenkins_build_url"),
                        jenkins_console_url: rs.getString("jenkins_console_url"),
                        error_summary: rs.getString("error_summary"),
                        deployment_duration: rs.getObject("deployment_duration"),
                        git_commit: rs.getString("git_commit")
                ])
            }

            rs.close()
            stmt.close()

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

    // 其他方法也需要类似的修复...
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

            // 使用显式参数设置
            def stmt = sql.connection.prepareStatement(insertSql)

            stmt.setString(1, config.projectName?.toString())
            stmt.setString(2, config.environment?.toString())
            stmt.setString(3, config.rollbackVersion?.toString())
            stmt.setString(4, config.currentVersion?.toString())
            stmt.setString(5, config.buildUrl?.toString())
            stmt.setInt(6, config.jenkinsBuildNumber as Integer)
            stmt.setString(7, config.jenkinsJobName?.toString())
            stmt.setString(8, config.rollbackUser?.toString())
            stmt.setString(9, config.reason?.toString())
            stmt.setString(10, (config.status ?: 'SUCCESS')?.toString())

            def result = stmt.executeUpdate()
            stmt.close()

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

    // 其他方法也需要类似的修复...
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

            def stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setString(2, environment?.toString())
            stmt.setInt(3, limit)

            def rs = stmt.executeQuery()
            def results = []

            while (rs.next()) {
                results.add([
                        version: rs.getString("version"),
                        deploy_time: rs.getTimestamp("deploy_time"),
                        git_commit: rs.getString("git_commit"),
                        build_url: rs.getString("build_url")
                ])
            }

            rs.close()
            stmt.close()

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

    // 其他方法也需要类似的修复...
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

            def stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setString(2, environment?.toString())
            stmt.setString(3, version?.toString())

            def rs = stmt.executeQuery()
            def exists = false

            if (rs.next()) {
                exists = rs.getLong("count") > 0
            }

            rs.close()
            stmt.close()

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

            def stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setString(2, environment?.toString())

            def rs = stmt.executeQuery()
            def version = null

            if (rs.next()) {
                version = rs.getString("version")
            }

            rs.close()
            stmt.close()

            return version

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
     * 获取上一个成功的部署版本（用于自动回滚）
     */
    def getPreviousSuccessfulVersion(String projectName, String environment, String currentVersion) {
        def sql = null
        def stmt = null
        def resultSet = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "⚠️ 数据库连接不可用，跳过查询上一个成功版本"
                return null
            }

            def query = """
                SELECT version, deploy_time, git_commit, build_url
                FROM deployment_records
                WHERE project_name = ? AND environment = ? AND status in ('SUCCESS', 'ROLLBACK_SUCCESS') AND version != ?
                ORDER BY deploy_time DESC
                LIMIT 1
            """

            // 使用 PreparedStatement
            stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setString(2, environment?.toString())
            stmt.setString(3, currentVersion?.toString())

            resultSet = stmt.executeQuery()

            if (resultSet.next()) {
                def result = [
                        version: resultSet.getString("version"),
                        deploy_time: resultSet.getTimestamp("deploy_time"),
                        git_commit: resultSet.getString("git_commit"),
                        build_url: resultSet.getString("build_url")
                ]
                steps.echo "✅ 找到上一个成功版本: ${result.version}"
                return result
            } else {
                steps.echo "❌ 没有找到上一个成功版本"
                return null
            }

        } catch (Exception e) {
            steps.echo "❌ 获取上一个成功版本失败: ${e.message}"
            steps.echo "详细堆栈: ${e.stackTrace.take(5).join('\n')}"
            return null
        } finally {
            // 确保资源被正确关闭
            try {
                resultSet?.close()
            } catch (Exception e) {
                steps.echo "关闭结果集时出错: ${e.message}"
            }
            try {
                stmt?.close()
            } catch (Exception e) {
                steps.echo "关闭语句时出错: ${e.message}"
            }
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "关闭数据库连接时出错: ${e.message}"
            }
        }
    }

    /**
     * 记录构建信息
     */
    def recordBuild(Map config) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过记录构建信息"
                return
            }

            def insertSql = """
            INSERT INTO build_records (
                project_name, version, git_commit, git_branch,
                build_timestamp, build_status, docker_image,
                jenkins_build_url, jenkins_build_number, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """

            def stmt = sql.connection.prepareStatement(insertSql)
            stmt.setString(1, config.projectName?.toString())
            stmt.setString(2, config.version?.toString())
            stmt.setString(3, config.gitCommit?.toString())
            stmt.setString(4, config.gitBranch?.toString())
            stmt.setTimestamp(5, new java.sql.Timestamp(config.buildTimestamp.getTime()))
            stmt.setString(6, config.buildStatus?.toString())
            stmt.setString(7, config.dockerImage?.toString())
            stmt.setString(8, config.jenkinsBuildUrl?.toString())
            stmt.setInt(9, config.jenkinsBuildNumber as Integer)
            stmt.setObject(10, groovy.json.JsonOutput.toJson(config.metadata ?: [:]), java.sql.Types.OTHER)

            def result = stmt.executeUpdate()
            stmt.close()

            steps.echo "✅ 构建记录已保存: ${config.projectName} ${config.version}"

        } catch (Exception e) {
            steps.echo "❌ 保存构建记录失败: ${e.message}"
        } finally {
            try {
                sql?.close()
            } catch (Exception e) {
                steps.echo "⚠️ 关闭数据库连接时出现警告: ${e.message}"
            }
        }
    }

/**
 * 获取项目的最新构建版本
 */
    def getRecentBuildVersions(String projectName, int limit = 10) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过查询构建版本"
                return []
            }

            def query = """
            SELECT version, build_timestamp, git_commit, docker_image, build_status
            FROM build_records
            WHERE project_name = ? AND build_status = 'SUCCESS'
            ORDER BY build_timestamp DESC
            LIMIT ?
        """

            def stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setInt(2, limit)

            def rs = stmt.executeQuery()
            def results = []

            while (rs.next()) {
                results.add([
                        version: rs.getString("version"),
                        build_timestamp: rs.getTimestamp("build_timestamp"),
                        git_commit: rs.getString("git_commit"),
                        docker_image: rs.getString("docker_image"),
                        build_status: rs.getString("build_status")
                ])
            }

            rs.close()
            stmt.close()

            steps.echo "✅ 从数据库获取到 ${results.size()} 个构建版本"
            return results

        } catch (Exception e) {
            steps.echo "❌ 获取构建版本失败: ${e.message}"
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
 * 验证构建版本是否存在
 */
    def validateBuildVersion(String projectName, String version) {
        def sql = null
        try {
            sql = getConnection()
            if (!sql) {
                steps.echo "❌ 无法获取数据库连接，跳过验证构建版本"
                return false
            }

            def query = """
            SELECT COUNT(*) as count
            FROM build_records
            WHERE project_name = ? AND version = ? AND build_status = 'SUCCESS'
        """

            def stmt = sql.connection.prepareStatement(query)
            stmt.setString(1, projectName?.toString())
            stmt.setString(2, version?.toString())

            def rs = stmt.executeQuery()
            def exists = false

            if (rs.next()) {
                exists = rs.getLong("count") > 0
            }

            rs.close()
            stmt.close()

            if (exists) {
                steps.echo "✅ 构建版本验证通过: ${version}"
            } else {
                steps.echo "❌ 构建版本不存在: ${version}"
            }

            return exists

        } catch (Exception e) {
            steps.echo "❌ 验证构建版本失败: ${e.message}"
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