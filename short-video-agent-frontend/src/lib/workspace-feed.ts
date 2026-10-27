import type {RunEvent} from './conversation-types';
import type {Source} from './types';
export interface WorkspaceToolCall {
 id:string; runId:string;tool:string;status:string;input:unknown;output:unknown;
 durationMs?:number;message:string;firstEventId:number;
}
export interface WorkspaceToolGroup extends WorkspaceToolCall { calls:WorkspaceToolCall[] }
export function workspaceToolGroups(events:RunEvent[],clearedThrough=0):WorkspaceToolGroup[]{
 const map=new Map<string,WorkspaceToolCall>();
 for(const event of [...events].sort((a,b)=>a.id-b.id)){
  if(!event.type.startsWith('tool.'))continue;
  const id=event.runId+':'+(event.toolCallId||String(event.data.toolCallId||event.id));
  const tool=event.tool||String(event.data.tool||'');
  const call=map.get(id)||{id,runId:event.runId,tool,status:'RUNNING',input:null,output:null,message:'',firstEventId:event.id};
  if(event.type==='tool.started')call.input=event.data.input;
  if(event.type==='tool.completed'){call.status='COMPLETED';call.output=event.data.output;call.durationMs=typeof event.data.durationMs==='number'?event.data.durationMs:undefined;}
  if(event.type==='tool.failed'){call.status='FAILED';call.message=String(event.data.message||'执行失败');call.durationMs=typeof event.data.durationMs==='number'?event.data.durationMs:undefined;}
  map.set(id,call);
 }
 const ended=new Set(events.filter(e=>['run.cancelled','run.failed','run.completed','run.waiting_input'].includes(e.type)).map(e=>e.runId));
 const groups=new Map<string,WorkspaceToolGroup>();
 for(const call of map.values()){
  if(call.firstEventId<=clearedThrough)continue;
  if(call.status==='RUNNING'&&ended.has(call.runId)){call.status='STOPPED';call.message='已停止';}
  const previous=groups.get(call.tool),calls=[...(previous?.calls||[]),call];
  const active=[...calls].reverse().find(c=>c.status==='RUNNING');
  const durations=calls.flatMap(c=>c.durationMs==null?[]:[c.durationMs]);
  groups.set(call.tool,{...call,id:call.tool,calls,status:active?'RUNNING':call.status,message:active?'':call.message,durationMs:durations.length?durations.reduce((a,b)=>a+b,0):undefined});
 }
 return [...groups.values()];
}
export function workspaceSources(events:RunEvent[],saved:Source[]=[],artifact:Source[]=[],clearedThrough=0):Source[]{
 const candidates:Source[]=[];
 const hiddenCalls=new Set(events.filter(e=>e.type==='tool.started'&&e.id<=clearedThrough).map(e=>e.runId+':'+(e.toolCallId||String(e.data.toolCallId||e.id))));
 for(const e of events){
  if(e.id<=clearedThrough||e.type!=='tool.completed')continue;
  if(hiddenCalls.has(e.runId+':'+(e.toolCallId||String(e.data.toolCallId||e.id))))continue;
  const output=e.data.output as {sources?:unknown}|undefined;
  if(Array.isArray(output?.sources))candidates.push(...output.sources);
 }
 if(!clearedThrough)candidates.push(...saved,...artifact);
 const found=new Map<string,Source>(),titles=new Map<string,string>();
 for(const source of candidates){
  if(!source||typeof source.chunkId!=='string'||typeof source.documentName!=='string'||typeof source.text!=='string')continue;
  let key=source.documentId+':'+source.chunkId;
  if(source.sourceUrl){try{const url=new URL(source.sourceUrl);url.hash='';for(const param of [...url.searchParams.keys()])if(/^(utm_|spm$|from$|source$)/i.test(param))url.searchParams.delete(param);key=url.toString();}catch{/* Keep the stable evidence identifier for malformed links. */}}
  const news=source.documentId.startsWith('web-'),title=source.documentName.replace(/\s+/g,'').toLowerCase();
  const previousKey=news?titles.get(title):undefined;
  if(previousKey&&previousKey!==key)continue;
  if(news)titles.set(title,key);
  found.set(key,source);
 }
 return [...found.values()];
}
