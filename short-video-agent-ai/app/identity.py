"""Product identity is application behavior, not a provider's self-report."""
import re


AGENT_NAME = "知映短视频创作 Agent"
AGENT_INTRO = (
    f"我是{AGENT_NAME}，你的短视频创作搭档。"
    "可以和你一起讨论方向与受众、构思选题、完善脚本与分镜、规划拍摄配音和剪辑，"
    "也能读取文档、整理知识库并参考历史作品。"
)
MODEL_NAME = (
    r"(?:deep[\s-]?seek|深度求索|chatgpt|gpt|claude|gemini|qwen|通义千问|"
    r"doubao|豆包|文心一言|grok|llama|nemotron|conatus|qiniu/conatus|nvidia/nemotron)"
    r"[-a-z0-9_./:]*"
)
MODEL_RE = re.compile(MODEL_NAME, re.I)
IDENTITY_QUERY = re.compile(
    r"(?:你好|您好|嗨|哈喽|请问|请|那|那么|所以|到底|究竟|现在|首先|告诉我|说说|能说说)*"
    r"(?:"
    r"你(?:到底|究竟|真的|现在|本身|自己)?(?:是(?:谁|什么(?:AI|大语言|语言|大)?模型|哪(?:一)?(?:个|种|款)?(?:AI|大语言|语言|大)?模型)"
    r"|叫什么(?:名字|名)?|的(?:名字|名称|身份|模型(?:名称|名字|ID|型号)?|底层模型)是什么"
    r"|(?:用的|使用的|基于的)(?:是)?(?:什么|哪(?:一)?(?:个|种|款)?)(?:AI|大语言|语言|大)?模型"
    r"|(?:是|不是|是不是|难道是)(?:一个|一款)?" + MODEL_NAME + r")"
    r"|(?:你是)?(?:什么|哪(?:一)?(?:个|种|款)?)模型"
    r"|(?:你的|你所用的|你使用的|目前使用的|当前|底层)?模型(?:名称|名字|ID|型号)?(?:是什么|叫什么)"
    r"|(?:介绍(?:一下)?你自己|自我介绍(?:一下)?|做(?:个|一个)?自我介绍|介绍一下你的(?:身份|能力))"
    r"|whoareyou|whatisyourname|what(?:AI)?modelareyou|areyou" + MODEL_NAME +
    r")(?:吗|呢|呀|啊|么|吧|来着)?", re.I,
)
SELF_CLAIM = re.compile(
    r"^(?:我是|我不是|我叫|我的(?:名字|名称|模型(?:名称|名|型号)?|底层模型)(?:叫|是|为)"
    r"|我(?:使用|基于)|作为(?:一个|一款)?|本助手是|当前模型是"
    r"|I(?:\s+am|'m)|My\s+(?:name|model)(?:\s+name)?\s+is|As\s+an?)", re.I,
)
CLAUSE_END = re.compile(r"[，,。！？；;!?\n]|\.(?=\s)")


def is_identity_question(text: str) -> bool:
    # Full matches keep requests to write scripts or compare models on the real model path.
    compact = re.sub(r"[\s，,。！？!?；;：:~～]+", "", text)
    return len(compact) <= 140 and bool(IDENTITY_QUERY.fullmatch(compact))


class IdentityOutputFilter:
    """Check clauses before streaming; quoted scripts and model discussions stay intact."""
    def __init__(self):
        self.pending = ""
        self.in_code = False
        self.continuation = False

    def clause(self, text: str) -> str:
        in_code = self.in_code
        if text.count("```") % 2:
            self.in_code = not self.in_code
        candidate = re.sub(r"^[ \t]*[#*_]{0,3}[ \t]*", "", text)
        if not in_code and "```" not in text and not self.continuation and SELF_CLAIM.match(candidate) and MODEL_RE.search(candidate[:200]):
            return "我是" + AGENT_NAME
        return text

    def feed(self, text: str, final: bool = False) -> str:
        self.pending += text
        out = []
        while match := CLAUSE_END.search(self.pending):
            out.extend((self.clause(self.pending[:match.start()]), match.group()))
            self.pending = self.pending[match.end():]
            self.continuation = False
        if final:
            out.append(self.clause(self.pending))
            self.pending = ""
        elif len(self.pending) > 512:
            out.append(self.clause(self.pending[:512]))
            self.pending = self.pending[512:]
            self.continuation = True
        return "".join(out)


def normalize_assistant_identity(text: str) -> str:
    return IdentityOutputFilter().feed(text, final=True)


def conversation_messages(messages) -> list[dict]:
    return [dict(message.model_dump(), content=(normalize_assistant_identity(message.content)
                 if message.role == "assistant" else message.content)) for message in messages]
