# Conversation API v1

All public endpoints authenticated at `/api`, raw JSON, owner checks, ISO datetimes. Existing task/admin APIs unchanged. Stable ids UUID strings. Errors `{message}` HTTP status. Internal AI APIs require X-Internal-Key. Every tool event must represent actual execution. No synthetic success in real mode. Do not persist or expose reasoning content, credentials, or system prompts.

## Domain

Conversation `{id,title,status:'IDLE'|'RUNNING'|'WAITING_INPUT'|'FAILED',brief:Brief,createdAt,updatedAt,activeRunId:string|null,messages:Message[],events:RunEvent[],artifact:Artifact|null,versions:ArtifactVersion[]}`. List endpoint may omit messages/events/versions and return artifact summary/null.
Brief `{topic:string,direction:string,audience:string,platform:string,duration:number,style:string,productionMode:string,equipment:string,requirements:string}`. Unknown strings empty, defaults platform抖音/duration60/style自然亲切. No forced initial forms. Explicit user values override inferred defaults.
Message `{id,role:'user'|'assistant',content:string,choices:ChoiceGroup[],runId:string|null,createdAt}`
ChoiceGroup `{id,kind:'TOPIC'|'DIRECTION'|'AUDIENCE'|'STYLE'|'PRODUCTION',title:string,options:ChoiceOption[],selectedOptionId:string|null,customValue:string|null,status:'OPEN'|'SELECTED'|'SUPERSEDED'}`
ChoiceOption `{id,label:'A'|'B'|'C'|'D'|'E',title,description,value:string}`. Each group has 2-5 actual options with contiguous labels starting at A. There is no synthetic custom option. Clicking stages the option value in the composer; only explicit send submits the selection and any added user notes. Backend only accepts choices from persisted current message/group. Old same-kind open groups superseded when new groups appear. Selected group is immutable; user can change requirements via chat, Agent can offer new group. Canonical selection is persisted only after the user submits the composer.
RunEvent `{id:number,runId,type:'run.started'|'message.delta'|'brief.updated'|'tool.started'|'tool.completed'|'tool.failed'|'artifact.updated'|'run.waiting_input'|'run.completed'|'run.failed'|'run.cancelled',toolCallId?:string,tool?:string,data:object,createdAt}`
Artifact `{id,title,content:ProductionPackage,versionNumber:number,status:'DRAFT'|'FINAL',sources:Source[],createdAt,updatedAt}`
ArtifactVersion `{id,versionNumber,kind:'GENERATED'|'REVISED'|'MANUAL'|'RESTORED'|'FINALIZED',instruction,content:ProductionPackage,sources:Source[],createdAt}`
ProductionPackage `{title:string,summary:string,positioning:string,audienceAnalysis:string,titles:string[],opening:string,segments:ProductionSegment[],ending:string,caption:string,tags:string[],shootingPlan:{approach:string,locations:string[],equipment:string[],props:string[],schedule:string[]},voiceoverPlan:{approach:string,tone:string,pace:string,recordingTips:string[],script:string},editingPlan:{approach:string,rhythm:string,music:string,soundEffects:string[],subtitleStyle:string,steps:string[]},qualityNotes:string[]}`
ProductionSegment `{id:string,duration:number,visual:string,narration:string,subtitle:string,shotSize:string,camera:string,action:string,voiceDirection:string,editing:string,material:string}`. idsstable for scope edits. New package20/30segmentsmax, totalfield limits sane alignPython/Java. Source same docs/API_CONTRACT.

## Public API

