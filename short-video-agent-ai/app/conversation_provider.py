"""Streaming OpenAI-compatible chat adapter. Reasoning fields are never emitted."""
import asyncio
import json
import re
import httpx
from .errors import ServiceError, model_http_error, retry_delay
from .providers import Provider
from .identity import IdentityOutputFilter


class ToolsUnsupported(Exception):
    """Only an explicit provider rejection of tools permits action fallback."""


class AnswerFilter:
    """Suppress providers that place think/analysis tags inside content deltas."""
    def __init__(self):
        self.pending = ""
        self.hidden = None

    def feed(self, text, final=False):
        self.pending += text
        out = ""
        while self.pending:
            if self.hidden:
                match = re.search(r"</" + self.hidden + r"\s*>", self.pending, re.I)
                if not match:
                    self.pending = self.pending[-20:]
                    break
                self.pending = self.pending[match.end():]
                self.hidden = None
                continue
            match = re.search(r"<(think|analysis)\s*>", self.pending, re.I)
            if match:
                out += self.pending[:match.start()]
                self.hidden = match.group(1).lower()
                self.pending = self.pending[match.end():]
                continue
            if final:
                out += self.pending
                self.pending = ""
            elif len(self.pending) > 20:
                out += self.pending[:-20]
                self.pending = self.pending[-20:]
            break
        return out


def explicit_tools_unsupported(status, body):
    if status not in {400, 404, 422, 501}:
        return False
    try:
        err = json.loads(body).get("error", {})
        if not isinstance(err, dict):
            return False
        message = str(err.get("message", "")).lower()
        param = str(err.get("param", "")).lower()
        code = str(err.get("code", "")).lower()
        names_tool = param in {"tools", "tool_choice", "functions", "function_call"} or bool(re.search(r"\btools?\b|function.call", message))
        rejected = any(word in message + " " + code for word in ("unsupported", "not supported", "does not support", "not support", "not implemented", "unrecognized", "unknown parameter"))
        return names_tool and rejected
    except (ValueError, AttributeError):
        return False


