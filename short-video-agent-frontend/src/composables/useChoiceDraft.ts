import {ref,watch,type Ref} from 'vue';
import type {ChatMessage,Selection} from '../lib/conversation-types';
export function useChoiceDraft(composer:Ref<string>){
 const pending=ref<{selection:Selection;seed:string}|null>(null);
 function stage(message:ChatMessage,choice:Omit<Selection,'messageId'>){
  const group=message.choices.find(g=>g.id===choice.groupId);
  if(!group||group.status!=='OPEN')return false;
  const option=group.options.find(o=>o.id===choice.optionId);
  if(!option)return false;
  const seed=`我选择 ${option.label}：${option.value}`;
  composer.value=seed;
  pending.value={selection:{messageId:message.id,groupId:group.id,optionId:option.id},seed};
  return true;
 }
 watch(composer,value=>{if(pending.value&&!value.startsWith(pending.value.seed))pending.value=null;});
 function selection():Selection|undefined{
  const value=pending.value;
  return value&&composer.value.startsWith(value.seed)?{...value.selection}:undefined;
 }
 return {stage,selection,clear:()=>{pending.value=null;}};
}
