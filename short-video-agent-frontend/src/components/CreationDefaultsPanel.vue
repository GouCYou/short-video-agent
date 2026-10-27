<script setup lang="ts">
import {onMounted,ref} from 'vue';
import {ElMessage,ElMessageBox} from 'element-plus';
import {creationSettingsApi} from '../lib/creation-settings';
import type {Brief} from '../lib/conversation-types';
import {errorMessage} from '../lib/api';
import CreationRequirementsEditor from './CreationRequirementsEditor.vue';
const brief=ref<Brief>(),busy=ref(false),error=ref('');
async function load(){busy.value=true;try{brief.value=await creationSettingsApi.get();error.value='';}catch(e){error.value=errorMessage(e);}finally{busy.value=false;}}
async function save(){if(!brief.value)return;busy.value=true;try{brief.value=await creationSettingsApi.save(brief.value);ElMessage.success('创作默认值已保存');}catch(e){ElMessage.error(errorMessage(e));}finally{busy.value=false;}}
async function reset(){try{await ElMessageBox.confirm('恢复系统创作默认值？已有对话的要求不会改变。','恢复默认',{confirmButtonText:'恢复默认',cancelButtonText:'取消'});}catch{return;}busy.value=true;try{brief.value=await creationSettingsApi.reset();ElMessage.success('已恢复默认');}catch(e){ElMessage.error(errorMessage(e));}finally{busy.value=false;}}
onMounted(load);
</script>
<template><section class="settings-card defaults-settings-card" v-loading="busy"><header class="settings-card-heading"><h2>内容与制作</h2><el-button :disabled="busy" @click="reset">恢复默认</el-button></header><p class="settings-context">用于新对话，当前对话可单独调整。</p><p v-if="error" role="alert">{{error}}<el-button @click="load">重试</el-button></p><CreationRequirementsEditor v-if="brief" v-model="brief" :disabled="busy" /><footer class="settings-card-footer"><el-button type="primary" :loading="busy" :disabled="!brief" @click="save">保存默认值</el-button></footer></section></template>
