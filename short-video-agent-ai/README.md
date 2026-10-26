# Short Video Agent AI

私有 FastAPI AI 服务，业务数据与任务持久化由 Java/MySQL 负责。本服务按请求执行 LangGraph 状态图，阶段结果由 Java 记录。仅 `/health` 无需认证，其他入口必须提供 `X-Internal-Key`。不对公网开放本服务。

## 启动

Python 3.12：

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
# 将下表配置设置到当前进程环境；生产密钥不要提交到仓库。
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
```

推荐由根目录 Docker Compose 启动。知识目录需要与后端共享，AI 侧可只读挂载。单个执行进程内按文档加锁，Qdrant 支持持久化；不能把本服务当作持久化任务队列。

## 配置

| 变量 | 用途 / 默认值 |
| --- | --- |
| `AI_MODE` | `real`（默认）或显式 `demo` |
| `AI_INTERNAL_KEY` | 必填，须与 Java 服务一致；缺失则关闭全部私有 API |
| `GENERATION_API_KEY` | 生成服务密钥 |
| `GENERATION_BASE_URL` | OpenAI 兼容服务基础地址，例如供应商提供的 `/v1` 地址；自动追加 `/chat/completions` |
| `GENERATION_MODEL` | 支持 JSON object 输出的生成模型 ID |
| `EMBEDDING_API_KEY` | 向量服务密钥，可与生成服务不同 |
| `EMBEDDING_BASE_URL` | 自动追加 `/embeddings`；响应应为 float vectors |
| `EMBEDDING_MODEL` | 中文向量模型 ID |
| `QDRANT_URL` | `http://localhost:6333`；容器内应使用 `http://qdrant:6333` |
| `QDRANT_API_KEY` | 可选，仅服务端使用 |
| `QDRANT_COLLECTION` | `short_video_knowledge`，演示模式自动追加 `_demo` |
| `KNOWLEDGE_DIR` | `/data/knowledge`，仅允许读取此目录内文件 |
| `AI_HTTP_TIMEOUT_SECONDS` | 单次上游调用 120 秒（允许 5～300） |
| `AI_HTTP_RETRIES` | 临时网络错误、429/502/503/504 最多重试 2 次（允许 0～3） |
| `RETRIEVAL_MIN_SCORE` | Cosine 检索阈值 `0.2`，需在真实语料上调优 |

基础地址不要填写完整 `/chat/completions` 或 `/embeddings` 路径。默认 `real` 未配置时返回可操作的 503，绝不自动改用演示输出。单个生成/索引请求总时限 300 秒，取消剩余调用；后端超时应大于此值。

目前以供应商支持广泛的 `response_format: {type: json_object}` 请求 JSON，再以 Pydantic Schema 严格验证字段；结构错误最多重新生成一次。模型需要支持该参数，不支持时会如实报错。

更换 Embedding 模型或供应商时，请使用新 `QDRANT_COLLECTION` 并重建知识索引；即使维度相同，两个模型的向量也不能混用。维度不匹配时返回 409。

## 执行方式

- `PLAN`：分析 → 可选检索 → 三个选题，返回后由业务系统等待用户选择；不生成脚本。
- `GENERATE`：分析 → 可选检索 →（无选题时生成并采用首个）→ 策划 → 脚本 → 结构与时长检查 → 最多一次修订。
- `REVISE`：使用请求携带的历史来源快照，只修改指定字段或零开始的段落下标。代码强制合并允许范围，其他字段保持原值；已有资料即使被删除，也不损坏历史作品。`caption` 范围包括发布文案及标签，`script` 仅包括分段脚本。

引用只允许从提供片段中选择 `usedChunkIds`，未知引用直接报错。生成结果返回确实采用的资料快照；未采用资料时明确提示核查。检索分数低于阈值或资料库为空，返回空结果并提示依据不足。时长及字数是粗估指标，不代表事实审核或真实口播验收。

