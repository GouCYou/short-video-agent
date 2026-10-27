import json
import stat
from dataclasses import replace
import httpx
from fastapi.testclient import TestClient
from app.main import create_app
from app.model_settings import mask_key
from test_conversation import settings,HEADERS,body,response


def test_mask_never_reveals_short_or_complete_secrets():
    assert mask_key('short-secret')=='••••••••'
    assert mask_key('sk-old-private-key-123456')=='sk-old••••••••3456'


def test_hot_save_uses_new_model_and_key_without_mutating_existing_snapshot_and_survives_restart(tmp_path):
    path=tmp_path/'model-settings.json';cfg=replace(settings(),model_settings_path=path)
    seen=[]
    def serve(request):
        seen.append((json.loads(request.content)['model'],str(request.url),request.headers['authorization']))
        return response(text='新模型的实际回复。')
    with TestClient(create_app(cfg,transport=httpx.MockTransport(serve))) as client:
        old=client.app.state.runtime
        view=client.get('/internal/model-settings',headers=HEADERS).json()
        assert cfg.generation_api_key not in json.dumps(view)
        updated=client.post('/internal/model-settings',headers=HEADERS,json={'model':'new-model','baseUrl':'https://new.example/v1','apiKey':'sk-new-private-token-987654','revision':0})
        assert updated.status_code==200 and updated.json()['revision']==1
        assert 'apiKey' not in updated.json() and 'sk-new-private-token-987654' not in updated.text
        assert old.cfg.generation_model=='conversation-model'
        assert client.app.state.runtime.cfg.generation_model=='new-model'
        result=client.post('/internal/conversation/run',headers=HEADERS,json=body('你好'))
        assert result.status_code==200 and seen[-1]==('new-model','https://new.example/v1/chat/completions','Bearer sk-new-private-token-987654')
        retained=client.post('/internal/model-settings',headers=HEADERS,json={'model':'new-model-v2','baseUrl':'https://new.example/v1','revision':1})
        assert retained.status_code==200
        assert client.app.state.runtime.cfg.generation_api_key=='sk-new-private-token-987654'
    assert stat.S_IMODE(path.stat().st_mode)==0o600
    with TestClient(create_app(cfg,transport=httpx.MockTransport(serve))) as client:
        view=client.get('/internal/model-settings',headers=HEADERS).json()
        assert view['model']=='new-model-v2' and view['revision']==2
        assert client.app.state.runtime.cfg.generation_api_key=='sk-new-private-token-987654'


def test_private_api_validation_conflict_and_persist_failure_preserve_existing_config(tmp_path,monkeypatch):
    cfg=replace(settings(),model_settings_path=tmp_path/'private.json')
    with TestClient(create_app(cfg)) as client:
        assert client.get('/internal/model-settings').status_code==401
        original=client.app.state.runtime
        payload={'model':'new-model','baseUrl':'http://models.test/v1','revision':0}
        assert client.post('/internal/model-settings',headers=HEADERS,json={**payload,'baseUrl':'https://other.example/v1'}).status_code==422
        assert client.post('/internal/model-settings',headers=HEADERS,json={**payload,'revision':9}).status_code==409
        for url in ['https://user:secret@example.com/v1','https://example.com/v1?key=secret','https://example.com/v1/chat/completions','file:///tmp/key']:
            result=client.post('/internal/model-settings',headers=HEADERS,json={**payload,'baseUrl':url})
            assert result.status_code==422 and 'secret' not in result.text
        def failed(*args,**kwargs): raise OSError('private diagnostic')
        monkeypatch.setattr('app.model_settings.os.replace',failed)
        result=client.post('/internal/model-settings',headers=HEADERS,json=payload)
        assert result.status_code==503 and 'private diagnostic' not in result.text
        assert client.app.state.runtime is original and client.app.state.model_settings.revision==0
