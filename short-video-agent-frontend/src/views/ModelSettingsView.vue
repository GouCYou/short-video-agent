<script setup lang="ts">
import {computed,onMounted,ref} from 'vue';
import {ElMessage} from 'element-plus';
import {PhPlugsConnected,PhKey,PhPencilSimple,PhCheck} from '@phosphor-icons/vue';
import {modelSettingsApi,type ModelSettings} from '../lib/model-settings';
import {errorMessage} from '../lib/api';
import LoadError from '../components/LoadError.vue';
const current=ref<ModelSettings>(),model=ref(''),baseUrl=ref(''),key=ref(''),replacing=ref(false),loading=ref(false),saving=ref(false),error=ref('');
const dirty=computed(()=>!!current.value&&(model.value!==current.value.model||baseUrl.value!==current.value.baseUrl||!!key.value));
function accept(value:ModelSettings){current.value=value;model.value=value.model;baseUrl.value=value.baseUrl;key.value='';replacing.value=!value.hasKey;}
async function load(){loading.value=true;try{accept(await modelSettingsApi.get());error.value='';}catch(e){error.value=errorMessage(e);}finally{loading.value=false;}}
async function save(){
 if(!current.value||saving.value||!model.value.trim()||!baseUrl.value.trim())return;
 if(replacing.value&&!key.value.trim()){ElMessage.warning('请填写新的 API 密钥');return;}
 saving.value=true;
 try{accept(await modelSettingsApi.save({model:model.value.trim(),baseUrl:baseUrl.value.trim(),revision:current.value.revision,...(key.value.trim()?{apiKey:key.value.trim()}: {})}));ElMessage.success('配置已保存，新请求立即生效');}catch(e){ElMessage.error(errorMessage(e));}finally{saving.value=false;}
}
function cancelKey(){key.value='';replacing.value=false;}
onMounted(load);
</script>
<template>
 <div class="admin-model-settings" v-loading="loading">
  <header class="page-heading"><div><h1>模型接入</h1></div><span class="model-format-badge"><PhPlugsConnected :size="18" />OpenAI 兼容格式</span></header>
  <LoadError v-if="error" :message="error" @retry="load" />
  <section v-else-if="current" class="settings-card model-connection-card">
   <header class="settings-card-heading"><h2>对话模型</h2><span class="model-live-status"><PhCheck :size="16" />热保存</span></header>
   <el-form label-position="top" @submit.prevent="save">
    <el-form-item label="模型 ID"><el-input v-model="model" :disabled="saving" maxlength="200" autocomplete="off" /></el-form-item>
    <el-form-item label="调用地址"><el-input v-model="baseUrl" :disabled="saving" maxlength="2000" autocomplete="off" /><p class="model-field-note">填写 OpenAI 兼容基础地址，例如 https://api.example.com/v1，请求使用 /chat/completions。</p></el-form-item>
    <div class="model-key-heading"><label for="model-replacement-key">API 密钥</label><button v-if="current.hasKey&&!replacing" type="button" class="model-replace-key" :disabled="saving" @click="replacing=true"><PhPencilSimple :size="16" />替换密钥</button><button v-else-if="current.hasKey" type="button" class="model-replace-key" :disabled="saving" @click="cancelKey">取消替换</button></div>
    <div v-if="!replacing" class="model-masked-key" aria-label="已保存的密钥，仅显示部分字符" @copy.prevent @cut.prevent @contextmenu.prevent @dragstart.prevent><PhKey :size="20" /><span>{{current.maskedKey||'尚未配置'}}</span></div>
    <el-input v-else id="model-replacement-key" v-model="key" type="password" :disabled="saving" maxlength="2000" autocomplete="new-password" placeholder="输入新密钥" @copy.prevent @cut.prevent />
    <footer class="settings-card-footer model-settings-footer"><p>保存后立即用于新请求，进行中的回复不受影响。</p><el-button native-type="submit" type="primary" :loading="saving" :disabled="!dirty||!model.trim()||!baseUrl.trim()||(replacing&&!key.trim())">保存并应用</el-button></footer>
   </el-form>
  </section>
 </div>
</template>
