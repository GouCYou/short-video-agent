<script setup lang="ts">
import type {Brief} from '../lib/conversation-types';
const brief=defineModel<Brief>({required:true});
defineProps<{currentConversation?:boolean;disabled?:boolean}>();
</script>
<template>
 <div class="creation-requirements-editor">
  <div class="creation-settings-grid">
   <label v-if="currentConversation">主题<el-input v-model="brief.topic" :disabled="disabled" maxlength="2000" /></label>
   <label v-if="currentConversation">表达方向<el-input v-model="brief.direction" :disabled="disabled" maxlength="2000" /></label>
   <label>发布平台<el-select v-model="brief.platform" filterable allow-create default-first-option :disabled="disabled"><el-option v-for="v in ['抖音','小红书','哔哩哔哩','视频号']" :key="v" :label="v" :value="v" /></el-select></label>
   <label>时长（秒）<el-input-number v-model="brief.duration" :min="10" :max="600" :precision="0" :disabled="disabled" /></label>
   <label>表达风格<el-select v-model="brief.style" filterable allow-create default-first-option :disabled="disabled"><el-option v-for="v in ['自然亲切','轻松幽默','专业清晰','纪实真实']" :key="v" :label="v" :value="v" /></el-select></label>
   <label>制作方式<el-select v-model="brief.productionMode" filterable allow-create clearable default-first-option :disabled="disabled"><el-option v-for="v in ['真人口播','手机实拍','实拍加旁白','图文混剪']" :key="v" :label="v" :value="v" /></el-select></label>
   <label>目标观众<el-input v-model="brief.audience" maxlength="2000" :disabled="disabled" /></label>
   <label>可用设备<el-input v-model="brief.equipment" maxlength="2000" :disabled="disabled" /></label>
  </div>
  <label class="creation-settings-extra">其他要求<el-input v-model="brief.requirements" type="textarea" :autosize="{minRows:3,maxRows:7}" maxlength="6000" :disabled="disabled" /></label>
 </div>
</template>
