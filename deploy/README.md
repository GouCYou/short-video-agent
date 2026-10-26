# 部署

在项目根目录执行：

```sh
./scripts/setup-env.sh
# 编辑 .env，填写生成模型与 Embedding 模型配置
# 或首次运行 ./scripts/setup-env.sh --demo，显式使用合成示例模式

docker compose up -d --build
```

浏览器打开 http://localhost:8088 。初始化管理员用户名默认为 `admin`，初始化脚本会为 `SEED_ADMIN_PASSWORD` 生成随机强密码；请从本地 `.env` 查看并在首次登录后修改。首次启动建表、构建镜像需要等待。`docker compose logs -f backend ai` 查看服务日志。模型密钥只能放服务端 `.env`，不能放 `VITE_` 变量中。

只公开本机前端端口；MySQL、Redis、Qdrant 和 AI 不映射宿主端口。外网部署需在前方配置 HTTPS 反代，并按需要更改前端绑定。不要直接开放内部服务。

配置修改后 `docker compose up -d` 重建相关容器。演示模式自动给集合名增加 `_demo` 后缀，与真实向量隔离。切换模式后必须重新索引知识；更换真实 Embedding 模型时也要更换 QDRANT_COLLECTION 并重建索引。

`docker compose down` 保留数据卷。不要使用 `down -v`，除非明确要删除全部数据。备份 MySQL 逻辑数据、Qdrant 快照和 knowledge-data 原始文件；三者应在暂停写入期间保持一致。
