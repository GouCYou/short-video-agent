"""Explicit demo fixtures; never selected as a real-provider failure fallback."""
import re
import uuid
from .conversation_models import Brief, ProductionOutput, ProductionPackage
from .conversation import event


def production(ctx):
    brief = ctx.brief
    subject = brief.topic or "校园生活中的一个小发现"
    scene = brief.direction or "从一个熟悉的场景出发，说明一个实用观点"
    audience = brief.audience or "对校园生活感兴趣的观众"
    durations = [round(brief.duration * weight, 2) for weight in (0.15, 0.25, 0.4)]
    durations.append(round(brief.duration - sum(durations), 2))
    lines = [f"关于{subject}，你有没有注意过这个小细节？今天我们从身边的场景说起。",
             f"先把镜头放到一个熟悉的位置。{scene}。观察具体发生了什么，记录一个能说明主题的小场景。",
             "接着给出三个可操作的步骤：先记录问题，再核对已有信息，最后试一个小行动。这里的具体案例需要你在拍摄前补充并核实。",
             f"这就是关于{subject}的一次小观察。你还遇到过哪些类似的情况？欢迎留下你的看法。"]
    sources = []
    if ctx.evidence:
        source = ctx.evidence[0]
        lines[1] = f"资料原文摘录：“{source.text[:80]}”[资料:{source.chunkId}]。这是演示模式展示的片段，发布前请核查原文与适用条件。"
        sources = [source.chunkId]
    segments = [{"id": str(uuid.uuid4()), "duration": durations[i],
                 "visual": ["创作者面对镜头，背景保持简洁，叠加主题字幕。", "展示能够说明主题的生活场景，补拍细节。", "展示三张自制步骤卡片，依次揭示内容。", "回到正面镜头，显示互动问题。"][i],
                 "narration": line, "subtitle": ["你注意过这个细节吗？", "从熟悉场景开始", "记录 → 核查 → 行动", "说说你的看法"][i],
                 "shotSize": ["中近景", "中景与特写", "俯拍特写", "中近景"][i],
                 "camera": "手机固定拍摄；切换机位前记录构图。", "action": "按口播自然表达，重点处配合手势。",
                 "voiceDirection": "语气自然，关键词稍作停顿。", "editing": "按语意切镜，保留每段开头半秒余量。",
                 "material": "自行拍摄生活场景与自制文字卡片；避免无授权素材。"} for i, line in enumerate(lines)]
    data = {"title": subject, "summary": f"围绕{subject}，采用{scene}，面向{audience}的短视频制作建议。",
            "positioning": f"{brief.platform} / {brief.duration} 秒 / {brief.style or '自然亲切'} / {brief.productionMode or '手机实拍'}",
            "audienceAnalysis": f"面向{audience}，先建立熟悉感，再提供清晰建议。",
            "titles": [subject, f"关于{subject}，先看看这件事", f"一分钟聊聊{subject}"],
            "opening": lines[0], "segments": segments, "ending": lines[-1],
            "caption": f"从一个小场景聊聊{subject}。此稿为离线演示模板，拍摄前请补充真实细节。",
            "tags": [subject[:30], "内容创作", "演示模板"],
            "shootingPlan": {"approach": "采用手机实拍与自制卡片完成，先拍口播主镜头，再补场景细节。",
                             "locations": ["安静且获准拍摄的室内位置", "与主题相关且允许拍摄的生活场景"],
                             "equipment": [brief.equipment or "手机", "稳定支架（如有）", "耳机麦克风或手机录音"],
                             "props": ["三张步骤卡片", "与主题相关的日常物品"],
                             "schedule": ["拍前检查收声、光线与背景", "录制完整口播两遍", "补拍每段对应的场景与特写", "现场检查素材是否完整"]},
            "voiceoverPlan": {"approach": "可以同期口播，也可以在安静房间补录；每段单独留出重录余量。", "tone": brief.style or "自然亲切",
                              "pace": "每秒约 3 到 5 个中文字；这是估计值，以实际试读为准。",
                              "recordingTips": ["手机与嘴保持稳定距离", "关掉持续噪声源，录几秒环境底噪", "重点前后适度停顿，避免赶字"],
                              "script": "\n".join(lines)},
            "editingPlan": {"approach": "先按口播搭建粗剪，再补画面、字幕与声音。", "rhythm": "开头直接进入问题，正文每个步骤对应一个画面变化。",
                            "music": "选择有合法使用许可的轻背景音乐；音量保持低于人声，不指定未经核实的曲目。",
                            "soundEffects": ["步骤卡片出现时可加轻提示音，避免遮挡口播"],
                            "subtitleStyle": "高对比、每屏一到两行，关键短语用统一强调色。",
                            "steps": ["按段落顺序剪出口播主线", "加入场景特写并检查画面与口播对应", "校对字幕、人名和事实信息", "平衡人声与音乐并完整预览", "按目标平台规格导出并再检查一次"]},
            "qualityNotes": ["DEMO：确定性制作包模板，未调用真实大模型。", "分段时长为预算值，口播需实际试读。", "事实、场地许可与素材授权需在发布前核查。"]}
    return ProductionOutput(content=ProductionPackage.model_validate(data), usedChunkIds=sources)