GET /conversations -> summary Conversation[] newestfirst max100
POST /conversations `{title?:string}` -> Conversation
GET /conversations/{id} -> full Conversation
PATCH /conversations/{id} `{title}` -> Conversation
DELETE /conversations/{id} ->204 (409running)
POST /conversations/{id}/messages `{content?:string,choice?:{messageId,groupId,optionId?:string,customText?:string}}` optional Idempotency-Key -> `{conversation:Conversation,runId:string}` (202 preferred). Reject concurrent run409. Persist usermessage+QUEUEDrun transaction, then asyncexecute.
GET /conversations/{id}/events?after=0 -> RunEvent[] afterid max500
GET /conversations/{id}/stream?after=0 -> SSE (`id:event.id`, `event:run-event`, `data:JSON RunEvent`), heartbeat. Auth via bearer fetch ReadableStream, NEVER URL token. Read persisted events bysequence, reconnectafterlast. Event may be persisted even with clientdisconnected; backend writes before emission. frontend may fallback2s GETdetail/events on streamerror.
POST /conversations/{id}/runs/{runId}/cancel -> Conversation. Cancels run flag, stops scheduling atnext boundary; rejectstale laterwrites, no partial artifact overwrite.
PUT /conversations/{id}/artifact `{content:ProductionPackage,baseVersion:number}` -> Conversation createsMANUALversion;409onrunning orstale baseversion
POST /conversations/{id}/artifact/restore/{versionId} -> Conversation newversion
POST /conversations/{id}/artifact/finalize `{baseVersion}` -> Conversation newFINALIZEDversion
GET /conversations/{id}/artifact/export?format=markdown|txt -> plainfile fullpackageinclshooting/voice/editing/source

## AI internal streaming endpoint

POST /internal/conversation/run -> application/x-ndjson. Request `{conversationId,runId,messages:[{role,content}],brief:Brief,artifact:ProductionPackage|null,sources:Source[],selection?:{kind,value},artifactStatus?:'DRAFT'|'FINAL'}`. Last30messages toboundcontext (Java can sendlatest40), maxbodylimits. Only generationmodel required; missingEmbedding is actionable search toolfailed/unavailable, can continue creative work with explicitnoevidence. Do not auto-demo real. Native toolcalling preferred, structured JSON action fallback only for explicitlyunsupportedtools; fallbackstilldynamicdecision selecting actual bounded tools.

Stream lines `{type,data}`:
run.started data `{mode:'real'|'demo'}`
message.delta data `{text}` actual assistantanswer deltas only, noreasoning
brief.updated data `{brief:Brief}`
tool.started data `{toolCallId,tool,input:object}` sanitized
 tool.completed data `{toolCallId,tool,output:object,durationMs}` summary output only
 tool.failed data `{toolCallId,tool,message,durationMs}` no credentials
artifact.updated data `{content:ProductionPackage,sources:Source[],kind:'GENERATED'|'REVISED',instruction:string,scope:'ALL'|'opening'|'titles'|'voiceover'|'shooting'|'editing'|'duration'|'segment:<uuid>'}`. The scope must match the actual create/revise tool invocation; Java independently merges only the permitted fields before commit.
final data `{content:string,choices:ChoiceGroup[],brief:Brief,usage:{inputTokens,outputTokens,model},waitingInput:boolean}`
error data `{message,code}` followedend. Java maps final toassistantmessage + runcompleted/waiting event and clearsbusy. Java validatespackage beforepersist and versionintransaction. Writes latestartifact atomically only aftervalidcurrentrun; neverpersist usercancelled late data.

Tool registry min: update_brief, propose_options(kind3options), search_knowledge, create_production, revise_production(scope stableid/opening/titles/voiceover/shooting/editing/duration/ALL), check_production. Modelmay directlyreplywithouttool. Toolsemitlive start/completed. Brief can beupdatedfromconfirmedselection incode. Nativecallloop max8steps, timeout300sec, callsbudget bounded. check_production mechanicalestimates only, neverfactverifiedclaim. No stagefake delays. Explicitdemo fixtures fullylabelled.

## UX

Homepageconversation workspace fourcolumnsrail72/history240/chatflex/right340, sage/coral/offwhite existing palette. Composer noform. Threechoicecards + Dcustom for TOPIC/DIRECTION/AUDIENCE/etc. Historycollapsible, executionrailcollapsible, mobile drawers/tabs. Right workspace uses one continuous feed for tool executions, original news links and the artifact entry, without status row or tabs. Productionpackage readable sections/timeline, inlineediting/versiondiff/export. Conversation remains primaryhome and works/nav/admincontinue. DefaultElAvatarUserFilled, noinitials. Visiblebody16/metamin14, noEnglishdecorativeeyebrows orfootersmallcopy. No conversationinstructionscopiedintoproduct.


