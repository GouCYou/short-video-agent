"""Environment-only settings. Model and embedding credentials are independent."""
import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    mode: str = "real"
    internal_key: str = ""
    backend_internal_url: str = "http://127.0.0.1:8080"
    generation_api_key: str = ""
    generation_base_url: str = ""
    generation_model: str = ""
    embedding_api_key: str = ""
    embedding_base_url: str = ""
    embedding_model: str = ""
    qdrant_url: str = "http://localhost:6333"
    qdrant_api_key: str = ""
    qdrant_collection: str = "short_video_knowledge"
    knowledge_dir: Path = Path("/data/knowledge")
    timeout: float = 120
    retries: int = 2
    retrieval_min_score: float = 0.2
    chunk_size: int = 700
    chunk_overlap: int = 100

    @classmethod
    def from_env(cls):
        mode = os.getenv("AI_MODE", "real").strip().lower()
        if mode not in {"real", "demo"}:
            raise ValueError("AI_MODE must be real or demo")
        return cls(
            mode=mode,
            internal_key=os.getenv("AI_INTERNAL_KEY", ""),
            backend_internal_url=os.getenv("BACKEND_INTERNAL_URL", "http://127.0.0.1:8080").rstrip("/"),
            generation_api_key=os.getenv("GENERATION_API_KEY", ""),
            generation_base_url=os.getenv("GENERATION_BASE_URL", "").rstrip("/"),
            generation_model=os.getenv("GENERATION_MODEL", ""),
            embedding_api_key=os.getenv("EMBEDDING_API_KEY", ""),
            embedding_base_url=os.getenv("EMBEDDING_BASE_URL", "").rstrip("/"),
            embedding_model=os.getenv("EMBEDDING_MODEL", ""),
            qdrant_url=os.getenv("QDRANT_URL", "http://localhost:6333").rstrip("/"),
            qdrant_api_key=os.getenv("QDRANT_API_KEY", ""),
            qdrant_collection=os.getenv("QDRANT_COLLECTION", "short_video_knowledge"),
            knowledge_dir=Path(os.getenv("KNOWLEDGE_DIR", "/data/knowledge")).resolve(),
            timeout=max(5, min(float(os.getenv("AI_HTTP_TIMEOUT_SECONDS", "120")), 300)),
            retries=max(0, min(int(os.getenv("AI_HTTP_RETRIES", "2")), 3)),
            retrieval_min_score=max(-1, min(float(os.getenv("RETRIEVAL_MIN_SCORE", "0.2")), 1)),
        )

    @property
    def generation_configured(self):
        return self.mode == "demo" or all((self.generation_api_key, self.generation_base_url, self.generation_model))

    @property
    def embedding_configured(self):
        return self.mode == "demo" or all((self.embedding_api_key, self.embedding_base_url, self.embedding_model))

    @property
    def collection(self):
        # Demo vectors must never contaminate a real embedding space.
        return self.qdrant_collection + ("_demo" if self.mode == "demo" else "")

