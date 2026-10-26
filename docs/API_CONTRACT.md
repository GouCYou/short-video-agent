# API contract

All public APIs prefix `/api`; JSON uses camelCase. Success returns raw objects/arrays (no envelope); errors `{message, code?}` with HTTP status. Authentication: `Authorization: Bearer <opaque token>` stored in Redis (7 days), logout revokes. Roles USER/ADMIN. IDs string UUID. ISO datetimes. Java backend sole public business authority. AI service private with `X-Internal-Key` header. No secrets in UI.

## Auth
POST /auth/register `{username,password,nickname}` -> `{token,user}`
POST /auth/login `{username,password}` -> same
POST /auth/logout -> 204
GET /auth/me -> User `{id,username,nickname,role,enabled,createdAt}`
PUT /auth/me `{nickname}` -> User
PUT /auth/password `{oldPassword,newPassword}` -> 204

## Tasks
GET /tasks?search=&status= -> Task[] newest first
POST /tasks (optional `Idempotency-Key` request header, per-user unique, max100 characters) `{title,topic,audience,platform,style,duration,requirements,mode,category,ragEnabled}` -> Task; mode GUIDED/QUICK, duration seconds, ragEnabled bool
GET /tasks/{id} -> Task including `result`, `topics`, `events`, `versions`, `sources`
POST /tasks/{id}/select-topic `{topicIndex}` -> Task (starts script stage)
POST /tasks/{id}/retry -> Task (fresh run)
POST /tasks/{id}/revise `{instruction,section}` -> Task; section ALL/title/opening/script/ending/caption or segment index string; creates immutable version
PUT /tasks/{id}/content `{content: object}` -> Task; manual save creates version
POST /tasks/{id}/restore/{versionId} -> Task creates a new version from old
DELETE /tasks/{id} -> 204 (409 if running)
GET /tasks/{id}/export?format=markdown|txt -> downloadable plain text
GET /dashboard -> `{totalTasks,completedTasks,runningTasks,failedTasks,recentTasks:Task[]}`
Task `{id,title,topic,audience,platform,style,duration,requirements,mode,category,ragEnabled,status,stage,error,createdAt,updatedAt,topics:Topic[],result:Content|null,sources:Source[],events:Event[],versions:Version[]}`
Statuses QUEUED/RUNNING/WAITING_SELECTION/COMPLETED/FAILED
Topic `{title,angle,reason}`
Content `{titles:string[],opening:string,positioning:string,audienceAnalysis:string,outline:string[],segments: {duration:number,visual:string,narration:string,subtitle:string}[],ending:string,caption:string,tags:string[],qualityNotes:string[]}`
Source `{documentId,documentName,chunkId,text,score,sourceUrl}`
Event `{stage,message,createdAt,durationMs?}`
Version `{id,versionNumber,kind,instruction,content:Content,sources:Source[],createdAt}`

## Knowledge (ADMIN management, USER can GET enabled categories)
GET /knowledge -> current user's documents, initializing default copies once
GET /knowledge/health -> safe embedding/vector configuration status
POST /knowledge multipart `file,category,sourceUrl` -> Document (text extracted, UNINDEXED)
POST /knowledge/text `{name,text,category,sourceUrl}` -> Document (UNINDEXED)
GET /knowledge/{id} -> owned Document with text/chunks/isDefault
PUT /knowledge/{id} `{name?,text?,enabled?,category?,sourceUrl?}` -> owned Document; changed content invalidates index
POST /knowledge/restore-defaults -> Document[]; restores presets and preserves custom documents
POST /knowledge/index-all -> {queued}; queues unindexed/failed documents
POST /knowledge/{id}/reindex -> owned Document
DELETE /knowledge/{id} -> 200; owner-only
POST /knowledge/search `{query,category?,limit?}` -> owned READY/enabled Source[]

