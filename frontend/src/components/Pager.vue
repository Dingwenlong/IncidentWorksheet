<script setup lang="ts">
import { computed } from 'vue'
import { ElPagination, ElSelect, ElOption } from 'element-plus'
import 'element-plus/es/components/pagination/style/css'
import 'element-plus/es/components/select/style/css'

const props = defineProps<{ page: number; pageSize: number; total: number }>()
const emit = defineEmits<{ change: [page: number, pageSize: number] }>()
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
const sizes = computed(() => [...new Set([10, 20, 50, 100, props.pageSize])].sort((a, b) => a - b))
</script>
<template>
  <nav class="pager work-pager" aria-label="结果分页">
    <span>共 <b>{{ total }}</b> 条</span>
    <div class="page-controls">
      <ElSelect
        :model-value="pageSize"
        size="small"
        aria-label="每页条数"
        class="size-picker"
        @update:model-value="emit('change', 1, $event)"
      >
        <ElOption v-for="n in sizes" :key="n" :label="n + ' 条 / 页'" :value="n" />
      </ElSelect>
      <ElPagination
        layout="prev, slot, next"
        size="small"
        :current-page="page"
        :page-count="Math.max(page, pages)"
        @update:current-page="emit('change', $event, pageSize)"
      >
        <span class="page-index" aria-live="polite">{{ page }} / {{ pages }}</span>
      </ElPagination>
    </div>
  </nav>
</template>
<style scoped>
.work-pager .page-controls {
  flex-wrap: wrap;
}
.size-picker {
  width: 110px;
}
.size-picker :deep(.el-select__input) {
  background: transparent;
  border: 0;
  padding: 0;
}
.page-index {
  padding-inline: 5px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
</style>