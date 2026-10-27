<script setup lang="ts">
import {nextTick,onBeforeUnmount,ref,useId,watch} from 'vue';
import {PhX,PhCircleNotch} from '@phosphor-icons/vue';
const open=defineModel<boolean>({required:true});
const props=withDefaults(defineProps<{title:string;description:string;note?:string;busy?:boolean;confirmText?:string}>(),{confirmText:'清空'});
const emit=defineEmits<{confirm:[]}>();
const dialog=ref<HTMLDialogElement>(),closing=ref(false),id=useId();
let timer:ReturnType<typeof setTimeout>|undefined;
function dismiss(){if(!props.busy)open.value=false;}
watch(open,async value=>{
 clearTimeout(timer);await nextTick();
 if(!dialog.value)return;
 if(value){closing.value=false;if(!dialog.value.open)dialog.value.showModal();}
 else if(dialog.value.open){closing.value=true;timer=setTimeout(()=>{dialog.value?.close();closing.value=false;},150);}
},{immediate:true});
onBeforeUnmount(()=>{clearTimeout(timer);if(dialog.value?.open)dialog.value.close();});
</script>
<template>
 <Teleport to="body">
  <dialog ref="dialog" class="studio-confirm" :class="{'is-closing':closing}" :aria-labelledby="id+'-title'" :aria-describedby="id+'-description'" :aria-busy="busy" @cancel.prevent="dismiss" @click="event=>{if(event.target===dialog)dismiss();}">
   <div class="studio-confirm-surface">
    <button class="icon-button studio-confirm-close" aria-label="关闭确认弹窗" :disabled="busy || closing" @click="dismiss"><PhX :size="19" /></button>
    <h2 :id="id+'-title'">{{title}}</h2>
    <p :id="id+'-description'">{{description}}</p>
    <p v-if="note" class="studio-confirm-note">{{note}}</p>
    <footer>
     <button class="studio-confirm-cancel" :disabled="busy || closing" autofocus @click="dismiss">取消</button>
     <button class="studio-confirm-submit" :disabled="busy || closing" @click="!closing && open && emit('confirm')"><PhCircleNotch v-if="busy" class="agent-spinner" :size="17" />{{busy?'正在清空':confirmText}}</button>
    </footer>
   </div>
  </dialog>
 </Teleport>
</template>
