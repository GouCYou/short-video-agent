import {it,expect} from 'vitest';
import {workspaceToolGroups,workspaceSources} from '../lib/workspace-feed';
import type {RunEvent} from '../lib/conversation-types';
import type {Source} from '../lib/types';
function event(id:number,type:RunEvent['type'],call:string,tool='search_news',data:Record<string,unknown>={},run='run-one'):RunEvent{return{id,type,runId:run,tool,toolCallId:call,data,createdAt:'2026-10-27T06:00:00Z'};}
function news(id:string,url='https://news.cctv.com/story.shtml',title='汽车新闻'):Source{return{documentId:'web-'+id,chunkId:id,documentName:title,text:'实际新闻资料',sourceUrl:url,score:0};}
it('shows each tool once while preserving every actual invocation and total duration',()=>{
 const events=[event(1,'tool.started','a'),event(2,'tool.completed','a','search_news',{durationMs:2000}),event(3,'tool.started','a','search_news',{},'run-two'),event(4,'tool.completed','a','search_news',{durationMs:1000},'run-two'),event(5,'tool.started','b','read_news')];
 const groups=workspaceToolGroups(events);expect(groups).toHaveLength(2);expect(groups[0]!.calls).toHaveLength(2);expect(groups[0]!.durationMs).toBe(3000);expect(groups[1]!.status).toBe('RUNNING');
});
it('keeps the grouped indicator running until all active calls finish',()=>{
 const groups=workspaceToolGroups([event(1,'tool.started','a'),event(2,'tool.started','b'),event(3,'tool.completed','b','search_news',{durationMs:1000})]);
 expect(groups).toHaveLength(1);expect(groups[0]!.status).toBe('RUNNING');
});
it('does not resurrect a cleared call when its completion arrives later',()=>{
 const events=[event(1,'tool.started','old'),event(3,'tool.completed','old','search_news',{output:{sources:[news('old')]}}),event(4,'tool.started','new'),event(5,'tool.completed','new','search_news',{output:{sources:[news('new','https://news.cctv.com/new.shtml','新新闻')]}})];
 expect(workspaceToolGroups(events,2)[0]!.calls.map(c=>c.id)).toEqual(['run-one:new']);
 expect(workspaceSources(events,[news('saved')],[news('artifact')],2).map(s=>s.chunkId)).toEqual(['new']);
 expect(workspaceSources(events,[news('saved')],[news('artifact')],5)).toEqual([]);
});
it('deduplicates original links, tracking variants and identical news headlines',()=>{
 const values=[news('one'),news('two','https://news.cctv.com/story.shtml?utm_source=share#top'),news('three','https://other.example/reprint','汽车新闻'),news('four','https://news.cctv.com/other.shtml','另一条新闻')];
 expect(workspaceSources([],values).map(s=>s.chunkId)).toEqual(['two','four']);
});
