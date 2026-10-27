"""Conversation-only schemas. Existing task Content remains a separate contract."""
from typing import Annotated, Literal
from uuid import UUID
from pydantic import Field, StringConstraints, field_validator, model_validator
from .models import Schema, ShortText, Source

Nonempty = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=5000)]
ChoiceKind = Literal["TOPIC", "DIRECTION", "AUDIENCE", "STYLE", "PRODUCTION"]


class Brief(Schema):
    topic: str = Field(default="", max_length=2000)
    direction: str = Field(default="", max_length=2000)
    audience: str = Field(default="", max_length=2000)
    platform: str = Field(default="抖音", min_length=1, max_length=100)
    duration: int = Field(default=60, ge=10, le=600)
    style: str = Field(default="自然亲切", max_length=2000)
    productionMode: str = Field(default="", max_length=2000)
    equipment: str = Field(default="", max_length=2000)
    requirements: str = Field(default="", max_length=6000)


class BriefUpdate(Schema):
    topic: str | None = Field(default=None, max_length=2000)
    direction: str | None = Field(default=None, max_length=2000)
    audience: str | None = Field(default=None, max_length=2000)
    platform: str | None = Field(default=None, min_length=1, max_length=100)
    duration: int | None = Field(default=None, ge=10, le=600)
    style: str | None = Field(default=None, max_length=2000)
    productionMode: str | None = Field(default=None, max_length=2000)
    equipment: str | None = Field(default=None, max_length=2000)
    requirements: str | None = Field(default=None, max_length=6000)


class ProductionSegment(Schema):
    id: str = Field(min_length=1, max_length=36, json_schema_extra={"format": "uuid"})
    duration: float = Field(gt=0, le=600)
    visual: Nonempty
    narration: str = Field(min_length=1, max_length=10000)
    subtitle: str = Field(max_length=5000)
    shotSize: ShortText
    camera: ShortText
    action: ShortText
    voiceDirection: ShortText
    editing: ShortText
    material: ShortText

    @field_validator("id")
    @classmethod
    def uuid_id(cls, value):
        try:
            return str(UUID(value))
        except ValueError as exc:
            raise ValueError("segment id must be a UUID") from exc


class ShootingPlan(Schema):
    approach: Nonempty
    locations: list[ShortText] = Field(min_length=1, max_length=30)
    equipment: list[ShortText] = Field(min_length=1, max_length=30)
    props: list[ShortText] = Field(max_length=30)
    schedule: list[ShortText] = Field(min_length=1, max_length=30)


class VoiceoverPlan(Schema):
    approach: Nonempty
    tone: ShortText
    pace: ShortText
    recordingTips: list[ShortText] = Field(min_length=1, max_length=30)
    script: str = Field(min_length=1, max_length=30000)


class EditingPlan(Schema):
    approach: Nonempty
    rhythm: ShortText
    music: ShortText
    soundEffects: list[ShortText] = Field(max_length=30)
    subtitleStyle: ShortText
    steps: list[ShortText] = Field(min_length=1, max_length=30)


class ProductionPackage(Schema):
    title: str = Field(min_length=1, max_length=300)
    summary: Nonempty
    positioning: Nonempty
    audienceAnalysis: Nonempty
    titles: list[ShortText] = Field(min_length=1, max_length=10)
    opening: Nonempty
    segments: list[ProductionSegment] = Field(min_length=1, max_length=30)
    ending: Nonempty
    caption: str = Field(min_length=1, max_length=10000)
    tags: list[ShortText] = Field(max_length=20)
    shootingPlan: ShootingPlan
    voiceoverPlan: VoiceoverPlan
    editingPlan: EditingPlan
    qualityNotes: list[ShortText] = Field(max_length=30)

    @model_validator(mode="after")
    def unique_segments(self):
        if len({segment.id for segment in self.segments}) != len(self.segments):
            raise ValueError("segment ids must be unique")
        return self


class ProductionOutput(Schema):
    content: ProductionPackage
    usedChunkIds: list[str] = Field(max_length=100)


class OpeningRevision(Schema):
    opening: Nonempty
    usedChunkIds: list[str] = Field(default_factory=list, max_length=100)


class ChoiceCandidate(Schema):
    label: Literal["A", "B", "C", "D", "E"]
    title: str = Field(min_length=1, max_length=300)
    description: str = Field(min_length=1, max_length=2000)
    value: str = Field(min_length=1, max_length=2000)


class ProposeOptions(Schema):
    kind: ChoiceKind
    title: str = Field(min_length=1, max_length=300)
    options: list[ChoiceCandidate] = Field(min_length=2, max_length=5)

    @model_validator(mode="after")
    def labels(self):
        if [option.label for option in self.options] != list("ABCDE")[:len(self.options)]:
            raise ValueError("options must be ordered from A through E")
        if len({option.value for option in self.options}) != len(self.options):
            raise ValueError("options must have distinct values")
        return self


class ChatMessage(Schema):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=20000)


class Selection(Schema):
    kind: ChoiceKind
    value: str = Field(min_length=1, max_length=2000)


class ConversationRequest(Schema):
    researchSources: list[Source] = Field(default_factory=list, max_length=100)
    knowledgeDocumentIds: list[str] | None = Field(default=None, max_length=10000)
    conversationId: str = Field(min_length=1, max_length=100)
    runId: str = Field(min_length=1, max_length=100)
    messages: list[ChatMessage] = Field(min_length=1, max_length=40)
    brief: Brief = Field(default_factory=Brief)
    artifact: ProductionPackage | None = None
    sources: list[Source] = Field(default_factory=list, max_length=100)
    selection: Selection | None = None
    artifactStatus: Literal["DRAFT", "FINAL"] = "DRAFT"
    memory: dict = Field(default_factory=dict)
    conversationSummary: str = Field(default="", max_length=6000)
    historyWorks: list[dict] = Field(default_factory=list, max_length=100)
    documents: list[dict] = Field(default_factory=list, max_length=50)


class ReadDocument(Schema):
    documentId: str = Field(min_length=1, max_length=100)
    offset: int = Field(default=0, ge=0, le=200000)
    limit: int = Field(default=12000, ge=500, le=20000)


class SaveKnowledge(Schema):
    name: str = Field(default="", max_length=200)
    documentId: str = Field(default="", max_length=100)
    text: str = Field(default="", max_length=200000)
    category: str = Field(default="通用", max_length=100)

    @model_validator(mode="after")
    def source(self):
        if bool(self.documentId.strip()) == bool(self.text.strip()):
            raise ValueError("Choose one document or user-provided text")
        return self


class SearchHistory(Schema):
    query: str = Field(default="", max_length=1000)


class ReadHistory(Schema):
    artifactId: str = Field(min_length=1, max_length=100)


class CreateProduction(Schema):
    instruction: str = Field(default="", max_length=6000)


class ReviseProduction(Schema):
    scope: str = Field(min_length=1, max_length=100)
    instruction: str = Field(min_length=1, max_length=6000)


class CheckProduction(Schema):
    pass


class DynamicAction(Schema):
    tool: Literal["update_brief", "propose_options", "search_knowledge", "search_news", "read_news", "create_production", "revise_production", "check_production", "read_document", "save_knowledge", "search_history_creations", "read_history_creation", "read_user_profile", "respond"]
    arguments: dict = Field(default_factory=dict)
