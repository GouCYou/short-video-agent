import asyncio
import json
import logging
import secrets
from contextlib import asynccontextmanager
import httpx
from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from .conversation import ConversationAgent
from .conversation_models import ConversationRequest
from .config import Settings
from .errors import ServiceError
from .knowledge import Knowledge, chunk_text
from .models import GenerateRequest, IndexRequest, MetadataRequest, InternalSearchRequest, ChunkPreview
from .providers import Provider
from .workflow import Workflow
from .documents import ExtractRequest, extract_document
from .memory import MemoryRequest, summarize
from .model_settings import ModelSettings, ModelUpdate

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, transport=None):
    cfg = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(timeout=cfg.timeout, transport=transport) as client:
            store=ModelSettings(cfg)
            def build(active):
                from types import SimpleNamespace
                provider=Provider(active,client);knowledge=Knowledge(active,provider,client)
                return SimpleNamespace(cfg=active,provider=provider,knowledge=knowledge,workflow=Workflow(active,provider,knowledge),conversation=ConversationAgent(active,provider,knowledge))
            def publish(runtime):
                app.state.runtime=runtime
                # Compatibility handles for tests and diagnostics; requests capture runtime once.
                for name in ('provider','knowledge','workflow','conversation'): setattr(app.state,name,getattr(runtime,name))
            app.state.model_settings=store;app.state.build_runtime=build;app.state.publish_runtime=publish
            publish(build(store.cfg))
            yield

    app = FastAPI(title="Short Video Agent Private AI", version="1.0.0", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    async def authenticate(x_internal_key: str = Header(default="")):
        if not cfg.internal_key:
            raise ServiceError("AI_INTERNAL_KEY 尚未配置，私有 API 已关闭。", 503, "INTERNAL_KEY_NOT_CONFIGURED")
        if not secrets.compare_digest(x_internal_key, cfg.internal_key):
            raise ServiceError("内部服务身份验证失败。", 401, "UNAUTHORIZED")

    @app.exception_handler(ServiceError)
    async def service_error(_request, exc):
        return JSONResponse(status_code=exc.status, content={"message": exc.message, "code": exc.code})

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request, exc):
        # Avoid reflecting uploaded data and request fields back to clients/logs.
        fields = [".".join(str(part) for part in item["loc"]) for item in exc.errors()]
        return JSONResponse(status_code=422, content={"message": "请求字段校验失败：" + ", ".join(fields), "code": "VALIDATION_ERROR"})

    @app.exception_handler(Exception)
    async def unexpected_error(_request, exc):
        logger.error("Unhandled AI service exception: %s", type(exc).__name__)
        return JSONResponse(status_code=500, content={"message": "AI 服务内部错误，请查看服务日志。", "code": "INTERNAL_ERROR"})

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/internal/health", dependencies=[Depends(authenticate)])
    async def internal_health(request: Request):
        runtime=request.app.state.runtime
        active=runtime.cfg
        try:
            await runtime.knowledge.exists()
            vector = "ok"
        except ServiceError:
            vector = "unavailable"
        configured = active.generation_configured and active.embedding_configured and vector == "ok"
        return {"status": "ok" if configured else "degraded", "generationConfigured": active.generation_configured,
                "embeddingConfigured": active.embedding_configured, "vectorStore": vector, "mode": active.mode,
                "statusDetail": "DEMO：离线模板模式，未调用真实模型。" if active.mode == "demo" else "real"}

    @app.get('/internal/model-settings',dependencies=[Depends(authenticate)])
    async def get_model_settings(request:Request):
        return request.app.state.model_settings.view()

    @app.post('/internal/model-settings',dependencies=[Depends(authenticate)])
    async def save_model_settings(body:ModelUpdate,request:Request):
        store=request.app.state.model_settings
        async with store.lock:
            active=store.prepare(body)
            runtime=request.app.state.build_runtime(active)
            store.commit(active)
            request.app.state.publish_runtime(runtime)
            return store.view()

    @app.post("/internal/generate", dependencies=[Depends(authenticate)])
    async def generate(body: GenerateRequest, request: Request):
        try:
            return await asyncio.wait_for(request.app.state.runtime.workflow.run(body), timeout=300)
        except asyncio.TimeoutError as exc:
            raise ServiceError("生成已超过 300 秒执行上限，请稍后重试或选择响应更快的模型。", 504, "GENERATION_TIMEOUT") from exc

    @app.post("/internal/conversation/run", dependencies=[Depends(authenticate)])
    async def conversation(body: ConversationRequest, request: Request):
        agent=request.app.state.runtime.conversation
        async def lines():
            async for item in agent.run(body):
                yield json.dumps(item, ensure_ascii=False, allow_nan=False) + "\n"
        return StreamingResponse(lines(), media_type="application/x-ndjson",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.post("/internal/documents/extract", dependencies=[Depends(authenticate)])
    async def extract(body: ExtractRequest):
        return await asyncio.to_thread(extract_document, body.path, cfg.knowledge_dir)

    @app.post("/internal/conversation/memory", dependencies=[Depends(authenticate)])
    async def memory(body: MemoryRequest, request: Request):
        try:
            return await asyncio.wait_for(summarize(request.app.state.runtime.provider, body), timeout=90)
        except asyncio.TimeoutError as exc:
            raise ServiceError("记忆整理超时，将在稍后继续。", 504, "MEMORY_TIMEOUT") from exc

    @app.post("/internal/knowledge/preview", dependencies=[Depends(authenticate)])
    async def preview_chunks(body: ChunkPreview):
        chunks = chunk_text(body.text, body.chunkSize, body.chunkOverlap)
        return {"chunkCount": len(chunks), "characters": len(body.text),
                "chunks": [{"text": text, "characters": len(text)} for text in chunks[:20]],
                "truncated": len(chunks) > 20}

    @app.post("/internal/knowledge/index", dependencies=[Depends(authenticate)])
    async def index(body: IndexRequest, request: Request):
        try:
            return await asyncio.wait_for(request.app.state.runtime.knowledge.index(body), timeout=300)
        except asyncio.TimeoutError as exc:
            raise ServiceError("知识索引超过 300 秒，请拆分文件后重试。", 504, "INDEX_TIMEOUT") from exc

    @app.post("/internal/knowledge/search", dependencies=[Depends(authenticate)])
    async def search(body: InternalSearchRequest, request: Request):
        return [source.model_dump() for source in await request.app.state.runtime.knowledge.search(body, document_ids=body.documentIds)]

    @app.put("/internal/knowledge/{document_id}", dependencies=[Depends(authenticate)])
    async def update(document_id: str, body: MetadataRequest, request: Request):
        return await request.app.state.runtime.knowledge.update(document_id, body)

    @app.delete("/internal/knowledge/{document_id}", dependencies=[Depends(authenticate)])
    async def delete(document_id: str, request: Request):
        return await request.app.state.runtime.knowledge.delete(document_id)

    return app


app = create_app()

