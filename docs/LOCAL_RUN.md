# 本地运行

需要 Java 21、Maven、Node.js 22 或 24、Python，以及本机 MySQL 8.x、Redis。macOS 可使用 Homebrew 安装 MySQL 与 Redis。Qdrant 首次运行时从官方 GitHub Release 下载并缓存在 `.runtime/local/bin/`；也可设置 LOCAL_QDRANT_BIN 指定现有程序。

```sh
bash run.sh
```

默认切换项目状态：项目未运行时启动；已有项目进程时关闭。MySQL、Redis 未启动时通过 `brew services start` 启动；关闭项目时保持这两个常驻服务运行。Linux 需预先配置系统数据库服务。

```sh
bash run.sh start          # 启动，已启动的组件不重复启动
bash run.sh start --demo   # 显式演示模式，不调用真实模型
bash run.sh stop           # 关闭项目，保留数据库、缓存与文件
bash run.sh status         # 查看进程与端口
bash run.sh logs backend   # 最近 50 行日志
```

默认地址：

| 服务 | 地址 |
| --- | --- |
| 前端 | http://127.0.0.1:5178 |
| Java 后端 | http://127.0.0.1:18080 |
| Python AI | http://127.0.0.1:18000 |
| Qdrant | 127.0.0.1:16333，gRPC 16334 |
| MySQL | 127.0.0.1:3306 |
| Redis | 127.0.0.1:6379 |

首次执行生成 `.env.local`。本地默认连接 root 空密码，仅用于读取本机现有安装；如本机已设密码或使用专用账号，应在该文件填写 DB_USERNAME、DB_PASSWORD。脚本不会修改数据库账户密码，不会清理已有数据；仅创建 DB_NAME 指定的数据库（默认 short_video_agent_local）。账号需要该数据库的建库和迁移权限。

配置优先级：进程环境 → 根目录 `.env` → 本地专用地址与默认值 → `.env.local`。供应商模型设置可放 `.env`，本机数据库和端口放 `.env.local`。配置文件按键值文本解析，不执行其中的 shell 命令。切换真实模型时将 `.env.local` 的 AI_MODE 设置为 real。演示模式需使用 `--demo` 或显式配置，不会在模型缺失时自动启用。

初次启动自动安装项目依赖与构建 Java；后续按依赖文件和源码摘要判断是否重新安装或编译。日志、进程记录、知识原件和 Qdrant 数据位于 `.runtime/local/`，停止不会删除。管理员初始化凭据来自 `.env` 的 SEED_ADMIN_USERNAME / SEED_ADMIN_PASSWORD；未设置时本地会生成随机密码，并保存在权限 0600 的 `.runtime/local/credentials.json`。启动只创建缺失的管理员，不覆盖已有账号密码。管理员登录后默认进入创作界面，从左下角账户菜单进入独立管理端。

端口被非本项目进程占用时明确报错，不会杀掉占用者。停止操作只处理启动记录与进程标识一致的本项目进程。失败启动仅回收本次已启动的组件。

Docker Compose 用于容器部署，参见 `deploy/README.md`。

## 验证

已在 macOS 上验证首次依赖准备、MySQL/Redis 系统服务启动、六个服务状态显示、无参数切换关闭、常驻服务保留、重复启动不重复进程，以及再次启动复用数据。显式 demo 模式下，通过该脚本启动的全套服务完成 18 项 HTTP 端到端检查。真实模式缺少模型配置时，基础服务可启动，AI 任务明确报配置缺失。
