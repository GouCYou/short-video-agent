"""Private, atomic model configuration. Public views contain only a masked secret."""
import asyncio
import json
import os
import tempfile
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from pydantic import Field, field_validator
from .models import Schema
from .errors import ServiceError


class ModelUpdate(Schema):
    model: str = Field(min_length=1, max_length=200)
    baseUrl: str = Field(min_length=1, max_length=2000)
    apiKey: str | None = Field(default=None, max_length=2000)
    revision: int = Field(ge=0)

    @field_validator('model')
    @classmethod
    def model_name(cls, value):
        if any(ord(c)<33 for c in value): raise ValueError('invalid model')
        return value

    @field_validator('baseUrl')
    @classmethod
    def base_url(cls, value):
        value=value.rstrip('/')
        try:
            url=urlsplit(value)
            if url.scheme not in {'http','https'} or not url.hostname or url.username or url.password or url.query or url.fragment or any(c.isspace() for c in value): raise ValueError()
            _=url.port
            if url.path.endswith('/chat/completions'): raise ValueError()
        except ValueError:
            raise ValueError('invalid base URL') from None
        return value

    @field_validator('apiKey')
    @classmethod
    def secret(cls,value):
        if value and (any(ord(c)<33 or ord(c)>126 for c in value) or '*' in value or '•' in value): raise ValueError('invalid key')
        return value or None


def mask_key(key):
    if not key: return ''
    return key[:6]+'••••••••'+key[-4:] if len(key)>=16 else '••••••••'


class ModelSettings:
    def __init__(self, cfg):
        self.cfg=cfg
        self.path=Path(cfg.model_settings_path) if cfg.model_settings_path else None
        self.revision=0; self.updated_at=None; self.lock=asyncio.Lock()
        if self.path and self.path.exists():
            try:
                saved=json.loads(self.path.read_text())
                value=ModelUpdate.model_validate(saved['config'])
                if not value.apiKey: raise ValueError()
                self.cfg=replace(cfg,generation_model=value.model,generation_base_url=value.baseUrl,generation_api_key=value.apiKey)
                self.revision=value.revision;self.updated_at=saved.get('updatedAt')
                self.path.chmod(0o600)
            except (OSError,ValueError,KeyError,TypeError):
                raise RuntimeError('Saved model settings could not be loaded') from None

    def view(self):
        return {'model':self.cfg.generation_model,'baseUrl':self.cfg.generation_base_url,'maskedKey':mask_key(self.cfg.generation_api_key),
                'hasKey':bool(self.cfg.generation_api_key),'revision':self.revision,'updatedAt':self.updated_at,'format':'OpenAI-compatible'}

    def prepare(self, value):
        if value.revision!=self.revision: raise ServiceError('配置已被更新，请刷新后重试。',409,'MODEL_CONFIG_CONFLICT')
        old=urlsplit(self.cfg.generation_base_url);new=urlsplit(value.baseUrl)
        if (old.scheme,old.netloc)!=(new.scheme,new.netloc) and self.cfg.generation_api_key and not value.apiKey:
            raise ServiceError('调用地址的域名或协议变化时，请同时填写对应的新密钥。',422,'MODEL_KEY_REQUIRED')
        key=value.apiKey or self.cfg.generation_api_key
        if not key: raise ServiceError('请填写 API 密钥。',422,'MODEL_KEY_REQUIRED')
        return replace(self.cfg,generation_model=value.model,generation_base_url=value.baseUrl,generation_api_key=key)

    def commit(self, cfg):
        updated=datetime.now(timezone.utc).isoformat();revision=self.revision+1
        if self.path:
            temp=None
            try:
                self.path.parent.mkdir(parents=True,exist_ok=True)
                fd,temp=tempfile.mkstemp(prefix='.model-settings-',dir=self.path.parent)
                with os.fdopen(fd,'w') as f:
                    json.dump({'config':{'model':cfg.generation_model,'baseUrl':cfg.generation_base_url,'apiKey':cfg.generation_api_key,'revision':revision},'updatedAt':updated},f)
                    f.flush();os.fsync(f.fileno())
                os.replace(temp,self.path)
            except OSError:
                if temp:
                    try: os.unlink(temp)
                    except OSError: pass
                raise ServiceError('配置保存失败，原配置仍然有效。',503,'MODEL_CONFIG_SAVE_FAILED') from None
        self.cfg=cfg;self.revision=revision;self.updated_at=updated
