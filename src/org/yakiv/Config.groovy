package org.yakiv

class Config implements Serializable {
    def steps

    Config(steps) {
        this.steps = steps
    }

    // 加载配置 - 失败时直接报错
    Map loadConfig() {
        try {
            // 使用 libraryResource 读取文件内容
            def yamlText = steps.libraryResource 'org/yakiv/default-config.yaml'

            // 使用 readYaml 步骤解析 YAML
            def config = steps.readYaml text: yamlText
            steps.echo "成功加载 YAML 配置文件"
            return config
        } catch (Exception e) {
            // 直接报错，不提供降级方案
            error "无法加载 YAML 配置文件: ${e.message}"
        }
    }

    // 获取配置值
    def getConfigValue(Map config, String path, defaultValue = null) {
        def keys = path.split('\\.')
        def current = config

        for (key in keys) {
            if (current instanceof Map && current[key] != null) {
                current = current[key]
            } else {
                return defaultValue
            }
        }
        return current
    }

    // 默认配置
    Map getDefaultConfig() {
        def config = loadConfig()

        return [
                // 基础设施
                nexusUrl: getConfigValue(config, 'infrastructure.nexus.url'),
                harborUrl: getConfigValue(config, 'infrastructure.harbor.url'),
                sonarUrl: getConfigValue(config, 'infrastructure.sonar.url'),
                trivyUrl: getConfigValue(config, 'infrastructure.trivy.url'),
                backupDir: getConfigValue(config, 'infrastructure.backup.dir'),

                // 默认项目配置
                defaultEmail: getConfigValue(config, 'defaults.email'),
                environments: getConfigValue(config, 'defaults.environments'),
                agentLabel: getConfigValue(config, 'defaults.agent'),
                projectName: getConfigValue(config, 'defaults.project'),
                appPort: getConfigValue(config, 'defaults.appPort', 8080),

                // 运行时参数默认值
                deployEnv: 'staging',
                rollback: false,
                rollbackVersion: '',
                isRelease: false
        ]
    }

    // 合并用户配置
    Map mergeConfig(Map userConfig) {
        def defaultConfig = getDefaultConfig()
        def mergedConfig = defaultConfig + userConfig

        // 特殊处理环境主机配置的合并
        if (userConfig.environmentHosts) {
            def config = loadConfig()
            def defaultEnvironments = getConfigValue(config, 'environments', [:])
            def mergedEnvHosts = [:] + defaultEnvironments

            userConfig.environmentHosts.each { env, hostConfig ->
                if (mergedEnvHosts[env]) {
                    mergedEnvHosts[env] = mergedEnvHosts[env] + hostConfig
                } else {
                    mergedEnvHosts[env] = hostConfig
                }
            }
            mergedConfig.environmentHosts = mergedEnvHosts
        }

        return mergedConfig
    }

    // 获取环境主机映射
    Map getEnvironmentHosts() {
        def config = loadConfig()
        return getConfigValue(config, 'environments', [:])
    }

    // 获取特定环境的主机
    String getEnvironmentHost(Map userConfig, String environment) {
        // 优先使用用户配置的环境主机
        if (userConfig.environmentHosts &&
                userConfig.environmentHosts[environment] &&
                userConfig.environmentHosts[environment].host) {
            return userConfig.environmentHosts[environment].host
        }

        // 使用默认配置的环境主机
        def config = loadConfig()
        def defaultHost = getConfigValue(config, "environments.${environment}.host")
        if (defaultHost) {
            return defaultHost
        }

        error "无法找到环境 ${environment} 的主机配置"
    }

    // 获取应用端口
    Integer getAppPort(Map userConfig) {
        def config = loadConfig()
        return userConfig.appPort ?: getConfigValue(config, 'defaults.appPort', 8080)
    }

    // 便捷方法 - 基础设施URL
    String getNexusUrl() {
        def config = loadConfig()
        return getConfigValue(config, 'infrastructure.nexus.url')
    }

    String getHarborUrl() {
        def config = loadConfig()
        return getConfigValue(config, 'infrastructure.harbor.url')
    }

    String getSonarUrl() {
        def config = loadConfig()
        return getConfigValue(config, 'infrastructure.sonar.url')
    }

    String getTrivyUrl() {
        def config = loadConfig()
        return getConfigValue(config, 'infrastructure.trivy.url')
    }

    String getBackupDir() {
        def config = loadConfig()
        return getConfigValue(config, 'infrastructure.backup.dir')
    }
}