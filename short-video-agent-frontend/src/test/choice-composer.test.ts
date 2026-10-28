import {mount,flushPromises} from '@vue/test-utils';
import ElementPlus from 'element-plus';
import {createPinia} from 'pinia';
import {createMemoryHistory,createRouter} from 'vue-router';
import {it,expect,vi,afterEach} from 'vitest';
import ConversationView from '../views/ConversationView.vue';
import {conversationApi} from '../lib/conversation-api';
import {choiceGroup,conversation} from './conversation-fixtures';
vi.mock('../lib/conversation-api',async importOriginal=>{
 const original=await importOriginal<typeof import('../lib/conversation-api')>();
 return {...original,conversationApi:{...original.conversationApi,get:vi.fn(),list:vi.fn(),events:vi.fn(),send:vi.fn()},streamConversation:vi.fn((_id:string,_after:number,signal:AbortSignal)=>new Promise<void>(resolve=>signal.addEventListener('abort',()=>resolve(),{once:true})))};
});
afterEach(()=>vi.unstubAllGlobals());
it('stages a choice without sending, then sends the choice and appended notes only after Enter',async()=>{
 vi.stubGlobal('crypto',{getRandomValues:globalThis.crypto.getRandomValues.bind(globalThis.crypto)});
 const group=choiceGroup();const value=conversation({messages:[{id:'answer',role:'assistant',content:'选择一个方向',choices:[group],runId:'old-run',createdAt:'2026-10-27T06:00:00Z'}]});
 vi.mocked(conversationApi.get).mockResolvedValue(value);vi.mocked(conversationApi.list).mockResolvedValue([value]);vi.mocked(conversationApi.events).mockResolvedValue([]);
 vi.mocked(conversationApi.send).mockResolvedValue({conversation:{...value,status:'RUNNING',activeRunId:'new-run'},runId:'new-run'});
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/conversations/:id',component:ConversationView,meta:{workspace:true}},{path:'/works/:id',component:{template:'<div />'}},{path:'/knowledge',component:{template:'<div />'}}]});await router.push('/conversations/chat-one');
 const wrapper=mount(ConversationView,{global:{plugins:[ElementPlus,createPinia(),router]}});await flushPromises();
 await wrapper.findAll('.choice-option')[0]!.trigger('click');await flushPromises();
 const input=wrapper.get('textarea[aria-label="发送消息"]');expect((input.element as HTMLTextAreaElement).value).toContain('我选择 A：one');expect(conversationApi.send).not.toHaveBeenCalled();
 await input.setValue('我选择 A：one\n表达更自然一点');await input.trigger('keydown',{key:'Enter',shiftKey:false,isComposing:false});await flushPromises();
 expect(conversationApi.send).toHaveBeenCalledOnce();expect(vi.mocked(conversationApi.send).mock.calls[0]![1]).toMatchObject({content:'我选择 A：one\n表达更自然一点',choice:{messageId:'answer',groupId:group.id,optionId:group.options[0]!.id}});
 wrapper.unmount();
});
