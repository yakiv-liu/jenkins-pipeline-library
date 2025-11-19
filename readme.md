# Jenkins Pipeline 项目文档

## 项目概述

本项目包含4个Jenkins流水线，用于自动化构建、测试、安全扫描和部署Java应用。所有流水线基于共享库 [jenkins-pipeline-library](https://github.com/yakiv-liu/jenkins-pipeline-library.git) 实现。

## 流水线列表

### 1. demo-helloworld-multibranch-PR
**类型**: 多分支流水线  
**触发条件**: 当有master或main分支的PR时通过webhook自动触发  
**Jenkinsfile**: [JenkinsfilePR.groovy](https://github.com/yakiv-liu/demo-helloworld/blob/main/jenkinsfiles/JenkinsfilePR.groovy)

#### Stages:
- **PR Info**: 显示PR信息，验证目标分支
- **Run PR Pipeline**: 执行PR流水线，包括：
  - 安全扫描（SonarQube、Trivy、依赖检查）
  - 构建和测试
  - 质量检查
  - 自动发布GitHub PR评论

#### 主要功能:
- 目标分支验证（仅允许master/main分支）
- 多种安全扫描强度配置（fast/standard/deep）
- 自动PR评论反馈
- 代码质量报告生成

---

### 2. demo-helloworld-master-branch-auto-deploy
**触发条件**: 当PR merge到master分支或有代码push到master分支时自动触发  
**Jenkinsfile**: [JenkinsfileMasterAutoDeploy.groovy](https://github.com/yakiv-liu/demo-helloworld/blob/main/jenkinsfiles/JenkinsfileMasterAutoDeploy.groovy)

#### Stages:
- **Initialize & Validation**: 初始化和参数验证
- **Checkout & Setup**: 代码检出和环境设置
- **Build & Security Scan**: 
  - **Build**: Maven构建、Docker镜像构建、Trivy安全扫描、镜像推送
  - **Security Scan**: SonarQube扫描、依赖检查（并行执行）
- **Quality Gate**: SonarQube质量门检查
- **Sequential Deployment**: 顺序部署到staging → pre-prod环境

#### 主要功能:
- 自动版本号生成（时间戳格式）
- 安全扫描和质量门控
- 顺序部署到测试环境
- 自动回滚机制
- 数据库部署记录

---

### 3. demo-helloworld-main-branch-auto-deploy
**触发条件**: 当PR merge到main分支或有代码push到main分支时自动触发  
**Jenkinsfile**: [JenkinsfileMainAutoDeploy.groovy](https://github.com/yakiv-liu/demo-helloworld/blob/main/jenkinsfiles/JenkinsfileMainAutoDeploy.groovy)

#### Stages:
- **Initialize & Validation**: 初始化和参数验证
- **Checkout & Setup**: 代码检出和环境设置
- **Build & Security Scan**: 
  - **Build**: Maven构建、Docker镜像构建、Trivy安全扫描、镜像推送
  - **Security Scan**: SonarQube扫描、依赖检查（并行执行）
- **Quality Gate**: SonarQube质量门检查
- **Sequential Deployment**: 顺序部署到staging → pre-prod → prod环境

#### 主要功能:
- 与master分支流水线类似，但部署到所有环境（包括生产环境）
- 完整的CI/CD流程
- 生产环境自动部署

---

### 4. helloworld-multi-mode-pipeline
**类型**: 手动触发流水线  
**Jenkinsfile**: [JenkinsfileManual.groovy](https://github.com/yakiv-liu/projectPipelines/blob/master/demo-helloworld/JenkinsfileManual.groovy)

#### 构建模式:
- **full-pipeline**: 完整流水线（构建+部署）- 自动生成版本号
- **build-only**: 仅构建（推送Docker镜像到仓库）- 自动生成版本号
- **deploy-only**: 仅部署（从数据库选择部署版本）

#### Stages (根据模式不同):
- **Initialize & Validation**: 初始化和参数验证
- **Checkout & Setup**: 代码检出和环境设置
- **Build & Security Scan** (full-pipeline/build-only模式):
  - Maven构建、Docker镜像构建、安全扫描、镜像推送
- **Deploy** (full-pipeline/deploy-only模式):
  - 部署到指定环境（staging/pre-prod/prod）
  - 支持版本选择和自动回滚

#### 主要功能:
- 灵活的构建模式选择
- 数据库版本管理
- 手动部署版本选择
- 支持单个环境部署
- 配置化项目参数

---

## 共享库功能

### 核心组件

#### 配置管理 (Config.groovy)
- 统一的配置管理
- 环境变量管理
- 邮件模板配置

#### 构建工具 (BuildTools.groovy)
- Maven构建
- Docker镜像构建和推送
- Trivy安全扫描

#### 安全工具 (SecurityTools.groovy)
- SonarQube代码扫描
- 依赖安全检查
- 多强度扫描配置

#### 部署工具 (DeployTools.groovy)
- Ansible部署
- 自动回滚机制
- 健康检查
- 多环境支持

#### 数据库工具 (DatabaseTools.groovy)
- 构建记录存储
- 部署历史管理
- 版本查询

#### 通知工具 (NotificationTools.groovy)
- 邮件通知
- 构建状态报告
- 自定义模板

### 环境配置

#### 基础设施
- **Nexus**: 192.168.233.8:8081
- **Harbor**: 192.168.233.9:80/mlp  
- **SonarQube**: 192.168.233.10:9000
- **Trivy**: 192.168.233.9:8084
- **数据库**: PostgreSQL (192.168.233.8:5432)

#### 部署环境
- **staging**: 192.168.233.8
- **pre-prod**: 192.168.233.9  
- **prod**: 192.168.233.10

## 特性

### 自动回滚
- 部署失败时自动回滚到上一个成功版本
- 数据库记录回滚操作
- 健康检查验证

### 安全扫描
- 代码质量分析（SonarQube）
- 容器安全扫描（Trivy）
- 依赖漏洞检查
- 多强度扫描模式

### 数据库集成
- 构建记录存储
- 部署历史追踪
- 版本管理
- 回滚记录

### 通知系统
- 邮件通知
- 构建状态报告
- GitHub PR评论
- 自定义模板

## 使用说明

### 自动流水线
- PR流水线：创建PR到master/main分支时自动触发
- 自动部署流水线：代码合并或推送到对应分支时自动触发

### 手动流水线
1. 选择构建模式：
   - full-pipeline: 完整构建部署
   - build-only: 仅构建镜像
   - deploy-only: 仅部署现有版本
2. 选择目标环境
3. 配置项目参数
4. 执行流水线

### 参数配置
所有流水线支持以下参数：
- 项目名称
- 应用端口
- 邮箱接收人
- 跳过依赖检查
- 环境选择
- 版本选择（deploy-only模式）

## 依赖要求
- Jenkins with Pipeline plugin
- Docker
- Maven
- Ansible
- PostgreSQL数据库
- SonarQube
- Trivy
- Harbor/Nexus