import {mount, flushPromises} from '@vue/test-utils';
import ElementPlus from 'element-plus';
import {createMemoryHistory, createRouter} from 'vue-router';
import {it,expect,vi} from 'vitest';
import WorkAttachmentPicker from '../components/WorkAttachmentPicker.vue';
import ConversationTurn from '../components/ConversationTurn.vue';
import {conversationApi} from '../lib/conversation-api';
import {conversation, production} from './conversation-fixtures';

it('exports an owned work as an actual document attachment without modifying its source', async()=>{
 const work=conversation({artifact:{id:'artifact-one',createdAt:'2026-10-27T06:00:00Z',updatedAt:'2026-10-27T06:00:00Z',title:production.title,versionNumber:2,status:'DRAFT',content:production,sources:[]}});
 const list=vi.spyOn(conversationApi,'list').mockResolvedValue([work]);
 const exported=vi.spyOn(conversationApi,'export').mockResolvedValue(new Blob(['# 原方案\n完整脚本和分镜'],{type:'text/markdown'}));
 const save=vi.spyOn(conversationApi,'save');
 const wrapper=mount(WorkAttachmentPicker,{props:{modelValue:false},global:{plugins:[ElementPlus],stubs:{Teleport:true}}});
 await wrapper.setProps({modelValue:true});await flushPromises();
 await wrapper.get('.work-picker-row').trigger('click');await flushPromises();
 expect(exported).toHaveBeenCalledWith(work.id,'markdown');
 const file=wrapper.emitted('select')![0]![0] as File;
 expect(file).toBeInstanceOf(File);expect(file.name).toBe(production.title+'.md');expect(file.size).toBeGreaterThan(0);
 expect(save).not.toHaveBeenCalled();
 wrapper.unmount();list.mockRestore();exported.mockRestore();save.mockRestore();
});

it('links a completed generated artifact to the readable work page',async()=>{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div />'}},{path:'/works/:id',component:{template:'<div />'}}]});await router.push('/');
 const wrapper=mount(ConversationTurn,{props:{conversationId:'chat-one',message:{id:'reply',role:'assistant',content:'方案已经完成。',runId:'run-one',createdAt:'2026-10-27T06:00:00Z',choices:[]},events:[{id:1,type:'artifact.updated',runId:'run-one',createdAt:'2026-10-27T06:00:00Z',data:{artifact:{title:production.title,versionNumber:2}}}]},global:{plugins:[ElementPlus,router]}});
 expect(wrapper.get('.message-work-card').text()).toContain(production.title);
 expect(wrapper.get('.message-work-card').text()).toContain('V2');
 expect(wrapper.get('.message-work-card').attributes('href')).toBe('/works/chat-one');
 wrapper.unmount();
});
