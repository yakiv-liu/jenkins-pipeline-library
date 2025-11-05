package org.yakiv

class BuildTools implements Serializable {
    def steps
    def env

    BuildTools(steps, env) {
        this.steps = steps
        this.env = env
    }

    def mavenBuild(Map config) {
        steps.withCredentials([
                steps.usernamePassword(
                        credentialsId: 'nexus-credentials',
                        usernameVariable: 'NEXUS_USERNAME',
                        passwordVariable: 'NEXUS_PASSWORD'
                )
        ]) {
            steps.configFileProvider([steps.configFile(fileId: 'global-maven-settings', variable: 'MAVEN_SETTINGS')]) {
                steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
                    steps.sh """
                    export MAVEN_OPTS="-Xmx512m -Xms256m -XX:MaxMetaspaceSize=256m"
                    
                    echo "执行 Maven 部署，版本: ${config.version}"
                    echo "是否为发布版本: ${config.isRelease}"
                    
                    # 使用内存优化的测试配置
                    mvn -s \$MAVEN_SETTINGS clean deploy \
                        '-Drevision=${config.version}' \
                        -Dmaven.test.forkCount=1 \
                        -DargLine="-Xmx256m -XX:MaxPermSize=128m"
                """
                }
            }
        }
    }

    def buildDockerImage(Map config) {
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            steps.sh """
            echo "=== 执行 Docker 镜像构建 ==="
            echo "当前目录: \$(pwd)"
            echo "使用工作目录参数: /app"
            
            # 验证必要的文件存在
            if [ ! -f "Dockerfile" ]; then
                echo "❌ Dockerfile 不存在，跳过构建"
                return
            fi
            
            if [ ! -d "target" ]; then
                echo "❌ target 目录不存在，跳过构建"
                return
            fi
            
            echo "target 目录内容:"
            ls -la target/ | head -10
            
            echo "✅ 开始 Docker 构建..."
            docker build \
            --build-arg PROJECT_NAME=${config.projectName} \
            --build-arg APP_VERSION=${config.version} \
            --build-arg GIT_COMMIT=${config.gitCommit} \
            -t ${env.HARBOR_URL}/${config.projectName}:${config.version} \
            -t ${env.HARBOR_URL}/${config.projectName}:latest \
            .
        """
        }
    }

    def trivyScan(Map config) {
        steps.sh """
            trivy image --format template --template @html.tpl -o trivy-report.html ${config.image}
        """
    }

    def pushDockerImage(Map config) {
        steps.withCredentials([steps.usernamePassword(
                credentialsId: 'harbor-creds',
                passwordVariable: 'HARBOR_PASSWORD',
                usernameVariable: 'HARBOR_USERNAME'
        )]) {
            steps.sh """
                docker login -u ${env.HARBOR_USERNAME} -p ${env.HARBOR_PASSWORD} ${config.harborUrl}
                docker push ${config.harborUrl}/${config.projectName}:${config.version}
                docker push ${config.harborUrl}/${config.projectName}:latest
            """
        }
    }

    def runPRBuildAndTest() {
        // 切换到项目目录
        steps.dir("${env.WORKSPACE}/${env.PROJECT_DIR}") {
            steps.sh '''
            mvn clean compile test -T 1C \
            -Dmaven.test.failure.ignore=false
        '''

            steps.sh 'mvn surefire-report:report jacoco:report'
            steps.sh 'mvn package -DskipTests'
        }
    }
}