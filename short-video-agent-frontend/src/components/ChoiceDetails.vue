<script setup lang="ts">
import {nextTick,onBeforeUnmount,ref,useId,watch} from 'vue';
import {PhX} from '@phosphor-icons/vue';
import type {ChoiceOption} from '../lib/conversation-types';
const open=defineModel<boolean>({required:true});
defineProps<{option:(Omit<ChoiceOption,'label'>&{label:string})|null;disabled?:boolean}>();
const emit=defineEmits<{use:[]}>(),dialog=ref<HTMLDialogElement>(),id=useId();
watch(open,async value=>{await nextTick();if(value&&!dialog.value?.open)dialog.value?.showModal();else if(!value&&dialog.value?.open)dialog.value.close();},{immediate:true});
onBeforeUnmount(()=>{if(dialog.value?.open)dialog.value.close();});
function use(){emit('use');open.value=false;}
</script>
<template><Teleport to="body"><dialog ref="dialog" class="choice-details-dialog" :aria-labelledby="id+'-title'" @cancel.prevent="open=false" @click="event=>{if(event.target===dialog)open=false;}"><div v-if="option" class="choice-details-surface"><button class="icon-button choice-details-close" aria-label="关闭选项详情" @click="open=false"><PhX :size="19" /></button><h2 :id="id+'-title'">{{option.title}}</h2><p>{{option.description}}</p><section v-if="option.value&&option.value!==option.description&&option.value!==option.title"><h3>选择内容</h3><p>{{option.value}}</p></section><footer><button class="choice-details-dismiss" autofocus @click="open=false">关闭</button><button class="choice-details-use" :disabled="disabled" @click="use">使用这个选项</button></footer></div></dialog></Teleport></template>