class ConversationProvider:
    def __init__(self, provider: Provider):
        self.provider, self.cfg, self.client = provider, provider.settings, provider.client

    async def turn(self, messages, tools=None):
        """Yield actual content deltas, then one validated assistant message."""
        cfg = self.cfg
        payload = {"model": cfg.generation_model, "messages": messages, "temperature": 0.5,
                   "max_tokens": 7000, "stream": True}
        if tools:
            payload.update({"tools": tools, "tool_choice": "auto"})
        for attempt in range(cfg.retries + 1):
            started = False
            try:
                async with self.client.stream("POST", cfg.generation_base_url + "/chat/completions",
                                              headers={"Authorization": "Bearer " + cfg.generation_api_key}, json=payload) as response:
                    if response.status_code >= 400:
                        raw = (await response.aread())[:20_000].decode(errors="replace")
                        if tools and explicit_tools_unsupported(response.status_code, raw):
                            raise ToolsUnsupported()
                        if response.status_code in {429, 502, 503, 504} and attempt < cfg.retries:
                            await asyncio.sleep(retry_delay(attempt, response))
                            continue
                        raise model_http_error(response.status_code, raw, attempt)
                    answer = ""
                    calls = {}
                    usage = {"inputTokens": 0, "outputTokens": 0, "model": cfg.generation_model}
                    filter = AnswerFilter()
                    identity = IdentityOutputFilter()
                    finished = False
                    if "text/event-stream" in response.headers.get("content-type", ""):
                        async for line in response.aiter_lines():
                            if not line.startswith("data:"):
                                continue
                            data = line[5:].strip()
                            if data == "[DONE]":
                                finished = True
                                break
                            if not data:
                                continue
                            try:
                                packet = json.loads(data)
                            except ValueError as exc:
                                raise ServiceError("生成服务流返回无效 JSON。", 502, "INVALID_MODEL_STREAM") from exc
                            if packet.get("error"):
                                raise ServiceError("生成服务在流输出过程中返回错误，请重试。", 502, "MODEL_STREAM_ERROR")
                            tokens = packet.get("usage") or {}
                            if tokens:
                                usage["inputTokens"] = max(0, int(tokens.get("prompt_tokens", 0)))
                                usage["outputTokens"] = max(0, int(tokens.get("completion_tokens", 0)))
                            choices = packet.get("choices") or []
                            if not choices:
                                continue
                            choice = choices[0]
                            delta = choice.get("delta") or {}
                            if choice.get("finish_reason") in {"stop", "tool_calls", "function_call"}:
                                finished = True
                            if choice.get("finish_reason") == "length":
                                raise ServiceError("模型输出超过长度限制，请缩小本轮创作范围。", 502, "MODEL_OUTPUT_TRUNCATED")
                            # Deliberately ignore reasoning/reasoning_content and every non-content field.
                            piece = delta.get("content")
                            if isinstance(piece, str):
                                safe = identity.feed(filter.feed(piece))
                                if safe:
                                    started = True
                                    answer += safe
                                    yield {"kind": "delta", "text": safe}
                            for part in delta.get("tool_calls") or []:
                                index = part.get("index", 0)
                                if not isinstance(index, int) or index < 0 or index >= 16:
                                    raise ServiceError("模型工具调用索引无效。", 502, "INVALID_TOOL_CALL")
                                target = calls.setdefault(index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                                if part.get("id"):
                                    target["id"] = part["id"]
                                function = part.get("function") or {}
                                for name in ("name", "arguments"):
                                    if function.get(name):
                                        target["function"][name] += function[name]
                                if len(target["function"]["arguments"]) > 100_000:
                                    raise ServiceError("模型工具参数超过限制。", 502, "INVALID_TOOL_CALL")
                            if len(answer) > 40_000:
                                raise ServiceError("模型回复超过长度限制。", 502, "INVALID_MODEL_STREAM")
                    else:
                        packet = await response.aread()
                        try:
                            result = json.loads(packet)
                            choice = result["choices"][0]
                            message = choice["message"]
                            answer = identity.feed(filter.feed(message.get("content") or "", final=True), final=True)
                            calls = {i: value for i, value in enumerate(message.get("tool_calls") or [])}
                            tokens = result.get("usage") or {}
                            usage.update(inputTokens=max(0, int(tokens.get("prompt_tokens", 0))), outputTokens=max(0, int(tokens.get("completion_tokens", 0))))
                            finished = choice.get("finish_reason", "stop") in {"stop", "tool_calls"}
                        except (ValueError, KeyError, IndexError, TypeError) as exc:
                            raise ServiceError("生成服务未返回有效的对话回复。", 502, "INVALID_MODEL_OUTPUT") from exc
                        if answer:
                            started = True
                            yield {"kind": "delta", "text": answer}
                    tail = identity.feed(filter.feed("", final=True), final=True)
                    if tail:
                        answer += tail
                        started = True
                        yield {"kind": "delta", "text": tail}
                    if not finished:
                        raise ServiceError("生成服务连接提前结束，请重试。", 502, "INCOMPLETE_MODEL_STREAM")
                    normalized = list(calls.values())
                    for call in normalized:
                        if not isinstance(call.get("id"), str) or not call["id"] or not call.get("function", {}).get("name"):
                            raise ServiceError("模型工具调用缺少名称或标识。", 502, "INVALID_TOOL_CALL")
                    yield {"kind": "done", "message": {"role": "assistant", "content": answer or None,
                                                         **({"tool_calls": normalized} if normalized else {})}, "usage": usage}
                    return
            except ToolsUnsupported:
                raise
            except (httpx.TimeoutException, httpx.NetworkError) as exc:
                # Retrying after emitted text would duplicate output and side effects.
                if started or attempt == cfg.retries:
                    raise ServiceError("生成服务连接失败或超时，请检查网络后重试。", 503, "MODEL_UNAVAILABLE") from exc
                await asyncio.sleep(retry_delay(attempt))
