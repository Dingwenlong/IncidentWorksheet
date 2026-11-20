<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronDown, Search, Check, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { get, errorText } from '../api'
import type { Equipment, Page } from '../types'
const props = withDefaults(
  defineProps<{ modelValue: string; activeOnly?: boolean; label?: string; placeholder?: string }>(),
  { placeholder: '全部设备' }
)
const emit = defineEmits<{ 'update:modelValue': [string] }>(),
  root = ref<HTMLElement | null>(null)
const open = ref(false),
  term = ref(''),
  by = ref('name'),
  page = ref(1),
  list = ref<Page<Equipment> | null>(null),
  error = ref(''),
  loading = ref(false),
  selected = ref<Equipment | null>(null)
let seq = 0
let timer: ReturnType<typeof setTimeout>
async function load() {
  const id = ++seq
  loading.value = true
  error.value = ''
  try {
    const data = await get<Page<Equipment>>('/equipment', {
      [by.value]: term.value.trim(),
      status: props.activeOnly ? 'ACTIVE' : '',
      page: page.value,
      pageSize: 10,
    })
    if (id === seq) list.value = data
  } catch (e) {
    if (id === seq) {
      error.value = errorText(e)
      list.value = null
    }
  } finally {
    if (id === seq) loading.value = false
  }
}
function shiftPage(delta: number) {
  page.value += delta
  void load()
}
function toggle() {
  open.value = !open.value
  if (open.value) {
    term.value = ''
    page.value = 1
    void load()
  }
}
function pick(row: Equipment | null) {
  selected.value = row
  emit('update:modelValue', row?.id || '')
  open.value = false
}
function search() {
  clearTimeout(timer)
  seq++
  loading.value = true
  page.value = 1
  timer = setTimeout(load, 180)
}
function clickaway(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
watch(
  () => props.modelValue,
  (v) => {
    if (selected.value?.id !== v) selected.value = null
  }
)
onMounted(() => document.addEventListener('click', clickaway))
onBeforeUnmount(() => {
  clearTimeout(timer)
  document.removeEventListener('click', clickaway)
  seq++
})
</script>
<template>
  <div ref="root" class="picker" @keydown.esc.stop="open = false">
    <button
      type="button"
      class="select-trigger"
      :aria-expanded="open"
      :aria-label="'选择设备，' + (selected?.name || label || modelValue || placeholder)"
      @click="toggle"
    >
      <span>{{
        selected
          ? selected.code + ' · ' + selected.name
          : label || (modelValue ? '设备 #' + modelValue : placeholder)
      }}</span
      ><ChevronDown />
    </button>
    <div v-if="open" class="picker-menu">
      <div class="picker-filter">
        <select aria-label="设备检索方式" v-model="by" @change="search">
          <option value="name">名称</option>
          <option value="code">编号</option></select
        ><input
          v-model="term"
          aria-label="搜索设备"
          placeholder="搜索设备"
          @input="search"
          @keydown.enter.prevent="load"
        />
      </div>
      <div v-if="loading" class="picker-info">正在加载…</div>
      <div v-else-if="error" class="picker-info">
        {{ error }} <button type="button" class="text-button" @click="load">重试</button>
      </div>
      <template v-else
        ><button v-if="!activeOnly" type="button" class="option" @click="pick(null)">全部设备</button
        ><button type="button" class="option" v-for="eq in list?.items" :key="eq.id" @click="pick(eq)">
          <span
            >{{ eq.name }}<small>{{ eq.code }} {{ eq.status === 'DISABLED' ? '· 已停用' : '' }}</small></span
          ><Check v-if="modelValue === eq.id" />
        </button>
        <div v-if="!list?.items.length" class="picker-info">没有匹配的设备</div></template
      >
      <div v-if="list && list.total > 10" class="picker-pages">
        <button
          type="button"
          class="icon-btn"
          :disabled="page === 1"
          aria-label="上一页设备"
          @click="shiftPage(-1)"
        >
          <ChevronLeft /></button
        ><span>{{ page }} / {{ Math.ceil(list.total / 10) }}</span
        ><button
          type="button"
          class="icon-btn"
          :disabled="page >= Math.ceil(list.total / 10)"
          aria-label="下一页设备"
          @click="shiftPage(1)"
        >
          <ChevronRight />
        </button>
      </div>
    </div>
  </div>
</template>
