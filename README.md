# 短视频内容智能策划与文案生成系统

Vue 3 + Element Plus 创作工作台，Spring Boot 业务服务，Python LangGraph Agent 与 RAG，MySQL 8.x、Redis 和 Qdrant。

## 演示截图

### 对话创作工作台（桌面）

![对话创作工作台桌面预览](docs/ui-preview/18-conversation-columns-v5.jpg)

### 对话创作工作台（窄屏）

![对话创作工作台窄屏预览](docs/ui-preview/21-conversation-narrow-v5.jpg)

更多知识库、作品管理和管理端截图见 [`docs/ui-preview/`](docs/ui-preview/)。截图展示本地演示界面，不代表线上服务已部署。

## 项目目录

```text
short-video-agent-frontend/   Vue3 TypeScript Vite Element Plus
short-video-agent-backend/    Java21 Spring Boot REST API
short-video-agent-ai/         Python FastAPI LangGraph Qdrant
compose.yaml                 六个服务的一体部署
scripts/                     环境初始化和端到端验证
docs/                        架构、接口、配置与测试说明
```


## 本地启动

```sh
bash run.sh
```

再次执行可关闭项目。MySQL、Redis 作为常驻服务保持运行，终端显示每个服务的端口。首次运行会安装依赖、构建并生成 `.env.local`，本机数据库账户与端口可在该文件配置。

```sh
bash run.sh start --demo
bash run.sh status
bash run.sh stop
```

前端默认 http://127.0.0.1:5178 。真实模式需配置生成模型；知识检索还需独立配置 Embedding。显式 demo 模式只验证界面与业务链路，不代表真实模型质量。详细配置见 [本地运行](docs/LOCAL_RUN.md)。

## Docker 部署

```sh
./scripts/setup-env.sh
docker compose up -d --build
```

容器前端默认 http://localhost:8088 。管理员初始化凭据位于 `.env`。部署说明见 [deploy/README.md](deploy/README.md)。

## 功能

首页为对话式创作工作台：连续讨论需求，通过 A/B/C 选择选题、表达方向、观众、风格和制作方式，或使用 D 输入自定义内容。Agent 按消息选择工具，执行过程实时显示，创作结果包含脚本分镜、拍摄、配音和剪辑方案，可继续局部改稿、手动编辑、比较与恢复版本、定稿及导出。

保留用户注册登录、原有任务与作品、知识引用和个人信息管理。每个账户拥有可编辑的默认知识库，可添加资料、启停、重建索引、测试检索和恢复预置内容。管理员从左下角账户菜单进入独立管理端，提供账户概览与用户启停、角色管理；返回用户端后恢复创作工作台。

对话支持 Word、PDF、PowerPoint 等文档上传、自动标题、聊天摘要、可编辑用户画像与历史作品读取。作品和对话共用可收起侧栏，详见 [连续创作工作区](docs/WORKSPACE_V3.md)。

最新交互与知识库说明见 [对话入库与独立双侧栏](docs/WORKSPACE_V5.md)，管理端说明见 [独立管理端](docs/WORKSPACE_V4.md)。

## 验证

```sh
cd short-video-agent-frontend && npm ci && npm test && npm run build
cd ../short-video-agent-backend && mvn test
cd ../short-video-agent-ai
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
```

运行中的系统可执行端到端验证，管理员密码通过环境变量传入：

```sh
SMOKE_ADMIN_PASSWORD='本地管理员密码' python3 scripts/smoke_test.py
SMOKE_ADMIN_PASSWORD='本地管理员密码' python3 scripts/conversation_smoke.py
```

脚本会创建测试用户、知识资料和任务；知识测试资料会删除，任务保留供检查。请在开发或测试数据库运行。

## 文档

- [本地启停脚本](docs/LOCAL_RUN.md)
- [对话式 Agent 工作台设计](docs/AGENT_WORKSPACE_DESIGN.md)
- [测试结果与待验收事项](docs/TESTING.md)
- [配置和后续所需模型 API](docs/CONFIGURATION.md)
- [部署与数据保留](deploy/README.md)
- [系统架构与功能边界](docs/ARCHITECTURE.md)
- [接口契约](docs/API_CONTRACT.md)
- [对话与制作包接口](docs/CONVERSATION_API.md)
- [演示知识资料](docs/examples/knowledge-demo.md)

本机已接入 `qiniu/conatus-free`，真实对话、选题工具、完整制作包及局部改稿已验证。浏览器已完成本轮桌面与窄屏检查；Embedding 与真实知识库检索质量仍需独立验收，见测试记录。
