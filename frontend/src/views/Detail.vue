<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Pencil, Play, Check, ScanText, FileText, History, CornerDownLeft } from 'lucide-vue-next'
import { ApiError, get, write, notify, errorText, fieldErrors } from '../api'
import { type Detail, type Side, type Algorithm, type Excerpt, stateNames, shTime } from '../types'
import { backToList } from '../router'
import Dialog from '../components/Dialog.vue'
import Problem from '../components/Problem.vue'
const id = String(useRoute().params.id),
  listFrom = backToList(),
  data = ref<Detail | null>(null),
  working = ref(false),
  loading = ref(true),
  err = ref(''),
  actionErr = ref(''),
  fields = ref<Record<string, string>>({}),
  formOpen = ref(false),
  draft = ref(''),
  baseAction = ref(''),
  expected = ref(0),
  needsRefresh = ref(false)
const selected = ref<{ side: Side; excerpt: Excerpt; algo: Algorithm } | null>(null)
const sides: Side[] = ['fault', 'action'],
  algos: Algorithm[] = ['TFIDF', 'TEXTRANK'],
  sideNames = { fault: '故障现象', action: '处理措施' },
  algoNames = { TFIDF: 'TF-IDF', TEXTRANK: 'TextRank' },
  events = { CREATED: '创建工单', STARTED: '开始处理', COMPLETED: '完成工单' }