Legacy public knowledge endpoints (retained for existing task integrations; exclude private documents):
GET /knowledge/categories -> string[]
GET /admin/knowledge -> Document[]
POST /admin/knowledge multipart `file,category,sourceUrl` -> Document (index async)
POST /admin/knowledge/text `{name,text,category,sourceUrl}` -> Document
GET /admin/knowledge/{id} -> Document with chunks
PUT /admin/knowledge/{id} `{enabled,category,sourceUrl}` -> Document; vector metadata updated
POST /admin/knowledge/{id}/reindex -> Document
DELETE /admin/knowledge/{id} -> 204
POST /admin/knowledge/search `{query,category,limit}` -> Source[]
Document `{id,name,category,sourceUrl,enabled,status,chunkCount,error,createdAt,chunks?:{id,text}[]}` statuses INDEXING/READY/FAILED
GET /admin/users -> User[]
PUT /admin/users/{id} `{enabled,role}` -> User (prevent self disable/demotion, never expose password)
GET /admin/tasks -> Task[]
GET /admin/stats -> `{users,tasks,documents,failedTasks,completedTasks,averageDurationMs}`
GET /admin/health -> `{ai:object,redis: string,database:string}` AI includes configured generation/embedding boolean, never keys.

## AI private service contract
Header `X-Internal-Key`, required except GET /health (only status). All APIs raw JSON.
GET /internal/health -> `{status,generationConfigured,embeddingConfigured,vectorStore,statusDetail?}`
POST /internal/generate `{taskId,phase:'PLAN'|'GENERATE'|'REVISE',task:{topic,audience,platform,style,duration,requirements,category,ragEnabled,mode},selectedTopic?:Topic,content?:Content,instruction?:string,section?:string,sources?:Source[]}` -> `{topics:Topic[],content?:Content,sources:Source[],events:{stage,message,durationMs?}[],usage:{inputTokens,outputTokens,model},quality?:object}`. PLAN returns topics only. GENERATE can generate topics when no selectedTopic. REVISE preserves fields outside requested section. Missing keys returns 503 actionable error, NEVER fake AI success. `AI_MODE=demo` explicit opt-in deterministic outputs marked demo through event/quality; default real.
POST /internal/knowledge/index `{documentId,path,name,category,sourceUrl,enabled}` OR `{documentId,text,name,category,sourceUrl,enabled}` -> `{chunkCount,chunks:{id,text}[]}`. Optional `ownerId` marks private documents. Path within shared knowledge mount only. Index replacement idempotent.
POST /internal/knowledge/search `{query,category?,limit?,documentIds?}` -> Source[]. Trusted server scope is applied before top-k; omitted scope excludes private documents, [] returns no sources.
PUT /internal/knowledge/{id} `{enabled,category,sourceUrl}` -> `{ok:true}`
DELETE /internal/knowledge/{id} -> `{ok:true}`

## Architecture notes
Backend MySQL8 Flyway + Redis sessions/execution lock. Persistent MySQL job records, scheduled workers with bounded concurrency, stale RUNNING job recovery; don't hold transactions during network calls. Backend AI HTTP timeout >= 300 sec. Knowledge local mount shared backend & AI; unique safe names; cap 20MB; no filename/path traversal. Frontend polls active detail every 2s, cleans timers; use data from real API. Server owner checks for all task/version/export endpoints. API keys only env configuration. Production defaults no demo/seed passwords; explicit demo profile permissible. Deploy topology MySQL8, Redis7, Qdrant, AI, backend, frontend Nginx. `/api` proxy to backend. AI never public port in compose.

User knowledge ingestion additions (authenticated):
- POST /knowledge/import multipart files[] + category + chunkSize + chunkOverlap -> {items,indexingAvailable}; up to 20 files, partial success supported.
- GET /knowledge/export -> {format:"zhiying-knowledge",version:1,documents:[{name,text,category,sourceUrl,enabled,chunkSize,chunkOverlap}]}
- POST /knowledge/preview {text,chunkSize,chunkOverlap} -> {chunkCount,characters,chunks,truncated}; no embedding call.
- Document responses add originType, originId, chunkSize, chunkOverlap and optional duplicate.
- PUT /knowledge/{id} accepts chunkSize (200..2000), chunkOverlap (0..500 and less than size). Changes invalidate old index.
- POST /internal/conversations/{runId}/knowledge requires X-Internal-Key and an active persisted run. Payload chooses documentId or verbatim user text, with optional name/category. Response contains documentId,name,status,enabled,duplicate,indexingAvailable and optional indexError. Owner and source text are resolved on the server.
