<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search, Plus, ClipboardList, ArrowUpRight, RefreshCw, Filter } from 'lucide-vue-next'
import { ElInput, ElSelect, ElOption } from 'element-plus'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import { get, errorText, fieldErrors, me } from '../api'
import type { Row, Page } from '../types'
import { stateNames, shTime } from '../types'
import EquipmentPicker from '../components/EquipmentPicker.vue'
import Pager from '../components/Pager.vue'
import Problem from '../components/Problem.vue'
const route = useRoute(),
  router = useRouter()
const filters = reactive({
  keyword: '',
  equipmentId: '',
  status: '',
  dateFrom: '',
  dateTo: '',
  creatorId: '',
})
type Filters = typeof filters
const result = ref<Page<Row>>({ items: [], total: 0, page: 1, pageSize: 20 }),
  busy = ref(false),
  err = ref(''),
  fields = ref<Record<string, string>>({})
const page = ref(1),
  size = ref(20),
  scope = computed(() => String(route.query.equipmentId || '')),
  mine = computed(() => !!me.value && route.query.creatorId === me.value.id)
const chosenName = computed(() => {
  const found = result.value.items.find((r) => r.equipmentId === filters.equipmentId)
  return found ? found.equipmentCode + ' · ' + found.equipmentName : ''
})
let seq = 0
function fromUrl() {
  for (const k of Object.keys(filters) as (keyof Filters)[]) {
    const raw = route.query[k]
    filters[k] = typeof raw === 'string' ? raw : ''
  }
  page.value = Number(route.query.page || 1)
  size.value = Number(route.query.pageSize || 20)
}
async function load() {
  const id = ++seq
  err.value = ''
  fields.value = {}
  busy.value = true
  try {
    const data = await get<Page<Row>>('/orders', { ...filters, page: page.value, pageSize: size.value })
    if (id === seq) result.value = data
  } catch (e) {
    if (id === seq) {
      err.value = errorText(e)
      fields.value = fieldErrors(e)
      result.value = { items: [], page: page.value, pageSize: size.value, total: 0 }
    }
  } finally {
    if (id === seq) busy.value = false
  }
}
function apply(p = 1, n = size.value) {
  const q: Record<string, string> = {}
  for (const [k, v] of Object.entries(filters)) if (v.trim()) q[k] = v.trim()
  if (p !== 1) q.page = String(p)
  if (n !== 20) q.pageSize = String(n)
  const dest = { path: '/orders', query: q }
  if (router.resolve(dest).fullPath === route.fullPath) {
    fromUrl()
    void load()
  } else void router.push(dest)
}
function refresh() {
  fromUrl()
  void load()
}
function changePage(p: number, n: number) {
  fromUrl()
  apply(p, n)
}
function clear() {
  Object.assign(filters, {
    keyword: '',
    equipmentId: scope.value,
    status: '',
    dateFrom: '',
    dateTo: '',
    creatorId: '',
  })
  apply()
}
function setMine(onlyMine: boolean) {
  fromUrl()
  filters.creatorId = onlyMine ? me.value?.id || '' : ''
  apply()
}
watch(
  () => route.fullPath,
  () => {
    if (route.name === 'orders') {
      fromUrl()
      void load()
    }
  },
  { immediate: true }
)
const rowLink = (id: string) => ({ path: '/orders/' + id, state: { listFrom: route.fullPath } })
</script>
<template>
  <div class="page-head">
    <div>
      <h1>{{ scope ? '设备历史工单' : '工单记录' }}</h1>
      <p v-if="scope" class="head-meta">
        设备 {{ chosenName || '#' + scope }}
        <router-link to="/orders" class="text-button">查看全部工单</router-link>
      </p>
    </div>
    <router-link
      v-if="me?.role === 'TECHNICIAN'"
      :to="{ path: '/orders/new', state: { listFrom: route.fullPath } }"
      class="primary"
      ><Plus />新建工单</router-link
    >
  </div>
  <form class="filter-bar order-filters" @submit.prevent="apply()">
    <label class="keyword" for="order-keyword">关键词
      <ElInput id="order-keyword" v-model="filters.keyword" size="large" clearable placeholder="工单号、设备或故障内容">
        <template #prefix><Search :size="16" /></template>
      </ElInput>
    </label>
    <div class="field">
      <span>设备</span><EquipmentPicker v-model="filters.equipmentId" :label="chosenName" />
    </div>
    <div class="field"><span id="order-status-label">状态</span>
      <ElSelect v-model="filters.status" size="large" aria-labelledby="order-status-label" :empty-values="[null, undefined]">
        <ElOption label="全部状态" value="" />
        <ElOption v-for="(label, value) in stateNames" :key="value" :label="label" :value="value" />
      </ElSelect>
    </div>
    <div class="field dates">
      <span>创建日期</span>
      <div>
        <input v-model="filters.dateFrom" type="date" aria-label="创建日期起" /><span>至</span
        ><input v-model="filters.dateTo" type="date" aria-label="创建日期止" />
      </div>
    </div>
    <div class="form-actions">
      <button class="button"><Filter />筛选</button
      ><button type="button" class="text-button" @click="clear">清空</button>
    </div>
  </form>
  <section class="panel orders-panel">
    <div class="list-head">
      <div class="tabs">
        <button :class="{ active: !mine }" @click="setMine(false)">全部工单</button
        ><button :class="{ active: mine }" @click="setMine(true)">我的工单</button>
      </div>
      <button
        class="icon-btn"
        aria-label="刷新工单"
        @click="refresh"
      >
        <RefreshCw />
      </button>
    </div>
    <Problem :message="err" :fields="fields"
      ><button
        class="text-button"
        @click="refresh"
      >
        重新加载
      </button></Problem
    >
    <div v-if="busy" class="state-line">正在读取工单…</div>
    <div v-else-if="!err && !result.items.length" class="empty">
      <ClipboardList />
      <h3>没有符合条件的工单</h3>
      <div class="row-actions">
        <button @click="clear">清空筛选条件</button
        ><router-link
          v-if="me?.role === 'TECHNICIAN'"
          :to="{ path: '/orders/new', state: { listFrom: route.fullPath } }"
          >新建工单<ArrowUpRight
        /></router-link>
      </div>
    </div>
    <div v-else-if="!err" class="order-list">
      <article v-for="row in result.items" :key="row.id" class="order-card">
        <div class="row-head">
          <span class="mono order-number">{{ row.orderNo }}</span
          ><span class="pill" :data-state="row.status">{{ stateNames[row.status] }}</span>
        </div>
        <router-link :to="rowLink(row.id)" class="order-title">{{ row.title }}<ArrowUpRight /></router-link>
        <div class="order-sub">
          <span>{{ row.equipmentCode }} · {{ row.equipmentName }}</span
          ><span>{{ row.creatorName }}</span
          ><time>{{ shTime(row.createdAt) }}</time>
        </div>
        <div class="summary-pair">
          <div>
            <b>故障现象</b>
            <p>{{ row.summary.fault }}</p>
          </div>
          <div>
            <b>处理措施</b>
            <p :class="{ muted: row.summary.action === null }">{{ row.summary.action ?? '尚未记录' }}</p>
          </div>
        </div>
      </article>
    </div>
    <Pager v-if="!err" :page="page" :page-size="size" :total="result.total" @change="changePage" />
  </section>
</template>
