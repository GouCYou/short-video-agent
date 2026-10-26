# 验证记录

## 2026 年 10 月 26 日：Agent 入库、批量导入、双侧栏与时间戳

前端 55 项、Python 67 项通过，Java 34 项通过及 1 项可选检查跳过。生产构建、TypeScript 与 Compose 配置校验通过。真实模型及 HTTP 工作流见 `validation/workspace-real-v5.json`；浏览器验证与功能边界见 `WORKSPACE_V5.md`。


## 2026 年 10 月 25 日：默认知识库、回复替换与独立管理端

当前前端 47 项通过，Java 31 项通过及 1 项可选检查跳过，Python 63 项通过；TypeScript 检查和生产构建通过。新增覆盖历史回复原位替换及取消保留、每用户默认资料编辑恢复与所属权限、服务端文档范围在向量查询前生效、独立管理端切换、六位管理员登录。

真实模型问候与重试已完成，重试后仍为两条消息，回复 ID 与时间戳不变，运行 ID 更新。实际浏览器验证默认资料修改和恢复、空作品页无多余溢出、加号居中、账户页面加宽、独立管理端及返回用户端。证据见 `validation/workspace-real-v4.json` 与 `WORKSPACE_V4.md`。Embedding 未配置，真实语义检索质量仍待验证。

以下为此前版本验证记录。

## 2026 年 10 月 25 日：连续工作区、文件和记忆

当前结果：前端 43 项通过、Java 28 项通过及 1 项可选检查跳过、Python 62 项通过；类型检查、生产构建和 Compose 配置校验通过。真实模型完成文档阅读、完整制作包归档、跨对话偏好与历史作品读取、重试和自然问候，记录见 `validation/workspace-real-v3.json`。Office/PDF 实际解析及上传见 `validation/document-formats-v3.json`、`validation/attachment-api-v3.json`。

浏览器连接已恢复。桌面与窄屏实际检查通过，已验证自定义菜单、侧栏状态保持、Word 上传、回复用时与摘要、复制、重试、真实作品列表与置顶。当前截图和边界见 `WORKSPACE_V3.md`。以下浏览器禁用及旧测试数量属于此前版本的历史记录。

## 2026 年 10 月 25 日：真实模型与交互修正

生成配置已改为正确的七牛地址 `https://api.qnaigc.com/v1`，并使用 `qiniu/conatus-free`。原生工具调用和流式探测均为 HTTP 200，记录见 `validation/provider-conatus.json`。模型返回的推理字段由适配器过滤，不进入聊天消息。

真实服务完成 6 组会话检查：普通问候直接回答且不调用选题工具；缺内容方向时先交流；明确咖啡领域并请求选题时返回 ABC；选择后不自动推送下一组；真实生成完整脚本、分镜、拍摄、配音和剪辑包；局部修改开场且保持其他字段。记录见 `validation/conversation-real-v2.json`。没有使用 demo 生成结果。完整制作包首次生成曾出现结构校验失败，后续受限重试通过；输出延迟仍与供应商及生成长度相关。

当前 Python 59 项、前端 41 项测试通过，前端类型检查与生产构建成功。新增覆盖普通问候/缺方向/仅讨论时拒绝选题卡、确认卡片不强制推进、可访问的动画折叠、Markdown 格式化与危险 HTML/链接/远程图片拦截。开场改写改为小范围结构化补丁，避免为了改一段而重新输出整份制作包；真实AI接口验证通过，约18.1秒完成且其他作品字段保持不变，见 `validation/opening-patch-real.json`。

新版按 `design/conversation-workspace-v2.png` 完善了单行自适应输入、导航图标、Logo 比例、用户消息对齐、响应式选题卡、左右栏及页面/折叠动画。当前浏览器工具仍报告 Work 本地浏览器禁用，尚未取得新版桌面/移动端渲染证据，视觉验收未通过。下文中“尚未完成真实模型调用”的结论为此前快照，已由本节真实调用结果取代。

## 2026 年 10 月 25 日：对话工作台第一版

已使用本机 MySQL、Redis、Qdrant、Java、Python 和 Vite 完成真实 HTTP 联调。新增对话检查共 10 组通过，覆盖选题/方向/观众的 ABC/D、讨论时不提前创建作品、伪造和过期选择拒绝、消息幂等、用户隔离、完整制作包、SSE 重放、局部改稿、手动保存冲突、版本恢复、定稿与导出。记录见 `validation/conversation-e2e.json`。

原有业务的 18 组集成回归再次通过，记录见 `validation/legacy-regression-2026-10-25.json`。两组创作测试均明确使用 demo 模式；数据库、调度、HTTP、事件持久化与 SSE 为实际服务执行，生成内容及向量为演示数据。

自动测试结果：

