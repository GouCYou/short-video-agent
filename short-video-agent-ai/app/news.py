"""Bounded public-news discovery and HTML extraction, independent of model credentials."""
import asyncio
import ast
from collections import defaultdict
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
import hashlib
from html import unescape
from html.parser import HTMLParser
import ipaddress
import json
import os
import re
import socket
import time
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser
import xml.etree.ElementTree as ET

import httpx
from pydantic import Field
from .errors import ServiceError
from .models import Schema, Source


class SearchNews(Schema):
    query: str = Field(min_length=2, max_length=300)
    days: int = Field(default=7, ge=1, le=90)
    limit: int = Field(default=6, ge=1, le=8)


class ReadNews(Schema):
    sourceId: str = Field(min_length=1, max_length=100)


HOSTS = set(('chinanews.com.cn people.com.cn xinhuanet.com news.cn cctv.com sina.com.cn '
             '163.com thepaper.cn qq.com sohu.com yicai.com stcn.com ce.cn cnr.cn stdaily.com '
             'gmw.cn cnstock.com eastmoney.com pcauto.com.cn autohome.com.cn bitauto.com '
             'chinadaily.com.cn reuters.com apnews.com bbc.com bbc.co.uk theguardian.com '
             'techcrunch.com arstechnica.com scmp.com cnbc.com cnn.com bing.com gdeltproject.org').split())
UA = 'ZhiyingNewsBot/1.0'
UTC = timezone.utc


def parse_date(value):
    if not value or not isinstance(value, str):
        return None
    try:
        dt = datetime.fromisoformat(value.strip().replace('Z', '+00:00'))
    except ValueError:
        try:
            dt = parsedate_to_datetime(value.strip())
        except (ValueError, TypeError, OverflowError):
            match = re.search(r'(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})日?(?:[ T\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?)?', value)
            if not match:
                return None
            try:
                dt = datetime(*(int(v or 0) for v in match.groups()))
            except ValueError:
                return None
    if dt.tzinfo is None:
        # Default Chinese publisher timestamps are local, never retrieval timestamps.
        dt = dt.replace(tzinfo=timezone(timedelta(hours=8)))
    return dt.astimezone(UTC).isoformat()


