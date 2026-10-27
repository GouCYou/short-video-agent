from unittest.mock import AsyncMock
import httpx
import pytest
from fastapi.testclient import TestClient
from app.main import create_app
from app.search_intent import search_intent_rule
from test_conversation import ScriptedProvider,response,call,settings,ndjson,body
from test_news import source

@pytest.mark.parametrize('text',[
 '换个其他的','帮我把这个方案改成更自然的口语','不要搜索，直接用刚才的资料','没让你搜，为什么又去搜了','帮我改写搜索关键词','搜索知识库里的拍摄方法','把脚本缩短一点','只换分镜和配音，不用再搜','你好',
])
def test_no_web_permission_for_revisions_or_denial(text):
 assert search_intent_rule(text) is False

@pytest.mark.parametrize('text',['帮我搜索汽车新闻','我要做一条视频，你帮我搜一下Anthropic上市的新闻','找一下汽车新闻','重新搜一轮','不要改稿，先搜索相关新闻','给我最近汽车新闻'])
def test_explicit_current_news_requests_are_allowed(text):
 assert search_intent_rule(text) is True

@pytest.mark.parametrize('text',['换个其他的','这段太正式了，改自然点','不要再搜索，直接改写'])
def test_unrequested_search_is_blocked_before_any_crawler_or_visible_search_event(text):
 provider=ScriptedProvider([response(calls=[call('search_news',{'query':'旧主题'})]),response(text='好的，我根据现有资料调整表达方向。')])
 with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as client:
  crawler=client.app.state.conversation.news.search=AsyncMock()
  frames=ndjson(client,body(text))
  crawler.assert_not_called()
 assert frames[-1]['type']=='final'
 assert not any(f['type'].startswith('tool.') and f['data'].get('tool')=='search_news' for f in frames)


def test_selected_card_text_does_not_implicitly_authorize_search():
 provider=ScriptedProvider([response(calls=[call('search_news',{'query':'旧主题'})]),response(text='按照所选方向继续讨论。')])
 with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as client:
  crawler=client.app.state.conversation.news.search=AsyncMock()
  frames=ndjson(client,body('我选择 A：搜索融资相关新闻',selection={'kind':'DIRECTION','value':'搜索融资相关新闻'}))
  crawler.assert_not_called()
 assert frames[-1]['type']=='final'


def test_ambiguous_followup_uses_current_semantics_once_before_searching():
 provider=ScriptedProvider([response(calls=[call('search_news',{'query':'汽车'})]),response(text='找到相关新闻。')],[{'allowed':True}])
 with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as client:
  crawler=client.app.state.conversation.news.search=AsyncMock(return_value={'matchCount':1,'sources':[source()],'articles':[],'notices':[],'factVerified':False})
  frames=ndjson(client,body('可以继续',messages=[{'role':'user','content':'了解汽车新闻'},{'role':'assistant','content':'要我继续搜索更多相关新闻吗？'},{'role':'user','content':'可以继续'}]))
  crawler.assert_awaited_once()
 assert frames[-1]['type']=='final'


def test_semantic_denial_blocks_ambiguous_discovery_and_removes_search_from_remaining_turn():
 provider=ScriptedProvider([response(calls=[call('search_news',{'query':'旧主题'})]),response(text='我们就现有内容继续交流。')],[{'allowed':False}])
 with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as client:
  crawler=client.app.state.conversation.news.search=AsyncMock()
  frames=ndjson(client,body('就按现在的信息聊一聊'))
  crawler.assert_not_called()
 assert frames[-1]['type']=='final'
 assert 'search_news' not in [tool['function']['name'] for tool in provider.requests[-1]['tools']]