## 工作区扩展（V3）

- `PATCH /api/conversations/{id}`：可修改 `title` 或 `pinned`；手动标题不再被自动概括覆盖。
- `PATCH /api/conversations/{id}/artifact`：`{title}` 重命名作品并保存新版本。
- `DELETE /api/conversations/{id}/artifact`：删除作品及版本，保留对话。
- `POST /api/conversations/{id}/attachments`：multipart `file`，返回 `{id,name,size,characters}`。
- `DELETE /api/conversations/{id}/attachments/{fileId}`：移除尚未发送的附件。
- 消息请求新增 `attachmentIds: string[]`；附件必须属于当前对话。消息返回 `attachments` 和 `metadata`。
- `POST /api/conversations/{id}/messages/{messageId}/retry`：支持 `Idempotency-Key`，返回 `{conversation,runId}`；`conversation.retryingMessageId` 指向原回复。成功后更新同一条回复，保留 ID/创建时间/后续消息；失败或取消保留原内容。作品版本继续按生成结果保存。
- `GET /api/memory`：返回个人记忆开关、画像条目、修订号及对话摘要。
- `PUT /api/memory`：更新 `{enabled,items:[{text,evidence}]}`。
- `DELETE /api/memory`：清空画像与摘要，保留对话和作品。

消息 `metadata` 包含 `thinkingMs`、`processingMs` 和后台生成的可展示 `summary`，不包含供应商原始推理内容。

新增实际工具：`read_document`、`read_user_profile`、`search_history_creations`、`read_history_creation`。附件和历史作品由业务服务按用户所属范围封装为本轮快照，客户端不能指定其他用户的历史。详细容量和处理边界见 `WORKSPACE_V3.md`。

`save_knowledge` 保存当前运行所属用户提供的原文或附件全文。使用单独的入库意图判断；只阅读/总结不会授权写入。右侧展示真实资料保存回执，可打开对应资料。内部回调和知识包契约见 API_CONTRACT.md。


## News and document canvas (V6)

`Conversation.researchSources: Source[]` returns persisted news snapshots derived from completed owner-scoped news tool events; the internal run request includes the same evidence. Clients cannot inject or select another user's snapshots. No database schema migration is required.

`search_news {query, days:1..90, limit:1..8}` searches bounded public indexes and publisher pages. Output includes `articles`, `sources`, `matchCount`, `notices`, and `factVerified:false`. Each article distinguishes `publishedAt` from `fetchedAt` and `BODY` from `SUMMARY`. `read_news {sourceId}` reads only an already retrieved web source snapshot. Replies report the original publication time; unknown timestamps remain unknown. Source metadata and body snapshots persist for subsequent turns and citation validation; discovery excerpts sent to the model are bounded.

`/works/:id` opens the existing artifact read-only; `?edit=1` explicitly enables the document canvas and Agent sidecar. Inline edits use the existing versioned artifact API, and Agent changes use the original conversation. `/tasks` now shows one row per work; legacy task details remain accessible.

## Personal defaults and cleanup (V7)

`GET/PUT/DELETE /creation-defaults` reads, replaces or restores the authenticated user's full Brief defaults. New conversations inherit them. `PUT /conversations/{id}/brief` updates a full Brief for this idle, owner-scoped conversation only. `DELETE /conversations/{id}/execution-records` returns Conversation with `executionsClearedThrough`; clients hide tool calls whose first event is at or before this cursor while retaining source evidence and artifacts.

Automatic memory entries retain conversation provenance. Conversation deletion and reply retry invalidate derived memory and pending extraction; manually edited and independently sourced entries remain. Migration V6 adds the defaults table and execution visibility cursor.
