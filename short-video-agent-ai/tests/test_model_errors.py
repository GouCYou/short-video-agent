import json
from app.errors import model_http_error


def test_free_campaign_limit_is_actionable_and_does_not_expose_provider_body():
    error = model_http_error(429, json.dumps({'error': {'code': 'free_campaign_global_limit', 'message': 'private request and key'}}))
    assert error.code == 'MODEL_FREE_GLOBAL_LIMIT'
    assert '全局限流' in error.message
    assert 'private' not in error.message and '密钥' not in error.message


def test_other_limits_and_auth_errors_are_distinguished():
    assert model_http_error(429, '<html/>').code == 'MODEL_RATE_LIMITED'
    assert model_http_error(401).code == 'MODEL_AUTH_ERROR'
    assert model_http_error(502).code == 'MODEL_HTTP_ERROR'

import httpx
import pytest
from unittest.mock import AsyncMock
from dataclasses import replace
from app.providers import Provider, request_json
from app.conversation_provider import ConversationProvider
from app.errors import ServiceError, retry_delay
from test_conversation import settings


@pytest.mark.asyncio
@pytest.mark.parametrize('streaming', [False, True])
@pytest.mark.parametrize('recovers', [False, True])
async def test_transient_failure_retries_before_success_or_final_error(monkeypatch, streaming, recovers):
    sleep = AsyncMock()
    monkeypatch.setattr('asyncio.sleep', sleep)
    calls = []
    def serve(request):
        calls.append(request)
        if not recovers or len(calls) < 5:
            return httpx.Response(429, headers={'Retry-After': '3'}, json={'error': {'code': 'free_campaign_global_limit'}})
        return httpx.Response(200, json={'choices': [{'message': {'content': '成功'}, 'finish_reason': 'stop'}]})
    cfg = replace(settings(), retries=4)
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as client:
        async def execute():
            if streaming:
                return [item async for item in ConversationProvider(Provider(cfg, client)).turn([{'role': 'user', 'content': '你好'}])]
            return await request_json(client, 'POST', 'https://models.test/chat', retries=4)
        if recovers:
            result = await execute()
            if streaming:
                assert result[-1]['kind'] == 'done'
                assert ''.join(x['text'] for x in result if x['kind'] == 'delta') == '成功'
        else:
            with pytest.raises(ServiceError, match='已自动重试 4 次'):
                await execute()
    assert len(calls) == 5
    assert [call.args[0] for call in sleep.await_args_list] == [3, 4, 8, 16]


def test_retry_after_is_bounded_and_invalid_header_uses_backoff():
    assert retry_delay(0, httpx.Response(429, headers={'Retry-After': '10'})) == 10
    assert retry_delay(0, httpx.Response(429, headers={'Retry-After': 'invalid'})) == 2
    assert retry_delay(0, httpx.Response(429, headers={'Retry-After': '99999'})) == 60
