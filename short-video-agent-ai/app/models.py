from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

ShortText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]


class Schema(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, strict=True, allow_inf_nan=False)


class Topic(Schema):
    title: str = Field(min_length=1, max_length=300)
    angle: str = Field(min_length=1, max_length=2000)
    reason: str = Field(min_length=1, max_length=2000)


class Segment(Schema):
    duration: float = Field(gt=0, le=600)
    visual: str = Field(min_length=1, max_length=5000)
    narration: str = Field(min_length=1, max_length=10000)
    subtitle: str = Field(max_length=5000)


class Content(Schema):
    titles: list[ShortText] = Field(min_length=1, max_length=10)
    opening: str = Field(min_length=1, max_length=5000)
    positioning: str = Field(min_length=1, max_length=5000)
    audienceAnalysis: str = Field(min_length=1, max_length=5000)
    outline: list[ShortText] = Field(min_length=1, max_length=30)
    segments: list[Segment] = Field(min_length=1, max_length=30)
    ending: str = Field(min_length=1, max_length=5000)
    caption: str = Field(min_length=1, max_length=10000)
    tags: list[ShortText] = Field(max_length=20)
    qualityNotes: list[ShortText] = Field(max_length=30)


class Source(Schema):
    documentId: str
    documentName: str
    chunkId: str
    text: str = Field(max_length=10000)
    score: float
    sourceUrl: str = ""


class TaskInput(Schema):
    topic: str = Field(min_length=1, max_length=3000)
    audience: str = Field(min_length=1, max_length=1000)
    platform: str = Field(min_length=1, max_length=100)
    style: str = Field(min_length=1, max_length=500)
    duration: int = Field(ge=10, le=600)
    requirements: str = Field(default="", max_length=6000)
    category: str = Field(default="", max_length=100)
    ragEnabled: bool = True
    mode: Literal["GUIDED", "QUICK"] = "GUIDED"


class GenerateRequest(Schema):
    taskId: str = Field(min_length=1, max_length=100)
    phase: Literal["PLAN", "GENERATE", "REVISE"]
    task: TaskInput
    selectedTopic: Topic | None = None
    content: Content | None = None
    instruction: str = Field(default="", max_length=6000)
    section: str = Field(default="ALL", max_length=30)
    sources: list[Source] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def valid_revision(self):
        if self.phase == "REVISE":
            if self.content is None or not self.instruction:
                raise ValueError("REVISE requires content and instruction")
            sections = {"ALL", "title", "opening", "script", "ending", "caption"}
            if self.section not in sections:
                if not self.section.isdigit() or int(self.section) >= len(self.content.segments):
                    raise ValueError("Unknown revision section; segment indexes are zero-based")
        return self


class IndexRequest(Schema):
    documentId: str = Field(min_length=1, max_length=100)
    ownerId: str = Field(default="", max_length=100)
    chunkSize: int = Field(default=700, ge=200, le=2000)
    chunkOverlap: int = Field(default=100, ge=0, le=500)
    path: str | None = None
    text: str | None = Field(default=None, max_length=2_000_000)
    name: str = Field(min_length=1, max_length=300)
    category: str = Field(default="", max_length=100)
    sourceUrl: str = Field(default="", max_length=2000)
    enabled: bool = True

    @model_validator(mode="after")
    def one_input(self):
        if self.chunkOverlap >= self.chunkSize:
            raise ValueError("overlap must be smaller than chunk size")
        if (self.path is None) == (self.text is None):
            raise ValueError("Provide exactly one of path or text")
        return self


class ChunkPreview(Schema):
    text: str = Field(min_length=1, max_length=200000)
    chunkSize: int = Field(default=700, ge=200, le=2000)
    chunkOverlap: int = Field(default=100, ge=0, le=500)

    @model_validator(mode="after")
    def settings(self):
        if self.chunkOverlap >= self.chunkSize:
            raise ValueError("overlap must be smaller than chunk size")
        return self


class SearchRequest(Schema):
    query: str = Field(min_length=1, max_length=3000)
    category: str | None = Field(default=None, max_length=100)
    limit: int = Field(default=5, ge=1, le=20)


class InternalSearchRequest(SearchRequest):
    documentIds: list[str] | None = Field(default=None, max_length=10000)


class MetadataRequest(Schema):
    enabled: bool
    category: str = Field(max_length=100)
    sourceUrl: str = Field(max_length=2000)


class Analysis(Schema):
    query: str = Field(min_length=1, max_length=3000)
    brief: str = Field(min_length=1, max_length=3000)


class TopicsOutput(Schema):
    topics: list[Topic] = Field(min_length=3, max_length=3)


class Plan(Schema):
    positioning: str = Field(min_length=1, max_length=5000)
    audienceAnalysis: str = Field(min_length=1, max_length=5000)
    outline: list[ShortText] = Field(min_length=1, max_length=30)


class ContentOutput(Schema):
    content: Content
    usedChunkIds: list[str] = Field(max_length=100)

