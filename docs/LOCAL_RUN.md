# 本地运行

入口位于项目根目录，均使用相对于脚本位置的项目路径，不依赖用户的当前目录。

```sh
# macOS / Linux
bash run.sh
```

```bat
:: Windows：也可直接双击 run.bat
run.bat
```

## 菜单

打开后先检测 MySQL、Redis 和项目进程及端口，以绿色主题显示状态和内网 IPv4 地址，不自动安装依赖、不写配置，也不启停服务。终端使用中文语言、窄窗口或不同 TZ 时，进程识别仍采用固定格式，不会将已运行的本项目误报为端口冲突。

| 编号 | 操作 |
| --- | --- |
| 1 | 项目未运行时为“启动项目”；已有项目进程时为“停止项目” |
| 2 | 重新检测并刷新状态 |
| 3 | 查看各服务最近日志 |
| 4 | 取消 / 退出 |

支持 ↑↓ 或 ←→ 移动高亮选项，按 Enter 确认；数字 1–4 可以定位选项，按 Enter 确认。Esc、Q 或 Ctrl+C 取消。终端输入重定向时使用数字输入；空输入或输入结束视为取消，不能意外触发启停。

只有确认“启动项目”后，才准备依赖、构建 Java 并启动项目。MySQL、Redis 已运行时复用，未运行时尝试启动已安装的系统服务。停止项目只停止所记录的 Qdrant、AI、Java、前端进程，保留 MySQL、Redis 与数据。

前后端进程提前退出或健康检查超时，会显示服务名、原因、日志路径及最近日志，例如端口占用、数据库连接失败或缺少 Java / Node。失败启动只回收本次启动的项目组件，原先运行的组件保持原状，菜单仍可操作。

## 环境

需要 Python 3.11+（推荐 3.12）、Java 21+、Maven、Node.js 22 或 24、npm、本机 MySQL 8.x、Redis 兼容服务及 mysql、redis-cli 客户端，相关命令需在 PATH 中。可用 PYTHON 指定启动控制器的解释器路径，用 LOCAL_AI_PYTHON 指定 AI 虚拟环境的基础解释器。

- macOS：MySQL、Redis 未启动时使用 Homebrew 的 brew services start。
- Windows 10/11 x64：使用已注册的 MySQL、Redis 或 Memurai 系统服务。脚本自动查找服务名；找到多个时需在 .env.local 用 LOCAL_MYSQL_SERVICE、LOCAL_REDIS_SERVICE 指定。服务启动需要相应的 Windows 服务管理权限，已运行的服务不需要重复启动。脚本不自动安装数据库或注册系统服务。
- Linux：使用 systemd，默认服务名为 mysql、redis-server，可用上述两个变量修改；当前账户需有服务管理权限。

Qdrant 首次启动时从官方 GitHub Release 下载 1.15.5，缓存在 .runtime/local/bin/。Windows 使用 x64 ZIP 与 qdrant.exe；macOS/Linux 使用对应架构 TAR 包。也可配置相对路径 LOCAL_QDRANT_BIN=tools/qdrant.exe。Windows AI 虚拟环境使用 .venv/Scripts/python.exe，macOS/Linux 使用 .venv/bin/python。依赖缓存包含操作系统和架构信息，不复用其他平台的原生依赖。

## 直接命令

```sh
bash run.sh start          # 明确启动；已启动的组件不重复启动
bash run.sh start --demo   # 显式演示模式，不调用真实模型
bash run.sh stop           # 关闭项目，保留数据库、缓存与文件
bash run.sh status         # 只读检测进程与端口
bash run.sh logs backend   # 最近 30 行后端日志
```

Windows 将 bash run.sh 换成 run.bat，例如 run.bat status。旧的 toggle 仅作为显式命令保留，无参数入口始终先显示菜单。切换 AI 模式需先停止项目再启动。

## 默认地址

| 服务 | 地址 |
| --- | --- |
| 前端 | http://内网IPv4:5178/ |
| Java 后端健康检查 | http://内网IPv4:18080/api/health |
| Python AI 内部健康检查 | 本机端口 18000，路径 /health |
| Qdrant 内部服务 | 本机端口 16333，gRPC 16334 |
| MySQL | 本机端口 3306 |
| Redis | 本机端口 6379 |

内网 IPv4 自动检测；macOS 优先使用活动的实体网卡，排除 VPN 和虚拟网卡。有多网卡或 VPN 时可通过 LOCAL_LAN_IP 指定，例如 LOCAL_LAN_IP=192.168.1.100。没有有效内网 IPv4 时明确提示，不用回环地址冒充内网地址。

后续启动时，前端和 Java 后端监听内网；AI 与 Qdrant 保留本机内部监听，MySQL、Redis 绑定设置不由脚本修改。现有运行进程不自动重启；若仍仅监听本机，菜单标注“仅本机”和“下次启动启用内网访问”。只有检测到内网端口可达后才显示为当前可用地址。

端口可在 .env.local 中修改，菜单显示实际配置值。端口被其他进程占用会明确报错，不会终止占用者。如果端口已监听但进程归属尚未确认，会明确说明，不声称整个项目未启动。停止前核验进程记录与实际进程身份；Windows 使用创建时间及可执行文件路径，避免误停复用 PID 的进程。

## 配置与数据

首次确认启动时生成缺失的 .env.local。数据库配置为 DB_NAME、DB_USERNAME、DB_PASSWORD；脚本不修改数据库账户密码，不清理已有数据，仅创建指定数据库（默认 short_video_agent_local）。账户需要建库和迁移权限。配置文件以 UTF-8 键值文本解析，支持 UTF-8 BOM，不执行其中的命令。

配置优先级：进程环境 → 根目录 .env → 本地专用地址与默认值 → .env.local。模型凭据放 .env，本机数据库、端口及系统服务名放 .env.local。配置示例见根目录 .env.local.example。

日志、进程记录、知识原件和 Qdrant 数据位于 .runtime/local/。管理员初始化凭据优先使用 .env 的 SEED_ADMIN_USERNAME / SEED_ADMIN_PASSWORD；缺失时本地使用 admin / 123456，内部服务密钥随机生成并保存到 .runtime/local/credentials.json。只创建缺失管理员，不覆盖已有账户密码。显示日志时隐藏已配置的密钥与密码。

Docker Compose 用于最终容器部署，见 deploy/README.md。

## 验证范围

控制器测试：python3 -m unittest scripts.test_local_runner -v。测试使用临时目录与模拟进程，覆盖菜单确认/取消、四向按键、默认无操作、Windows 文件锁、系统服务检测、CMD 路径引号、Qdrant ZIP、故障日志与回收范围，不启动项目服务。

本轮在 macOS 验证了从其他目录打开根目录入口、数字取消、真实终端方向键与回车退出，并核对服务进程与配置均未改变。Windows 分支完成模拟兼容检查，尚未在 Windows 实机执行完整启动验收。

Windows 实现参考：[进程创建时间](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocesstimes)、[Python Windows 文件锁](https://docs.python.org/3/library/msvcrt.html#msvcrt.locking)。