const sortedEvents = computed(() =>
  [...(data.value?.events || [])].sort(
    (a, b) => a.occurredAt.localeCompare(b.occurredAt) || (BigInt(a.id) < BigInt(b.id) ? -1 : 1)
  )
)
async function load() {
  loading.value = true
  err.value = ''
  selected.value = null
  try {
    data.value = await get<Detail>('/orders/' + id)
  } catch (e) {
    data.value = null
    err.value = errorText(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
function complete() {
  if (!data.value) return
  draft.value = data.value.order.actionText
  baseAction.value = draft.value
  expected.value = data.value.order.version
  actionErr.value = ''
  needsRefresh.value = false
  fields.value = {}
  formOpen.value = true
}
async function transition(action: 'START' | 'COMPLETE') {
  if (!data.value || working.value) return
  working.value = true
  actionErr.value = ''
  fields.value = {}
  const v = action === 'COMPLETE' ? expected.value : data.value.order.version
  try {
    const body: Record<string, unknown> = { action, version: v }
    if (action === 'COMPLETE' && draft.value !== baseAction.value) body.actionText = draft.value
    await write('/orders/' + id + '/transitions', 'POST', body)
    formOpen.value = false
    notify(action === 'START' ? '已开始处理' : '工单已完成')
    await load()
  } catch (e) {
    actionErr.value = errorText(e)
    fields.value = fieldErrors(e)
    if (e instanceof ApiError && (e.status === 409 || e.status === 0)) needsRefresh.value = true
  } finally {
    working.value = false
  }
}
async function refreshAndReview() {
  await load()
  if (!data.value) return
  expected.value = data.value.order.version
  if (draft.value === baseAction.value) draft.value = data.value.order.actionText
  baseAction.value = data.value.order.actionText
  needsRefresh.value = false
  actionErr.value = formOpen.value ? '已读取最新版本，请核对当前原文和草稿后提交。' : ''
}
function raw(side: Side) {
  return (side === 'fault' ? data.value?.order.faultText : data.value?.order.actionText) || ''
}
async function locate(side: Side, algo: Algorithm, e: Excerpt) {
  if (e.start === null || e.end === null || e.sentence === null) return
  selected.value = { side, algo, excerpt: e }
  await nextTick()
  document.getElementById('hit-' + side)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}
</script>
<template>
  <router-link :to="listFrom" class="backlink"><ArrowLeft />返回工单记录</router-link>
  <div v-if="loading && !data" class="state-line">正在读取详情…</div>
  <Problem :message="err"><button class="text-button" @click="load">重新加载</button></Problem>
  <template v-if="data"
    ><div class="detail-no">
      <span class="mono">{{ data.order.orderNo }}</span
      ><span class="pill" :data-state="data.order.status">{{ stateNames[data.order.status] }}</span>
    </div>
    <div class="page-head detail-title">
      <h1>{{ data.order.title }}</h1>
      <div class="buttons">
        <router-link
          v-if="data.permissions.canEdit"
          :to="{ path: '/orders/' + id + '/edit', state: { listFrom } }"
          class="button"
          ><Pencil />编辑</router-link
        ><button
          v-if="data.permissions.canStart"
          class="primary"
          :disabled="working || needsRefresh"
          @click="transition('START')"
        >
          <Play />开始处理</button
        ><button v-if="data.permissions.canComplete" class="primary" :disabled="working" @click="complete">
          <Check />完成工单
        </button>
      </div>
    </div>
    <Problem v-if="!formOpen" :message="actionErr" :fields="fields"
      ><button v-if="needsRefresh" class="text-button" @click="refreshAndReview">
        重新加载最新版本
      </button></Problem
    >
    <dl class="facts">
      <div>
        <dt>设备</dt>
        <dd>
          <router-link :to="{ path: '/orders', query: { equipmentId: data.order.equipmentId } }"
            >{{ data.order.equipmentCode }} · {{ data.order.equipmentName }}</router-link
          >
        </dd>
      </div>
      <div>
        <dt>创建人</dt>
        <dd>{{ data.order.creatorName }}</dd>
      </div>
      <div>
        <dt>创建时间</dt>
        <dd>{{ shTime(data.order.createdAt) }}</dd>
      </div>
      <div>
        <dt>最近修改</dt>
        <dd>{{ shTime(data.order.updatedAt) }}</dd>
      </div>
    </dl>
    <section class="panel detail-panel">
      <div class="panel-head">
        <h2><FileText />工单原文</h2>
      </div>
      <div class="source-grid">
        <div v-for="side in sides" :key="side">
          <h3>{{ side === 'fault' ? '故障描述' : '处理经过' }}</h3>
          <p
            class="raw"
            v-if="selected?.side === side && selected.excerpt.start !== null && selected.excerpt.end !== null"
          >
            {{ raw(side).slice(0, selected.excerpt.start)
            }}<mark :id="'hit-' + side">{{
              raw(side).slice(selected.excerpt.start, selected.excerpt.end)
            }}</mark
            >{{ raw(side).slice(selected.excerpt.end) }}
          </p>
          <p v-else class="raw" :class="{ muted: !raw(side) }">{{ raw(side) || '尚未记录' }}</p>
        </div>
      </div>
    </section>
    <section class="panel detail-panel">
      <div class="panel-head">
        <h2><ScanText />摘要对照</h2>
        <small class="muted">点击摘要定位原句</small>
      </div>
      <div class="compare-grid">
        <div v-for="algo in algos" :key="algo" class="algorithm">
          <h3>{{ algoNames[algo] }}</h3>
          <div v-for="side in sides" :key="side" class="excerpt">
            <h4>{{ sideNames[side] }}</h4>
            <button
              v-if="data.summaries[algo][side].sentence !== null"
              class="sentence"
              :class="{ chosen: selected?.algo === algo && selected?.side === side }"
              @click="locate(side, algo, data.summaries[algo][side])"
            >
              {{ data.summaries[algo][side].sentence }} <CornerDownLeft />
            </button>
            <p v-else class="muted">尚未记录</p>
            <div class="keywords">
              <span v-for="k in data.summaries[algo][side].keywords" :key="k">{{ k }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>
    <section class="panel detail-panel">
      <div class="panel-head">
        <h2><History />流转记录</h2>
      </div>
      <ol class="timeline">
        <li v-for="ev in sortedEvents" :key="ev.id">
          <div>
            <b>{{ events[ev.type] }}</b
            ><span>{{ ev.actorName }}</span>
          </div>
          <time>{{ shTime(ev.occurredAt) }}</time>
        </li>
      </ol>
    </section></template
  >
  <Dialog v-if="formOpen" title="完成工单" :busy="working" @close="formOpen = false"
    ><form class="dialog-body" @submit.prevent="transition('COMPLETE')">
      <Problem :message="actionErr" :fields="fields"
        ><button v-if="needsRefresh" type="button" class="text-button" @click="refreshAndReview">
          重新加载最新版本
        </button></Problem
      >
      <p v-if="!needsRefresh && data && baseAction" class="current-copy">当前记录：{{ baseAction }}</p>
      <label>最终处理经过<textarea v-model="draft" required rows="5"></textarea></label>
      <footer>
        <button type="button" class="button" @click="formOpen = false">取消</button
        ><button class="primary" :disabled="working || needsRefresh || !data?.permissions.canComplete">
          保存并完成
        </button>
      </footer>
    </form></Dialog
  >
</template>
