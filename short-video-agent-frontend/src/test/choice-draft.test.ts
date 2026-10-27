import {ref,nextTick} from 'vue';
import {it,expect} from 'vitest';
import {useChoiceDraft} from '../composables/useChoiceDraft';
import {choiceGroup} from './conversation-fixtures';
import type {ChatMessage} from '../lib/conversation-types';
it('keeps appended notes editable and drops stale selection metadata after a full rewrite',async()=>{
 const composer=ref(''),draft=useChoiceDraft(composer),group=choiceGroup();
 const message:ChatMessage={id:'reply',role:'assistant',content:'',choices:[group],runId:'run',createdAt:''};
 expect(draft.stage(message,{groupId:group.id,customText:''})).toBe(false);
 draft.stage(message,{groupId:group.id,optionId:group.options[0]!.id});expect(composer.value).toBe('我选择 A：one');
 composer.value+='\n面向大三学生';await nextTick();expect(draft.selection()?.optionId).toBe(group.options[0]!.id);
 composer.value='直接按照我的新要求修改';await nextTick();expect(draft.selection()).toBeUndefined();
});
