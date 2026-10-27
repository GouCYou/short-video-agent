<script setup lang="ts">
import {computed,ref} from 'vue';
import {PhDotsThree} from '@phosphor-icons/vue';
import type {ChoiceGroup,ChoiceOption} from '../lib/conversation-types';
import ChoiceDetails from './ChoiceDetails.vue';
const props=defineProps<{group:ChoiceGroup;disabled?:boolean}>();
const emit=defineEmits<{select:[selection:{groupId:string;optionId?:string;customText?:string}]}>();
type DetailOption=ChoiceOption;
const detailsOpen=ref(false),detail=ref<DetailOption|null>(null);
const locked=computed(()=>props.disabled||props.group.status!=='OPEN');
function select(optionId:string){if(!locked.value)emit('select',{groupId:props.group.id,optionId});}
function showDetails(option:DetailOption){detail.value=option;detailsOpen.value=true;}
function useDetail(){if(detail.value)select(detail.value.id);}
</script>
<template>
 <section class="choice-group" :class="{superseded:group.status==='SUPERSEDED'}">
  <h3>{{group.title}}</h3>
  <div class="choice-options">
   <div v-for="option in group.options" :key="option.id" class="choice-row">
    <button class="choice-option" :class="{selected:group.selectedOptionId===option.id}" :disabled="locked" :aria-pressed="group.selectedOptionId===option.id" @click="select(option.id)"><span class="choice-letter">{{option.label}}</span><span class="choice-copy"><strong>{{option.title}}</strong><span>{{option.description}}</span></span></button>
    <button class="icon-button choice-details-button" :aria-label="'查看 '+option.label+' 选项详情'" title="查看详情" @click="showDetails(option)"><PhDotsThree :size="21" weight="bold" /></button>
   </div>

  </div>
  <ChoiceDetails v-model="detailsOpen" :option="detail" :disabled="locked" @use="useDetail" />
 </section>
</template>
