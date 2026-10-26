"""Explicit fixtures for offline demo. No claim of model-generated content."""
from .models import Content, ContentOutput, GenerateRequest, Segment, Source, Topic


def topics(req: GenerateRequest):
    return [Topic(title=f"{req.task.topic}：{suffix}", angle=angle, reason=f"适合{req.task.audience}，用于演示选题确认。")
            for suffix, angle in [("从一个常见疑问开始", "问题切入，先提出疑问再解释方法"),
                                  ("三个值得关注的细节", "清单结构，每个细节配一个生活场景"),
                                  ("跟着一个场景理解", "情境叙事，从场景走向可执行建议")]]


def content(req: GenerateRequest, sources: list[Source] | None = None) -> ContentOutput:
    subject = req.selectedTopic.title if req.selectedTopic else req.task.topic
    duration = req.task.duration
    lengths = [round(duration * 0.15, 2), round(duration * 0.25, 2), round(duration * 0.4, 2)]
    lengths.append(round(duration - sum(lengths), 2))
    result = ContentOutput(content=Content(
        titles=[subject, f"关于{req.task.topic}，先弄懂这件事", f"{req.task.topic}的入门思路"],
        opening=f"你是否也对“{req.task.topic}”有这样的疑问？先用一个生活场景来理解。",
        positioning=f"面向{req.task.audience}的{req.task.platform}短视频；主题为{req.task.topic}。",
        audienceAnalysis=f"{req.task.audience}需要清楚、可执行的入门内容。采用{req.task.style}表达。",
        outline=["提出主题和疑问", "解释一个生活场景", "给出行动方法", "总结与互动"],
        segments=[Segment(duration=lengths[0], visual="近景：创作者直视镜头，叠加主题字幕。", narration=f"今天聊聊{req.task.topic}。先问你一个问题：遇到相关情况时，你通常怎么做？", subtitle="从一个问题开始"),
                  Segment(duration=lengths[1], visual="中景：展示白板或自制场景卡片。", narration=f"把{req.task.topic}放进熟悉的场景，先明确目标，再整理已有信息。这里需要补充与你主题匹配、可以核实的具体案例。", subtitle="明确目标，整理信息"),
                  Segment(duration=lengths[2], visual="特写：依次展示三张步骤卡片。", narration="第一步，先记录当前问题。第二步，对照可靠资料检查信息。第三步，选择一个小行动并观察结果。具体事实与数字，请在正式发布前核查。", subtitle="记录 → 核查 → 行动"),
                  Segment(duration=lengths[3], visual="回到正面镜头，显示互动问题。", narration=f"这就是理解{req.task.topic}的一个起点。你还想了解哪个部分？欢迎留下问题。", subtitle="你还想了解什么？")],
        ending="你还想了解哪个部分？欢迎留下问题。",
        caption=f"一起梳理{req.task.topic}的入门思路。此内容为离线演示模板，发布前需补充案例并核查。",
        tags=[req.task.topic[:30], "内容创作", "演示模板"],
        qualityNotes=["DEMO：确定性演示模板，未调用大模型，不可作为真实生成或评测结果。", "分段时长为预算值，不等于真实口播时长。"],
    ), usedChunkIds=[])
    if sources:
        source = sources[0]
        # This is an exact excerpt, not a simulated fact synthesis. It allows the
        # source snapshot UI/lifecycle to be exercised against actual Qdrant.
        result.content.segments[1].narration = (
            f"资料片段示例：“{source.text[:80]}”[资料:{source.chunkId}]。"
            "这是演示模式直接展示的检索片段，正式发布前请核查原文与适用条件。"
        )
        result.content.qualityNotes.append("DEMO：第二段直接摘录首个检索片段，用于展示来源追溯；没有进行模型事实审核。")
        result.usedChunkIds = [source.chunkId]
    return result


def revise(req: GenerateRequest):
    value = req.content.model_copy(deep=True)
    note = f"【演示修改：{req.instruction[:120]}】"
    section = req.section
    if section in {"ALL", "title"}:
        value.titles = [value.titles[0] + note] + value.titles[1:]
    if section in {"ALL", "opening"}:
        value.opening += note
    if section in {"ALL", "ending"}:
        value.ending += note
    if section in {"ALL", "caption"}:
        value.caption += note
    if section in {"ALL", "script"}:
        for segment in value.segments:
            segment.narration += note
    if section.isdigit():
        value.segments[int(section)].narration += note
    return ContentOutput(content=value, usedChunkIds=[source.chunkId for source in req.sources])

