from datetime import datetime,timedelta,timezone
from email.utils import format_datetime
import socket
from unittest.mock import AsyncMock
import httpx
import pytest
from fastapi.testclient import TestClient
from app.news import ArticleHTML,NewsCrawler,SearchNews,parse_date
from app.errors import ServiceError
from app.main import create_app
from test_conversation import ScriptedProvider,response,call,settings,ndjson,body

@pytest.fixture(autouse=True)
def public_dns(monkeypatch):
    monkeypatch.setattr(socket,'getaddrinfo',lambda *a,**kw:[(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',443))])

def test_publication_date_uses_original_date_not_last_modified():
    assert parse_date('2026年10月26日 15:20')=='2026-10-26T07:20:00+00:00'
    assert parse_date('Mon, 26 Oct 2026 15:20:00 +0800')=='2026-10-26T07:20:00+00:00'
    assert parse_date('not a date') is None
    h=ArticleHTML();h.feed('<meta property="article:modified_time" content="2026-10-27"><script type="application/ld+json">{"datePublished":"2026-10-25T09:00:00+08:00"}</script><p>公开报道的正文内容，这里的内容确实来自实际网页响应。</p>')
    assert h.published()=='2026-10-25T01:00:00+00:00' and h.paragraphs

def test_embedded_article_is_read_as_literal_without_executing_script():
    h = ArticleHTML()
    paragraph = '公开新闻中的实际报道文字。' * 12
    h.feed('<script>var cont = ' + repr('<p>' + paragraph + '</p>') + '; throw new Error("never execute");</script>')
    assert h.embedded_body() == paragraph

def test_model_discovery_excerpt_preserves_full_durable_snapshot():
    from app.conversation import model_tool_result
    original = {'ok': True, 'sources': [{'text': '发布时间：2026-10-27\n' + '正文' * 4000, 'chunkId': 'web-one'}]}
    bounded = model_tool_result('search_news', original)
    assert len(bounded['sources'][0]['text']) == 1800
    assert len(original['sources'][0]['text']) > 8000
    assert bounded['sources'][0]['chunkId'] == 'web-one'
    assert model_tool_result('read_news', original) is original

@pytest.mark.asyncio
async def test_reads_response_bodies_deduplicates_excludes_stale_and_caches():
    now=datetime.now(timezone.utc)-timedelta(hours=2);old=now-timedelta(days=100);seen=[]
    def serve(r):
        assert 'authorization' not in r.headers
        seen.append(str(r.url));path=r.url.path
        if path=='/robots.txt':return httpx.Response(200,text='User-agent: *\nAllow: /')
        if path.endswith('.xml'):
            items=''.join(f'<item><title>汽车新闻{i}</title><link>https://www.chinanews.com.cn/auto/2026/10-26/{i}.shtml</link><pubDate>{format_datetime(old if i==4 else now)}</pubDate></item>' for i in [1,1,2,3,4])
            return httpx.Response(200,text=f'<rss><channel>{items}</channel></rss>')
        if r.url.host=='cn.bing.com':return httpx.Response(200,text='<rss><channel/></rss>')
        if path.endswith('.shtml'):return httpx.Response(200,text=f'<meta name="pubdate" content="{now.isoformat()}"><p>{"这是公开新闻正文，包含真实返回的报道信息。"*6}</p>')
        return httpx.Response(200,text='<html/>')
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as c:
        crawler=NewsCrawler(c);request=SearchNews(query='汽车',days=7,limit=3);result=await crawler.search(request)
        assert result['matchCount']==3
        assert all(a['publishedAt']==now.isoformat() and a['contentStatus']=='BODY' for a in result['articles'])
        assert not any('/4.shtml' in u for u in seen)
        n=len(seen);assert await crawler.search(request)==result and len(seen)==n

@pytest.mark.asyncio
async def test_robots_denial_preserves_summary_and_unknown_date():
    seen=[]
    def serve(r):seen.append(r.url.path);return httpx.Response(200,text='User-agent: *\nDisallow: /')
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as c:
        a=await NewsCrawler(c).article(c,{'title':'汽车报道','url':'https://www.chinanews.com.cn/auto/story.shtml','publisher':'中新网','excerpt':'原始摘要','publishedAt':None})
    assert a['contentStatus']=='SUMMARY' and a['publishedAt'] is None
    assert a['content']=='原始摘要' and '/auto/story.shtml' not in seen

@pytest.mark.asyncio
async def test_redirects_and_dns_cannot_reach_private_services(monkeypatch):
    seen=[]
    def serve(r):seen.append(str(r.url));return httpx.Response(302,headers={'location':'http://127.0.0.1:18080/api/auth/me'})
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as c:
        crawler=NewsCrawler(c)
        with pytest.raises(ServiceError):await crawler.fetch(c,'https://www.chinanews.com.cn/link',robots=False)
        assert len(seen)==1
        monkeypatch.setattr(socket,'getaddrinfo',lambda *a,**kw:[(socket.AF_INET,socket.SOCK_STREAM,6,'',('192.168.1.2',443))])
        with pytest.raises(ServiceError,match='内网'):await crawler.validate_url('https://www.chinanews.com.cn/story')
        with pytest.raises(ServiceError):await crawler.validate_url('https://www.chinanews.com.cn@127.0.0.1/')

@pytest.mark.asyncio
async def test_known_https_publisher_supports_tun_fake_dns(monkeypatch):
    monkeypatch.setattr(socket,'getaddrinfo',lambda *a,**kw:[(socket.AF_INET,socket.SOCK_STREAM,6,'',('198.18.0.20',443))])
    c=NewsCrawler();assert await c.validate_url('https://www.chinanews.com.cn/auto/')=='https://www.chinanews.com.cn/auto/'
    with pytest.raises(ServiceError):await c.validate_url('https://unknown.example/')

def source(date='2026-10-26T07:20:00+00:00'):
    return {'documentId':'web-news-one','documentName':'汽车行业新报道','chunkId':'web-news-one','text':f'新闻标题：汽车行业新报道\n发布来源：公开媒体\n发布时间：{date}\n抓取时间：2026-10-27T04:00:00Z\n证据范围：网页正文\n原文内容。','score':0.0,'sourceUrl':'https://www.chinanews.com.cn/auto/story.shtml'}

def test_chat_includes_original_publication_date_even_when_model_omits_it():
    provider=ScriptedProvider([response(calls=[call('search_news',{'query':'汽车','days':7,'limit':3})]),response(text='找到一条新闻，我们可以讨论表达角度。')])
    with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as c:
        c.app.state.conversation.news.search=AsyncMock(return_value={'matchCount':1,'sources':[source()],'articles':[],'notices':[],'factVerified':False})
        frames=ndjson(c,body('找一下汽车新闻，先讨论选题'))
    assert frames[-1]['type']=='final'
    answer=frames[-1]['data']['content'];assert '2026年10月26日 15:20' in answer and '2026年10月27日' not in answer
    assert ''.join(f['data']['text'] for f in frames if f['type']=='message.delta')==answer
    assert any(f['type']=='tool.completed' and f['data']['tool']=='search_news' for f in frames)
    assert not any(f['type']=='artifact.updated' for f in frames)

def test_followup_reads_saved_news_and_admits_missing_publication_date():
    provider=ScriptedProvider([response(calls=[call('read_news',{'sourceId':'web-news-one'})]),response(text='这条报道可以作为讨论材料。')])
    with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as c:
        frames=ndjson(c,body('读一下刚才那条新闻',researchSources=[source('未确认')]))
    assert frames[-1]['type']=='final' and '发布时间未确认' in frames[-1]['data']['content']
    assert '2026年10月27日' not in frames[-1]['data']['content']

def test_first_creative_news_summary_ends_with_real_choice_cards():
    options = {'kind':'DIRECTION','title':'选择表达角度','options':[
        {'label':label,'title':title,'description':title+'的内容切入点','value':title}
        for label,title in zip('ABC',['普通车主怎么看','技术有哪些边界','实际应用在哪里'])]}
    provider=ScriptedProvider([response(calls=[call('search_news',{'query':'汽车'})]),response(text='报道已总结，原始发布时间如下。')], [options])
    with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as c:
        c.app.state.conversation.news.search=AsyncMock(return_value={'matchCount':1,'sources':[source()],'articles':[],'notices':[],'factVerified':False})
        frames=ndjson(c,body('我要做一个汽车新闻视频，帮我搜一下并总结，不要直接生成完整作品'))
    final=frames[-1];assert final['type']=='final'
    group=final['data']['choices'][0]
    assert [o['label'] for o in group['options']]==list('ABC')
    assert group['status']=='OPEN' and group['kind']=='DIRECTION'
    assert any(f['type']=='tool.completed' and f['data']['tool']=='propose_options' for f in frames)
    assert not any(f['type']=='artifact.updated' for f in frames)


def test_pure_news_query_does_not_force_creative_choices():
    provider=ScriptedProvider([response(calls=[call('search_news',{'query':'汽车'})]),response(text='这是新闻总结。')])
    with TestClient(create_app(settings(),transport=httpx.MockTransport(provider))) as c:
        c.app.state.conversation.news.search=AsyncMock(return_value={'matchCount':1,'sources':[source()],'articles':[],'notices':[],'factVerified':False})
        frames=ndjson(c,body('帮我查一下汽车新闻并总结'))
    assert frames[-1]['type']=='final' and frames[-1]['data']['choices']==[]