知识上传由 Java 保存文件，再调用索引。支持 UTF-8/GB18030 TXT、MD、DOCX 段落和表格、文本型 PDF。限制文件 20 MB、提取文本 200 万字、PDF 500 页；不支持 OCR 和加密 PDF。切分默认 700 字 / 100 字重叠，每批最多 16 个片段向量化；先完成新向量再替换旧索引。片段 ID 根据文档、内容版本和下标生成，重复索引幂等。启停、分类、来源地址和删除同步更新 Qdrant。

## 演示模式

`AI_MODE=demo` 不需要模型密钥，但仍使用真实 Qdrant 完成索引/检索、分类和删除链路。演示向量是确定性字符哈希，不能代表语义检索质量。检索到资料时，第二段直接展示首个片段的原文摘录并附 chunkId，返回相应来源快照，以便演示引用与历史版本。生成内容、事件和 quality 均标注 DEMO；这是功能演示模板，不能作为真实生成或论文效果评测。真实模式和演示模式使用独立 collection。

## 测试与评测

```sh
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest -q
# 配置真实生成 / Embedding 服务与知识库后执行三组对照：
.venv/bin/python -m app.evaluate --input examples/evaluation.json --output evaluation/results.jsonl --repeats 2
```

评测随机化执行顺序并固定种子，保存每次成功/失败、内容、来源、耗时和 Token；同时生成 summary JSON。`direct` 单次提示，`agent` 分阶段但关闭 RAG，`agent_rag` 启用 RAG；同一个模型与相同采样温度。语义质量需按统一量表由人工盲评，脚本不会用模型自评分冒充实验结论。重复运行有调用费用。`--allow-demo` 只用于脚本功能检查，并明确标记结果不适用于效果评测。

接口字段见根目录 `docs/API_CONTRACT.md`。健康检查不返回地址、密钥或模型输入内容。

## 对话式创作

`POST /internal/conversation/run` 使用相同私有鉴权，返回逐行 `application/x-ndjson`。此接口只要求生成模型配置；若 Agent 尝试检索且 Embedding 未配置，发出真实 `tool.failed` 并把错误交给模型，允许继续讨论表达与创意，不能伪造事实依据。

对话模型自主决定直接回答或调用工具：`update_brief`、`propose_options`、`search_knowledge`、`create_production`、`revise_production`、`check_production`。支持原生 OpenAI 兼容 `tools` 与流式工具参数；工具完成后继续调用模型，直接转发实际回答的内容增量。`reasoning_content` 等推理字段不会转发，`<think>` / `<analysis>` 块也会移除。

仅当上游明确以 400/404/422/501 拒绝工具能力时，才切换到结构化动作选择；后备方案仍由模型动态选择工具，再以实际流式自然语言收尾。网络故障、密钥错误、一般参数错误不会触发降级或演示模式。

最多 8 轮模型决策、16 次工具调用、2 次作品写入，总执行上限 300 秒。创建制作包前有独立的结构化语义确认，只允许用户明确要求整套交付，调用用量计入本轮 usage。讨论、选择选题、确认 A/B/C/D 不自动创建作品。选项确认值在代码中先写入 Brief，本轮模型不能覆盖该字段。

制作包 Schema 位于 `app/conversation_models.py`，包括脚本和稳定 UUID 分镜、拍摄计划、配音完整口播稿、剪辑计划。修改范围 `opening`、`titles`（主标题和备选）、`voiceover`、`shooting`、`editing`、`duration`、`segment:<UUID>` 或 `ALL`。除 ALL 外在代码中精确合并允许字段，其他字段保持不变；时长修改仅更改各段 duration，保留分镜标识。引用必须来自当前检索证据或历史快照，未知引用不产生作品更新。

`tool.started` 在实际工具执行之前发出，`tool.completed` / `tool.failed` 来自实际执行结果；没有模拟延迟。客户端断开时异步生成器可取消，Java 业务层还必须用运行标识防止已取消运行的迟到写入。`artifact.updated` 带 scope，由 Java 在收到 final 并通过当前运行校验后原子保存；AI 不维护作品版本数据库。

显式 `AI_MODE=demo` 提供固定选项与制作包，仅供界面和业务链路测试，所有回答与作品质量说明都标记演示模式。它不代表真实模型语义理解或生成验收。完整协议见根目录 `docs/CONVERSATION_API.md`。