def revise(ctx, args):
    content = ctx.artifact.model_copy(deep=True)
    note = "【演示修改：" + args.instruction[:120] + "】"
    scope = args.scope
    if scope in {"ALL", "opening"}:
        content.opening = "先说重点：" + content.opening[:4000] + note
    if scope in {"ALL", "titles"}:
        content.title = content.title[:160] + "·调整版"
        content.titles[0] = content.title
    if scope in {"ALL", "shooting"}:
        content.shootingPlan.approach += note
    if scope in {"ALL", "voiceover"}:
        content.voiceoverPlan.approach += note
    if scope in {"ALL", "editing"}:
        content.editingPlan.approach += note
    if scope == "duration":
        found = re.search(r"(\d{2,3})\s*秒", args.instruction)
        duration = max(10, min(600, int(found.group(1)))) if found else ctx.brief.duration
        each = round(duration / len(content.segments), 2)
        for segment in content.segments:
            segment.duration = each
        content.segments[-1].duration = round(duration - each * (len(content.segments) - 1), 2)
    target = scope.removeprefix("segment:")
    for segment in content.segments:
        if scope == "ALL" or segment.id == target:
            segment.narration += note
    return ProductionOutput(content=content, usedChunkIds=[source.chunkId for source in ctx.sources])


def choices(kind):
    data = {
        "TOPIC": ("先选一个想聊的选题", [("校园日常观察", "从身边的小事提炼共鸣", "校园日常观察"), ("学习方法实验", "记录尝试过程和个人体验", "学习方法实验"), ("宿舍生活窍门", "用低预算方法解决常见问题", "宿舍生活窍门")]),
        "DIRECTION": ("这个主题可以怎样表达", [("生活场景故事", "用一个具体场景带出观点", "生活场景故事"), ("三个实用步骤", "用清单方式表达可操作建议", "三个实用步骤"), ("亲身体验记录", "讲清过程、观察和适用限制", "亲身体验记录")]),
        "AUDIENCE": ("你更想与哪类观众交流", [("刚入学的新生", "关注适应校园与入门经验", "刚入学的新生"), ("在校大学生", "关注学习和日常生活体验", "在校大学生"), ("对校园感兴趣的观众", "需要更多场景背景与解释", "对校园感兴趣的观众")]),
        "STYLE": ("选择一种表达风格", [("自然亲切", "像与朋友交流", "自然亲切"), ("轻松幽默", "用轻松表达保持清晰", "轻松幽默"), ("清晰理性", "强调结构、信息与边界", "清晰理性")]),
        "PRODUCTION": ("选择适合现有条件的制作方式", [("手机实拍", "以真人口播与生活画面为主", "手机实拍"), ("画面加配音", "先录旁白，再搭配自摄画面", "画面加配音"), ("文字卡片讲解", "采用自制卡片与简单镜头", "文字卡片讲解")]),
    }
    title, rows = data[kind]
    return {"kind": kind, "title": title, "options": [{"label": label, "title": row[0], "description": row[1], "value": row[2]}
            for label, row in zip("ABC", rows)]}