class ArticleHTML(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.paragraphs, self.links, self.meta = [], [], {}
        self.ignored, self.paragraph, self.anchor = 0, None, None
        self.ld = None
        self.ld_blocks = []
        self.scripts = []
        self.script_text = None
        self.title = None
        self.headline = ''

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'meta':
            key = (a.get('property') or a.get('name') or '').lower()
            self.meta.setdefault(key, a.get('content', ''))
        if tag == 'time' and a.get('datetime'):
            self.meta.setdefault('article:published_time', a['datetime'])
        if tag == 'script':
            self.script_text = []
            if a.get('type', '').lower() == 'application/ld+json':
                self.ld = []
            self.ignored += 1
        elif tag in ('style', 'nav', 'footer', 'noscript'):
            self.ignored += 1
        if self.ignored:
            return
        if tag == 'p':
            self.paragraph = []
        if tag == 'h1':
            self.title = []
        if tag == 'a' and a.get('href'):
            self.anchor = [a['href'], []]

    def handle_data(self, data):
        if self.script_text is not None:
            self.script_text.append(data[:100000])
        if self.ld is not None:
            self.ld.append(data[:100000])
        if self.ignored:
            return
        if self.paragraph is not None:
            self.paragraph.append(data)
        if self.title is not None:
            self.title.append(data)
        if self.anchor is not None:
            self.anchor[1].append(data)

    def handle_endtag(self, tag):
        if tag == 'script' and self.script_text is not None:
            self.scripts.append(''.join(self.script_text)[:200000])
            self.script_text = None
        if tag == 'script' and self.ld is not None:
            self.ld_blocks.append(''.join(self.ld))
            self.ld = None
        if tag in ('script', 'style', 'nav', 'footer', 'noscript'):
            self.ignored = max(0, self.ignored - 1)
        if tag == 'p' and self.paragraph is not None:
            text = re.sub(r'\s+', ' ', ''.join(self.paragraph)).strip()
            if len(text) >= 18:
                self.paragraphs.append(text)
            self.paragraph = None
        if tag == 'h1' and self.title is not None:
            self.headline = ''.join(self.title).strip()
            self.title = None
        if tag == 'a' and self.anchor is not None:
            text = re.sub(r'\s+', ' ', ''.join(self.anchor[1])).strip()
            if len(text) >= 8:
                self.links.append((self.anchor[0], text))
            self.anchor = None

    def embedded_body(self):
        # Some publishers place static article HTML in a quoted JS variable. Parse only string literals; never execute JS.
        pattern = re.compile(r'''(?:var|let|const)\s+(?:content|cont|articleContent|html)\w*\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')''', re.S | re.I)
        for script in self.scripts:
            for match in pattern.finditer(script):
                try:
                    value = ast.literal_eval(match.group(1))
                except (ValueError, SyntaxError):
                    continue
                if isinstance(value, str) and '<p' in value.lower():
                    article = ArticleHTML(); article.feed(value)
                    text = '\n'.join(dict.fromkeys(article.paragraphs))
                    if len(text) >= 80:
                        return text
        return ''

    def published(self):
        for key in ('article:published_time', 'publishdate', 'pubdate', 'publish_date', 'datepublished', 'date'):
            if value := parse_date(self.meta.get(key)):
                return value
        def find(value):
            if isinstance(value, dict):
                if date := parse_date(value.get('datePublished')):
                    return date
                for item in value.values():
                    if date := find(item):
                        return date
            if isinstance(value, list):
                for item in value:
                    if date := find(item):
                        return date
            return None
        for block in self.ld_blocks:
            try:
                if date := find(json.loads(block)):
                    return date
            except (ValueError, TypeError):
                continue
        return None


class NewsCrawler:
    def __init__(self, client=None, allowed_hosts=None):
        self.client = client
        self.hosts = set(allowed_hosts or HOSTS) | set(filter(None, os.getenv('NEWS_ALLOWED_HOSTS', '').split(',')))
        self.robots = {}
        self.locks = defaultdict(asyncio.Lock)
        self.last_fetch = {}
        self.cache = {}

    @asynccontextmanager
    async def session(self):
        if self.client is not None:
            yield self.client
        else:
            async with httpx.AsyncClient(timeout=10, follow_redirects=False, headers={'User-Agent': UA, 'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.7'}) as client:
                yield client

    async def validate_url(self, value):
        parsed = urlsplit(value)
        host = (parsed.hostname or '').lower().rstrip('.')
        if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.port not in (None, 80, 443):
            raise ServiceError('新闻地址不是可读取的公开网页。', 422, 'NEWS_URL_NOT_ALLOWED')
        if not any(host == root or host.endswith('.' + root) for root in self.hosts):
            raise ServiceError('该来源暂未接入正文读取，保留原文链接供查看。', 422, 'NEWS_HOST_NOT_ALLOWED')
        try:
            addresses = await asyncio.to_thread(socket.getaddrinfo, host, 443, 0, socket.SOCK_STREAM)
            for info in addresses:
                ip = ipaddress.ip_address(info[4][0])
                ip = ip.ipv4_mapped if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped else ip
                # Known publisher HTTPS hosts may use a local TUN fake-IP proxy. Never allow LAN/loopback targets.
                fake_ip = isinstance(ip, ipaddress.IPv4Address) and ip in ipaddress.ip_network('198.18.0.0/15')
                if not ip.is_global and not fake_ip:
                    raise ServiceError('不允许读取本机或内网地址。', 422, 'NEWS_PRIVATE_ADDRESS')
        except socket.gaierror as exc:
            raise ServiceError('新闻来源域名暂时无法解析。', 502, 'NEWS_DNS_FAILED') from exc
        return urlunsplit(('https', host, parsed.path or '/', parsed.query, ''))

    async def fetch(self, client, url, *, robots=True):
        url = await self.validate_url(url)
        for _ in range(5):
            origin = urlunsplit(('https', urlsplit(url).netloc, '', '', ''))
            delay = .25
            if robots:
                cached = self.robots.get(origin)
                if not cached or time.monotonic() - cached[0] > 300:
                    try:
                        raw, _ = await self.fetch(client, origin + '/robots.txt', robots=False)
                        rules = RobotFileParser(); rules.parse(raw.decode('utf-8', errors='replace').splitlines())
                    except (ServiceError, httpx.HTTPError) as error:
                        rules = RobotFileParser()
                        if isinstance(error, ServiceError) and 'HTTP 404' in error.message:
                            rules.parse([])
                        else:
                            rules.disallow_all = True
                    self.robots[origin] = (time.monotonic(), rules)
                rules = self.robots[origin][1]
                if not rules.can_fetch(UA, url):
                    raise ServiceError('来源不允许自动读取正文，保留新闻摘要与原文链接。', 422, 'NEWS_ROBOTS_DENIED')
                delay = max(delay, rules.crawl_delay(UA) or rules.crawl_delay('*') or 0)
                if delay > 10:
                    raise ServiceError('该来源需要较长访问间隔，本次保留摘要。', 422, 'NEWS_RATE_LIMIT')
            async with self.locks[origin]:
                gap = delay - (time.monotonic() - self.last_fetch.get(origin, 0))
                if gap > 0:
                    await asyncio.sleep(gap)
                try:
                    async with client.stream('GET', url, follow_redirects=False, headers={'User-Agent': UA}) as response:
                        if response.is_redirect:
                            target = response.headers.get('location', '')
                            url = await self.validate_url(urljoin(url, target))
                            continue
                        if response.status_code >= 400:
                            raise ServiceError(f'新闻来源返回 HTTP {response.status_code}。', 502, 'NEWS_HTTP_ERROR')
                        chunks, size = [], 0
                        async for part in response.aiter_bytes():
                            size += len(part)
                            if size > 2_000_000:
                                raise ServiceError('新闻页面超过读取大小限制。', 422, 'NEWS_PAGE_TOO_LARGE')
                            chunks.append(part)
                        return b''.join(chunks), url
                finally:
                    self.last_fetch[origin] = time.monotonic()
        raise ServiceError('新闻来源跳转次数过多。', 422, 'NEWS_TOO_MANY_REDIRECTS')

    @staticmethod
    def category(query):
        if re.search(r'汽车|新能源|车企|比亚迪|特斯拉|智驾|买车|驾驶', query, re.I):
            return 'auto'
        if re.search(r'科技|人工智能|\bAI\b|芯片|数码|互联网|手机', query, re.I):
            return 'it'
        if re.search(r'财经|金融|消费|经济|股市|银行|基金', query):
            return 'finance'
        return 'china'

    @staticmethod
    def relevant(query, title, url):
        terms = [word for word in re.split(r'[\s,，、]+', query) if word not in ('热点', '新闻', '最新', '近期')]
        return any(word.casefold() in title.casefold() for word in terms) or ('汽车' in query and '/auto/' in url)

    async def discover(self, client, query, days):
        category = self.category(query)
        candidates, notices = [], []
        async def broadcast_index(page):
            try:
                raw, _ = await self.fetch(client, f'https://news.cctv.com/2019/07/gaiban/cmsdatainterface/page/news_{page}.jsonp', robots=False)
                payload = raw.decode('utf-8', errors='replace').strip()
                match = re.fullmatch(r'[\w.]+\((.*)\)\s*;?', payload, re.S)
                data = json.loads(match.group(1) if match else payload)
                for item in data.get('data', {}).get('list', [])[:100]:
                    title, url = str(item.get('title', '')), str(item.get('url', ''))
                    context = title + ' ' + str(item.get('brief', '')) + ' ' + str(item.get('keywords', ''))
                    if title and url and self.relevant(query, context, url):
                        candidates.append({'title': title[:300], 'url': url, 'publishedAt': parse_date(item.get('focus_date')),
                                           'excerpt': str(item.get('brief', ''))[:2500], 'publisher': '央视网'})
            except (ServiceError, httpx.HTTPError, ValueError, TypeError, AttributeError):
                notices.append('部分媒体新闻目录暂时无法读取')
        await asyncio.gather(broadcast_index(1), broadcast_index(2))
        async def feed(url):
            try:
                raw, _ = await self.fetch(client, url, robots=False)
                if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
                    return
                root = ET.fromstring(raw)
                for item in root.findall('./channel/item')[:50]:
                    title, link = item.findtext('title') or '', item.findtext('link') or ''
                    if title and link and self.relevant(query, title, link):
                        candidates.append({'title': title[:300], 'url': link, 'publishedAt': parse_date(item.findtext('pubDate')),
                                           'excerpt': re.sub('<[^>]+>', '', unescape(item.findtext('description') or ''))[:2500], 'publisher': '中国新闻网'})
            except (ServiceError, httpx.HTTPError, ET.ParseError):
                notices.append('部分公开新闻订阅暂时无法读取')
        await feed(f'https://www.chinanews.com.cn/rss/{category}.xml')
        try:
            request = httpx.URL('https://cn.bing.com/search', params={'q': query + ' 新闻', 'format': 'rss', 'mkt': 'zh-CN'})
            raw, _ = await self.fetch(client, str(request), robots=False)
            if b'<!DOCTYPE' not in raw.upper() and b'<!ENTITY' not in raw.upper():
                for item in ET.fromstring(raw).findall('./channel/item')[:15]:
                    title, link = item.findtext('title') or '', item.findtext('link') or ''
                    if self.relevant(query, title, link):
                        candidates.append({'title': title[:300], 'url': link, 'publishedAt': None,
                                           'excerpt': re.sub('<[^>]+>', '', unescape(item.findtext('description') or ''))[:2500],
                                           'publisher': urlsplit(link).hostname or ''})
        except (ServiceError, httpx.HTTPError, ET.ParseError):
            notices.append('网页新闻检索暂时不可用')
        # The public category page covers publishers whose RSS feed updates less frequently.
        try:
            raw, base = await self.fetch(client, f'https://www.chinanews.com.cn/{category}/')
            html = ArticleHTML(); html.feed(raw.decode('utf-8', errors='replace'))
            for link, title in html.links:
                url = urljoin(base, link)
                if re.search(r'/20\d{2}/\d{2}-\d{2}/\d+\.shtml', url) and self.relevant(query, title, url):
                    candidates.append({'title': title[:300], 'url': url, 'publishedAt': None, 'excerpt': '', 'publisher': '中国新闻网'})
        except (ServiceError, httpx.HTTPError):
            notices.append('新闻栏目页面暂时无法读取')
        # Optional broad discovery; an unavailable public index never becomes invented results.
        if len(candidates) < 3:
            try:
                request = httpx.URL('https://api.gdeltproject.org/api/v2/doc/doc', params={'query': query, 'mode': 'artlist', 'format': 'json', 'maxrecords': '12', 'timespan': f'{days}d'})
                raw, _ = await self.fetch(client, str(request), robots=False)
                for item in json.loads(raw).get('articles', [])[:12]:
                    candidates.append({'title': str(item.get('title', ''))[:300], 'url': item.get('url', ''),
                                       'publishedAt': None, 'excerpt': '', 'publisher': item.get('domain', '')})
            except (ServiceError, httpx.HTTPError, ValueError, TypeError):
                notices.append('公开新闻索引暂时不可用或限流')
        return candidates, list(dict.fromkeys(notices))

    async def article(self, client, item):
        value = dict(item)
        value['contentStatus'], value['content'] = 'SUMMARY', value.get('excerpt', '')
        try:
            raw, final_url = await self.fetch(client, value['url'])
            html = ArticleHTML(); html.feed(raw.decode('utf-8', errors='replace'))
            body = '\n'.join(dict.fromkeys(html.paragraphs))[:7500]
            if len(body) < 80:
                body = html.embedded_body()[:7500]
            value['url'] = final_url
            if html.published():
                value['publishedAt'] = html.published()
            if len(body) >= 80:
                value['content'], value['contentStatus'] = body, 'BODY'
            else:
                value['readNote'] = '未获取到可用正文，仅有标题或摘要'
        except (ServiceError, httpx.HTTPError) as exc:
            value['readNote'] = exc.message if isinstance(exc, ServiceError) else '原文暂时无法连接'
        value['fetchedAt'] = datetime.now(UTC).isoformat()
        return value

    async def search(self, request: SearchNews):
        key = (request.query, request.days, request.limit)
        cached = self.cache.get(key)
        if cached and time.monotonic() - cached[0] < 180:
            return cached[1]
        async with self.session() as client:
            candidates, notices = await self.discover(client, request.query, request.days)
            unique = {}
            cutoff = datetime.now(UTC) - timedelta(days=request.days)
            for item in candidates:
                try:
                    url = await self.validate_url(item['url'])
                except ServiceError:
                    continue
                path_parts = [part for part in urlsplit(url).path.split('/') if part]
                if len(path_parts) < 2 and not re.search(r'\.(?:s?html?|aspx?)$', urlsplit(url).path, re.I):
                    continue
                if item.get('publishedAt') and datetime.fromisoformat(item['publishedAt']) < cutoff:
                    continue
                if not item.get('publishedAt'):
                    dated_path = re.search(r'/(20\d{2})/(\d{2})[/-](\d{2})(?:/|\D)', urlsplit(url).path)
                    if dated_path:
                        try:
                            hinted = datetime(*(int(value) for value in dated_path.groups()), tzinfo=UTC)
                            if hinted.date() < cutoff.date():
                                continue
                        except ValueError:
                            continue
                normalized_title = re.sub(r'\W', '', item['title']).casefold()
                if url not in unique and normalized_title not in {re.sub(r'\W', '', v['title']).casefold() for v in unique.values()}:
                    unique[url] = dict(item, url=url)
            semaphore = asyncio.Semaphore(3)
            async def read(item):
                async with semaphore:
                    return await self.article(client, item)
            articles = await asyncio.gather(*(read(item) for item in list(unique.values())[:request.limit * 2]))
        articles = [item for item in articles if not item.get('publishedAt') or cutoff <= datetime.fromisoformat(item['publishedAt']) <= datetime.now(UTC) + timedelta(days=1)]
        articles.sort(key=lambda item: item.get('publishedAt') or '', reverse=True)
        articles = articles[:request.limit]
        sources = []
        for item in articles:
            identifier = 'web-' + hashlib.sha256(item['url'].encode()).hexdigest()[:24]
            item['id'] = identifier
            date = item.get('publishedAt') or '未确认'
            text = (f'新闻标题：{item["title"]}\n发布来源：{item["publisher"]}\n发布时间：{date}\n抓取时间：{item["fetchedAt"]}\n'
                    f'证据范围：{"网页正文" if item["contentStatus"] == "BODY" else "标题或摘要"}\n{item["content"]}')
            source = Source(documentId=identifier, documentName=item['title'], chunkId=identifier, text=text[:10000], score=0.0, sourceUrl=item['url'])
            sources.append(source.model_dump())
        result = {'query': request.query, 'days': request.days, 'matchCount': len(articles), 'sources': sources,
                  'articles': [{k: v for k, v in item.items() if k not in ('content', 'excerpt')} for item in articles],
                  'notices': notices, 'factVerified': False, 'searchedAt': datetime.now(UTC).isoformat()}
        self.cache[key] = (time.monotonic(), result)
        if len(self.cache) > 100:
            self.cache.pop(next(iter(self.cache)))
        return result
