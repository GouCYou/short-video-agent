# 配置与 API 接入

所有模型凭据仅配置到服务端环境。前端不直接访问模型服务。默认 `AI_MODE=real`，缺少模型配置时创建任务会得到可重试的明确错误，而不会生成假结果。

## 后续需要提供的信息

| 配置 | 用途 |
| --- | --- |
| GENERATION_BASE_URL | 生成模型 OpenAI 兼容 API 基础地址，通常以 /v1 结尾，不带 /chat/completions |
| GENERATION_API_KEY | 生成模型密钥 |
| GENERATION_MODEL | 生成模型标识，必须是供应商实际提供的型号名 |
| EMBEDDING_BASE_URL | 向量模型 API 基础地址，不带 /embeddings |
| EMBEDDING_API_KEY | 向量模型密钥，可与生成服务共用，仍需分别填写 |
| EMBEDDING_MODEL | 支持中文的文本向量模型标识 |

不需要第三个 AI 服务密钥。不强制使用付费 Reranker、搜索引擎、视频生成或 OCR API。生成服务需支持聊天接口与结构化 JSON 输出。对话工作台优先使用原生工具调用和流式输出；仅当供应商明确返回不支持 tools 时切换为结构化动作循环，连接失败或鉴权错误不会触发演示模式。缺少 Embedding 仍可进行创意讨论与制作包生成，知识检索会明确返回配置缺失。

Embedding 返回的向量维度必须稳定，变更模型或维度后换用新的 QDRANT_COLLECTION 并重新索引知识。

## 基础设施

- DB_URL / DB_USERNAME / DB_PASSWORD：Java 使用的 MySQL8 JDBC 连接。
- REDIS_HOST / REDIS_PORT / REDIS_PASSWORD：Redis 会话和任务执行锁。
- AI_INTERNAL_KEY：Java 与 Python 间共享内部凭据，由 setup-env.sh 随机生成。
- QDRANT_URL / QDRANT_COLLECTION：向量库地址与集合，不属于大模型供应商配置。
- KNOWLEDGE_DIR：两个服务共享的原始知识文件目录，路径须一致。
- SEED_ADMIN_USERNAME / SEED_ADMIN_PASSWORD：仅用于首次创建管理员；已有用户不会被重置。

## 演示模式

仅在主动设置 `AI_MODE=demo` 时启用。用于离线界面、业务接口和知识索引链路测试，输出必须明确标注为演示。生成文案和向量均是确定性合成数据，不能用于评估实际模型质量。演示模式自动给集合名增加 `_demo` 后缀，避免混入真实向量。切回真实模式后需要重新索引资料。

## 运行检查

本机已配置 `https://api.qnaigc.com/v1` 和 `qiniu/conatus-free`，真实中文对话、原生工具调用、流式回复、选题、制作包和局部改稿已完成 HTTP 验证。密钥仅保存在忽略提交的服务端环境文件中。Embedding 仍需单独配置。

此前错误地址 `api.naigc.com` 少了字母 q，对应 TLS 失败记录属于旧配置。原指定 NVIDIA 模型正确标识为 `nvidia/nemotron-3-ultra-550b-a55b0-free`，供应商 `/models` 已核对；本轮该模型返回上游 502，未将其替换为演示输出。

Conatus 的免费活动有结束时间，当前官方说明为北京时间 2026 年 11 月 30 日 23:59 结束；到期后需重新选择可用模型，项目不会自动切换到付费型号。见 [七牛活动说明](https://www.qiniu.com/ai/free-models/conatus-2026)。

用户端知识库展示向量配置与连接状态，不展示密钥。独立管理端当前仅提供账户概览与用户管理。真实模型验收至少应覆盖：真实生成、真实 Embedding 索引与检索、局部改写、空检索、服务超时或限流，以及 Token 统计是否由供应商返回。

## 注册限流

`REGISTRATION_MAX_ATTEMPTS` 默认 10，`REGISTRATION_WINDOW_SECONDS` 默认 3600，按客户端 IP 计算。测试环境可以单独提高，正式环境保留默认保护。反向代理覆盖客户端转发头，后端只通过受信任代理处理客户端 IP。不要在清理测试计数时使用 Redis FLUSHALL，以免删除会话和执行锁。

对话入库回调使用内部服务地址 BACKEND_INTERNAL_URL：本地脚本自动设为本机后端端口，Compose 设为 http://backend:8080；两端使用同一个 AI_INTERNAL_KEY。该地址与密钥不会传给模型或浏览器。
