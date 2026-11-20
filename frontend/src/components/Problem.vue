<script setup lang="ts">
import { ElAlert } from 'element-plus'
import 'element-plus/es/components/alert/style/css'

defineProps<{ message: string; fields?: Record<string, string> }>()
</script>
<template>
  <ElAlert v-if="message" class="alert work-alert" :title="message" type="error" :closable="false" show-icon>
    <template v-if="(fields && Object.keys(fields).length) || $slots.default" #default>
      <ul v-if="fields && Object.keys(fields).length">
        <li v-for="(msg, key) in fields" :key="key">{{ msg }}</li>
      </ul>
      <slot />
    </template>
  </ElAlert>
</template>
<style scoped>
.work-alert.el-alert {
  --el-alert-bg-color: #fcf3ed;
  --el-alert-text-color: #80442b;
  width: auto;
}
.work-alert :deep(.el-alert__icon) {
  flex: none;
  width: 17px;
  font-size: 17px;
}
.work-alert :deep(.el-alert__description) {
  margin: 0;
  color: inherit;
}
</style>