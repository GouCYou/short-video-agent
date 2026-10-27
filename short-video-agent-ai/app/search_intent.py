"""Conservative turn-local permission gate for public web discovery."""
import re


def search_intent_rule(text):
    text=text.strip()
    if re.search(r'(?:不要|不用|别|无需|不必|不需要|先不|暂不|停止)(?:再|去|继续|主动|替我|帮我|给我|现在|进行|任何|新的|重新|了|\s){0,4}(?:搜|查新闻|查找|联网|上网)|(?:do not|don.t|no)\s+(?:search|browse)',text,re.I):
        return False
    if re.search(r'(?:没让|没有让|没叫|并没要求|不是让).{0,8}(?:搜|联网)|(?:为什么|怎么又).{0,8}(?:搜|联网)|(?:改|调整|优化|写).{0,8}(?:搜索词|搜索关键词|检索词)',text):
        return False
    if re.search(r'(?:搜索|检索|找|查).{0,10}(?:知识库|历史作品|历史对话|用户画像|记忆)',text) and not re.search(r'联网|上网|网上|公开新闻',text):
        return False
    action=r'(?:搜索|搜一下|搜下|搜一轮|搜一搜|搜寻|搜集|搜吧|联网|上网|网上查|search\b|look\s+up)'
    explicit=(re.search(r'(?:^|[，。；！？\n])\s*(?:请|帮我|给我|麻烦|再|重新|继续|先|现在|去|能不能|可以)?\s*'+action,text,re.I)
              or re.search(r'(?:帮我|给我|请你|麻烦你|能不能|可不可以|再帮我).{0,5}'+action,text,re.I))
    if explicit:
        return True
    if re.search(r'搜|联网|上网|search\b|browse',text,re.I):
        return None
    if re.search(r'修改|改写|改成|改为|改个|改一下|调整|改.{0,5}(?:自然|轻松|口吻|风格)|换个|换一个|换其他|换一种|其他的|别的方向|另一个方向|润色|缩短|精简|扩写|口语|语气|更自然|分镜|配音|剪辑',text):
        return False
    if re.search(r'(?:查|找).{0,30}(?:新闻|报道|热点|资讯|来源|事实|发布|最新进展)',text):
        return True
    if re.search(r'(?:给我|帮我|看看|有哪些|整理).{0,12}(?:最新|最近|今天|近期|热点).{0,15}(?:新闻|报道|资讯|事件)',text):
        return True
    if re.fullmatch(r'(?:你好|您好|嗨|hello|hi|谢谢)[！!。,.，\s]*',text,re.I):
        return False
    return None
