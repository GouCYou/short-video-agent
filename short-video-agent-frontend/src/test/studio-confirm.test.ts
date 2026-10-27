import {mount,flushPromises} from '@vue/test-utils';
import {it,expect,vi} from 'vitest';
import StudioConfirm from '../components/StudioConfirm.vue';
it('uses a native modal, requires a separate confirmation click and blocks dismissal while busy',async()=>{
 const show=vi.fn(function(this:HTMLDialogElement){this.setAttribute('open','');Object.defineProperty(this,'open',{configurable:true,value:true});});
 const close=vi.fn(function(this:HTMLDialogElement){this.removeAttribute('open');Object.defineProperty(this,'open',{configurable:true,value:false});});
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value:show});
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value:close});
 const wrapper=mount(StudioConfirm,{props:{modelValue:false,title:'清空工作区记录？',description:'执行记录和历史新闻将清空。',busy:false},global:{stubs:{Teleport:true}}});
 await wrapper.setProps({modelValue:true});await flushPromises();expect(show).toHaveBeenCalledOnce();expect(wrapper.find('.el-message-box').exists()).toBe(false);expect(wrapper.emitted('confirm')).toBeUndefined();
 await wrapper.get('.studio-confirm-submit').trigger('click');expect(wrapper.emitted('confirm')).toHaveLength(1);
 await wrapper.setProps({busy:true});await wrapper.get('dialog').trigger('cancel');expect(wrapper.emitted('update:modelValue')).toBeUndefined();
 await wrapper.setProps({busy:false});await wrapper.get('dialog').trigger('cancel');expect(wrapper.emitted('update:modelValue')!.at(-1)).toEqual([false]);
 wrapper.unmount();
});
