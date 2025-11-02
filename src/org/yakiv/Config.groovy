package org.yakiv

class Config implements Serializable {
    // 基础设施配置 - 集中管理
    static final NEXUS_URL = 'http://192.168.233.8:8081'
    static final HARBOR_URL = '192.168.233.9:8083'
    static final SONAR_URL = 'http://192.168.233.8:9000'
    static final TRIVY_URL = 'http://192.168.233.9:8084'
    static final BACKUP_DIR = '/opt/backups'
    
    // 默认配置
    static Map getDefaultConfig() {
        return [
            nexusUrl: NEXUS_URL,
            harborUrl: HARBOR_URL,
            sonarUrl: SONAR_URL,
            trivyUrl: TRIVY_URL,
            backupDir: BACKUP_DIR,
            defaultEmail: '251934304@qq.com',
            environments: ['staging', 'pre-prod', 'prod'],
            agentLabel: 'any',
            // 参数默认值 - 更新为demo-helloworld
            projectName: 'demo-helloworld',
            deployEnv: 'staging',
            rollback: false,
            rollbackVersion: '',
            isRelease: false
        ]
    }
    
    // 合并配置
    static Map mergeConfig(Map userConfig) {
        return getDefaultConfig() + userConfig
    }
}
