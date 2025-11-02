pipeline {
    agent any
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        
        stage('Lint') {
            steps {
                script {
                    // Groovy语法检查
                    sh '''
                        find . -name "*.groovy" -type f | xargs groovylint || true
                    '''
                }
            }
        }
        
        stage('Test') {
            steps {
                script {
                    // 运行共享库的单元测试
                    sh '''
                        echo "Running shared library tests..."
                        # 这里可以添加具体的测试命令
                    '''
                }
            }
        }
        
        stage('Build Documentation') {
            steps {
                script {
                    // 生成文档
                    sh '''
                        echo "Generating documentation..."
                        # 生成使用文档
                    '''
                }
            }
        }
    }
    
    post {
        always {
            cleanWs()
        }
        success {
            echo "Shared library CI completed successfully"
        }
        failure {
            echo "Shared library CI failed"
        }
    }
}