| 服务 | 结果 | 重点 |
| --- | --- | --- |
| Java | 26 通过，1 个可选 MySQL 时区测试本轮跳过；package 成功 | 取消后迟到写入、独立修改范围保护、工具事件先落盘、重启中断恢复、真实 NDJSON 读取 |
| Python | 50 通过 | 原生流式工具分片、结构化动作回退条件、推理过滤、卡片确认、完整制作包、引用与修改范围、超时及预算 |
| 前端 | 39 通过；类型检查与生产构建成功 | 五类选择卡、完整作品编辑、SSE 解析与鉴权、重复事件、取消/轮询/路由竞态、中文工具摘要 |
| Compose | 配置校验通过 | 本轮未进行实际 Docker 容器部署 |

已用真实配置重试生成供应商的 tools 与 streaming 请求，均在 TLS 连接阶段失败，未收到模型响应，见 `validation/provider-connectivity.json`。另将全套服务切换 real 模式发起实际会话请求，确认运行持久化为 FAILED、错误明确、没有降级到 demo，已有作品与版本数保持不变，见 `validation/conversation-real-failure.json`。随后恢复显式 demo 模式供界面预览。真实模型兼容性、内容质量与费用尚未验收；Embedding 配置仍未提供。

当前浏览器工具持续返回 `Local browser use is disabled in Work settings`，与用户提供的已启用设置截图不一致。无法取得本轮页面截图、进行桌面/移动视觉对比或检查浏览器控制台；这些项目仍未完成，详见根目录 `design-qa.md`。组件测试和构建不能替代浏览器验收。

以下为此前版本的验证记录。

2026 年 10 月 23 日，本机独立测试环境完成以下验证。测试数据库与已有数据库隔离，未使用任何真实模型密钥。

## 实际服务集成

使用 MySQL 8.0.46、Redis 8.6.1（部署编排使用 Redis 7.4）、Qdrant 1.15.5、Java 21、Python 3.12 和 Vite 代理。根目录 `scripts/smoke_test.py` 通过前端代理连接业务服务，18 项检查全部通过。记录见 `validation/end-to-end.json`。

包含：登录注册、个人信息、管理员权限、资料文本与实际文件上传、异步索引、真实 Qdrant 检索、资料启停、任务所属用户隔离、引导选题确认、脚本生成、引用快照、局部修改字段保护、手动保存、版本恢复、导出、快速生成、幂等提交、历史搜索、重建与删除知识、用户禁用、会话撤销、密码修改及管理统计。

此轮 AI 使用显式 `demo` 模式。索引、检索、数据库、Redis 与 HTTP 调用是真实执行；生成文案和 Embedding 为明确标注的确定性合成测试数据，不作为真实模型质量结论。

另外使用独立数据库与 `real` 模式服务，在缺少生成 API 配置时创建任务，验证 MySQL 中保存 FAILED、明确指出所缺配置，并且没有生成结果和版本。显式将测试 AI 改为 demo 后重试同一任务，成功保存有 DEMO 标注的版本，旧失败事件仍保留。记录见 `validation/config-retry.json`。AI 私有接口另验证了缺少 Embedding 配置的 503 和错误内部凭据的 401。

## 自动测试

- Java：15 项测试通过（含实际 MySQL 时区回归），`mvn package` 生成可执行 JAR。H2 MySQL 兼容模式执行迁移与业务集成；Redis 命令在单元测试中模拟，实际 Redis 行为由上面的端到端检查补充。
- Python：20 项 pytest 通过，涵盖结构化模型 HTTP 适配、限次结构重试、未知引用拒绝、局部修改、路径/文件校验、Qdrant 生命周期。实际 Qdrant 独立验证了索引幂等、中文检索、停用与删除。
- 前端：13 项组件测试、TypeScript 检查及 Vite 生产构建通过，组件测试覆盖登录注册、错误展示、结构化编辑、文本安全、选题、轮询销毁、路由请求竞态和幂等重试。
- Docker Compose：`docker compose config --quiet` 校验通过，服务配置与依赖已检查。

## 待具备条件后验证

1. 本机 Docker daemon 当前不可连接，因此尚未实际构建和启动六个容器；宿主分服务启动与 Compose 配置校验不等同容器部署验收。
2. 最初自动测试未覆盖浏览器视觉检查；后续浏览器验收见 `UI_REVIEW.md`。
3. 生成模型、Embedding 模型的供应商兼容性、实际响应时延/费用、语义检索质量和内容质量，等待提供 API 配置后验证。

## 复现

参考根目录 README 启动服务。在测试环境设置 `SMOKE_ADMIN_PASSWORD`，执行：

```sh
python3 scripts/smoke_test.py --base-url http://localhost:8088/api --report docs/validation/end-to-end.json
```

该脚本会创建测试用户与作品，删除本轮临时知识资料。请勿将它直接运行在正式业务数据环境。`docs/examples/evaluation-cases.json` 提供 30 个创作需求；AI 服务中的 `python -m app.evaluate` 可执行三组对照，见该服务 README。演示评测只验证脚本可运行，不能作为论文实验效果。


后续界面预览时发现 MySQL 会话时区与 JDBC 配置不一致导致显示时间偏移，已通过 UTC 会话配置修复并在实际 MySQL 中回归验证；无需重写已有数据。

