<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import { ElDialog } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'

const props = defineProps<{ title: string; busy?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null

function requestClose() {
  if (!props.busy) emit('close')
}

// The parent also unmounts the modal directly after a successful form submission.
onBeforeUnmount(() => {
  queueMicrotask(() => {
    if (opener?.isConnected) opener.focus()
  })
})
</script>
<template>
  <ElDialog
    :model-value="true"
    :title="title"
    class="dialog work-dialog"
    width="min(470px, 94vw)"
    align-center
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    :before-close="requestClose"
  >
    <template #header="{ titleId }">
      <h2 :id="titleId">{{ title }}</h2>
    </template>
    <slot />
  </ElDialog>
</template>
<style>
.el-dialog.work-dialog {
  padding: 0;
  max-height: 90dvh;
  overflow-y: auto;
  overscroll-behavior: contain;
  border-radius: 12px;
}
.work-dialog .el-dialog__header {
  position: relative;
  padding: 15px 48px 15px 23px;
  margin: 0;
}
.work-dialog .el-dialog__headerbtn {
  top: 50%;
  transform: translateY(-50%);
}
</style>