async def run_demo(agent, ctx):
    text = ctx.request.messages[-1].content
    calls = []
    full = bool(re.search(r"(生成|制作|给我|创建|写|做).{0,16}(完整|全套|制作包)|完整.{0,12}(方案|脚本|作品)|全套.{0,12}(方案|脚本|作品)", text))
    if ctx.artifact and re.search(r"改|修改|调整|优化|缩短", text):
        scope = "opening"
        for word, value in [("配音", "voiceover"), ("拍摄", "shooting"), ("剪辑", "editing"), ("标题", "titles"), ("时长", "duration"), ("全部", "ALL")]:
            if word in text:
                scope = value
        match = re.search(r"第\s*(\d+)\s*段", text)
        if match and 0 < int(match.group(1)) <= len(ctx.artifact.segments):
            scope = "segment:" + ctx.artifact.segments[int(match.group(1)) - 1].id
        calls.append(("revise_production", {"scope": scope, "instruction": text}))
        answer = "已按指定范围完成演示修改，其他部分保持原稿。当前为演示模式，未调用真实大模型。"
    elif full:
        calls.append(("create_production", {"instruction": text}))
        calls.append(("check_production", {}))
        answer = "完整演示制作包已整理好，包含分段脚本、镜头安排、拍摄、配音和剪辑建议。请在作品区查看；当前为离线演示模板，发布前需要补充真实细节并核查。"
    elif re.search(r"(查|搜|检索).{0,8}(资料|知识)|依据|来源", text):
        calls.append(("search_knowledge", {"query": ctx.brief.topic or text[:1000], "limit": 5}))
        answer = "已执行知识检索。检索结果只提供参考线索，不能代替事实核查。当前为演示模式。"
    else:
        kind = None
        if ctx.request.selection:
            answer = "记下这个选择了。我们可以继续讨论，你也可以告诉我下一步想完善什么。"
        elif re.fullmatch(r"\s*(你好|您好|嗨|哈喽|hello|hi|早上好|下午好|晚上好)[呀啊哦呢！!。.,，～~\s]*", text, re.I):
            answer = "你好！有什么想聊的？"
        else:
            requested = bool(re.search(r"选题|选项|推荐|给我|帮我想|几个|三个|几种|建议", text))
            if requested:
                if re.search(r"风格", text):
                    kind = "STYLE"
                elif re.search(r"拍摄方式|制作方式", text):
                    kind = "PRODUCTION"
                elif re.search(r"受众|观众", text):
                    kind = "AUDIENCE"
                elif re.search(r"方向|表达", text):
                    kind = "DIRECTION"
                elif re.search(r"选题", text):
                    kind = "TOPIC"
            subject = ctx.brief.topic or ctx.brief.direction
            if not subject:
                match = re.search(r"校园|宿舍|学习|咖啡|数码|美食|运动|健身|旅行|摄影|职场|宠物", text)
                if match:
                    subject = match.group(0)
                    calls.append(("update_brief", {"topic": subject}))
            if kind == "TOPIC" and not subject:
                kind = None
                answer = "你更想做哪类内容？先聊一个感兴趣的领域，或者想让观众了解的事。"
            elif kind:
                answer = "这些选择可以作为讨论的起点，也可以直接说说你自己的想法。"
            else:
                answer = "可以，我们先聊聊你的想法。你现在最想讨论哪一部分？"
        if kind:
            proposal = choices(kind)
            if kind == "TOPIC" and subject and not re.search(r"校园|宿舍|学习", subject):
                proposal["title"] = "这三个切入点，你更想聊哪一个"
                proposal["options"] = [
                    {"label": label, "title": title, "description": description, "value": title}
                    for label, title, description in [
                        ("A", subject + "入门常识", "从一个常见问题讲清基本概念"),
                        ("B", subject + "常见误区", "结合具体场景讨论容易忽略的细节"),
                        ("C", subject + "体验记录", "用实际过程分享个人观察")]]
            calls.append(("propose_options", proposal))
    for name, args in calls:
        call = {"id": "demo_" + uuid.uuid4().hex, "function": {"name": name, "arguments": args}}
        async for item in agent.call_tool(ctx, call):
            if "_tool_result" not in item:
                yield item
    yield event("message.delta", text=answer)
    yield agent.final(ctx, answer)
