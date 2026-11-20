<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Save, Check, FileText } from 'lucide-vue-next'
import { ElButton, ElInput } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import { ApiError, get, write, notify, errorText, fieldErrors } from '../api'
import type { Order, Detail } from '../types'
import { chars, stateNames } from '../types'
import { backToList } from '../router'
import EquipmentPicker from '../components/EquipmentPicker.vue'
import Problem from '../components/Problem.vue'
const route = useRoute(),
  router = useRouter(),
  id = String(route.params.id || ''),
  creating = !id
const detail = ref<Detail | null>(null),
  model = reactive({ equipmentId: '', title: '', faultText: '', actionText: '' }),
  base = ref({ ...model }),
  busy = ref(false),
  loading = ref(!creating),
  err = ref(''),
  fields = ref<Record<string, string>>({}),
  conflict = ref(false),
  latest = ref<Detail | null>(null),
  readFailed = ref(false),
  pickerKey = ref(0)
const listFrom = backToList()
let leave = false,
  signature = '',
  key = ''
const changed = computed(() => JSON.stringify(model) !== JSON.stringify(base.value))
const editable = ['title', 'faultText', 'actionText'] as const
function copy(d: Detail) {
  return {
    equipmentId: d.order.equipmentId,
    title: d.order.title,
    faultText: d.order.faultText,
    actionText: d.order.actionText,
  }
}
async function load() {
  loading.value = true
  err.value = ''
  readFailed.value = false
  try {
    const d = await get<Detail>('/orders/' + id)
    detail.value = d
    if (!d.permissions.canEdit) {
      leave = true
      notify('该工单只可查看')
      await router.replace({ path: '/orders/' + id, state: { listFrom } })
      return
    }
    const f = copy(d)
    base.value = f
    Object.assign(model, f)
  } catch (e) {
    err.value = errorText(e)
    readFailed.value = true
  } finally {
    loading.value = false
  }
}
async function fetchLatest() {
  busy.value = true
  try {
    latest.value = await get<Detail>('/orders/' + id)
  } catch (e) {
    err.value = errorText(e)
  } finally {
    busy.value = false
  }
}
function rebase() {
  if (!latest.value) return
  const next = copy(latest.value)
  for (const k of editable) {
    if (model[k] === base.value[k]) model[k] = next[k]
  }
  detail.value = latest.value
  base.value = next
  latest.value = null
  conflict.value = false
  err.value = ''
  fields.value = {}
}
async function back() {
  await router.push(creating ? listFrom : { path: '/orders/' + id, state: { listFrom } })
}
onBeforeRouteLeave(() => !changed.value || leave || window.confirm('表单有未保存的修改，确定离开？'))
onMounted(() => {
  if (!creating) void load()
})
async function save(complete: boolean) {
  if (busy.value || conflict.value) return
  err.value = ''
  fields.value = {}
  if (!model.equipmentId) fields.value.equipmentId = '请选择启用的设备'
  if (!model.title.trim() || chars(model.title.trim()) > 100) fields.value.title = '标题须为 1 至 100 个字符'
  if (!model.faultText.trim() || chars(model.faultText) > 5000)
    fields.value.faultText = '请输入 1 至 5000 个字符的故障描述'
  if (chars(model.actionText) > 5000 || (complete && !model.actionText.trim()))
    fields.value.actionText = complete ? '完成前请填写处理经过，最多 5000 个字符' : '处理经过最多 5000 个字符'
  if (Object.keys(fields.value).length) {
    err.value = '请检查表单内容'
    return
  }
  busy.value = true
  try {
    let saved: Order
    if (creating) {
      const body = { ...model, title: model.title.trim() }
      const fingerprint = JSON.stringify(body)
      if (fingerprint !== signature) {
        key = crypto.randomUUID()
        signature = fingerprint
      }
      saved = await write<Order>('/orders', 'POST', body, { 'Idempotency-Key': key })
    } else {
      const d = detail.value!
      const changes: Record<string, string> = {}
      for (const k of editable) if (model[k] !== base.value[k]) changes[k] = model[k]
      if (!complete && !Object.keys(changes).length) {
        await back()
        return
      }
      saved = complete
        ? await write<Order>('/orders/' + id + '/transitions', 'POST', {
            version: d.order.version,
            action: 'COMPLETE',
            ...changes,
          })
        : await write<Order>('/orders/' + id, 'PATCH', { version: d.order.version, ...changes })
    }
    leave = true
    notify(complete ? '工单已完成' : '工单已保存')
    await router.replace({ path: '/orders/' + saved.id, state: { listFrom } })
  } catch (e) {
    err.value = errorText(e)
    fields.value = fieldErrors(e)
    if (e instanceof ApiError) {
      if (e.code === 'EQUIPMENT_DISABLED') {
        model.equipmentId = ''
        pickerKey.value++
      }
      if (!creating && (e.status === 409 || e.status === 0)) {
        conflict.value = true
        await fetchLatest()
      }
    }
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <button class="backlink" @click="back"><ArrowLeft />{{ creating ? '返回工单记录' : '返回详情' }}</button>
  <div class="page-head">
    <h1>{{ creating ? '新建工单' : '编辑工单' }}</h1>
    <span v-if="detail" class="pill" :data-state="detail.order.status">{{
      stateNames[detail.order.status]
    }}</span>
  </div>
  <div v-if="loading" class="state-line">正在读取工单…</div>
  <form v-else class="panel edit-panel" @submit.prevent="save(false)">
    <div class="panel-head">
      <h2><FileText />工单内容</h2>
      <span v-if="detail" class="mono">{{ detail.order.orderNo }}</span>
    </div>
    <div class="edit-body">
      <Problem :message="err" :fields="fields"
        ><button v-if="readFailed" type="button" class="text-button" @click="load">重新加载</button
        ><button v-if="conflict && !latest" type="button" @click="fetchLatest" class="text-button">
          读取最新版本
        </button></Problem
      >
      <div v-if="latest" class="conflict">
        <strong>服务器最新内容 · 版本 {{ latest.order.version }}</strong>
        <dl>
          <dt>标题</dt>
          <dd>{{ latest.order.title }}</dd>
          <dt>故障描述</dt>
          <dd>{{ latest.order.faultText }}</dd>
          <dt>处理经过</dt>
          <dd>{{ latest.order.actionText || '尚未记录' }}</dd>
        </dl>
        <button v-if="latest.permissions.canEdit" class="button" type="button" @click="rebase">
          保留草稿并基于此版本编辑</button
        ><span v-else>工单已不可编辑，可复制当前草稿后返回。</span>
      </div>
      <template v-if="creating || detail"
        ><div class="field">
          <span>设备 <em>*</em></span
          ><EquipmentPicker
            v-if="creating"
            :key="pickerKey"
            v-model="model.equipmentId"
            active-only
            placeholder="选择启用设备"
          />
          <div v-else class="readonly">
            {{ detail?.order.equipmentCode }} · {{ detail?.order.equipmentName }}
          </div>
          <small class="invalid">{{ fields.equipmentId }}</small>
        </div>
        <label for="order-title">
          <span>标题 <em>*</em></span>
          <ElInput id="order-title" v-model="model.title" placeholder="简述设备故障" required
            :aria-invalid="!!fields.title" aria-describedby="order-title-error" />
          <small id="order-title-error" class="invalid">{{ fields.title }}</small>
        </label>
        <label for="order-fault">
          <span>故障描述 <em>*</em></span>
          <ElInput
            id="order-fault"
            type="textarea"
            v-model="model.faultText"
            :rows="5"
            required
            :aria-invalid="!!fields.faultText"
            aria-describedby="order-fault-error order-fault-count"
            placeholder="填写发现的故障现象、故障码及影响"
          />
          <span class="field-tail">
            <small id="order-fault-error" class="invalid">{{ fields.faultText }}</small>
            <small id="order-fault-count">{{ chars(model.faultText) }} / 5000</small>
          </span>
        </label>
        <label for="order-action">
          <span>处理经过</span>
          <ElInput
            id="order-action"
            type="textarea"
            v-model="model.actionText"
            :rows="5"
            :aria-invalid="!!fields.actionText"
            aria-describedby="order-action-error order-action-count"
            placeholder="记录处置措施与结果，可在处理时补充"
          />
          <span class="field-tail">
            <small id="order-action-error" class="invalid">{{ fields.actionText }}</small>
            <small id="order-action-count">{{ chars(model.actionText) }} / 5000</small>
          </span>
        </label>
      </template>
    </div>
    <footer v-if="creating || detail">
      <ElButton @click="back">取消</ElButton>
      <ElButton type="primary" native-type="submit" :icon="Save" :loading="busy"
        :disabled="busy || conflict || (!creating && !changed)">
        {{ busy ? '提交中…' : '保存工单' }}
      </ElButton>
      <ElButton
        v-if="detail?.permissions.canComplete"
        :icon="Check"
        :disabled="busy || conflict"
        @click="save(true)"
      >
        保存并完成
      </ElButton>
    </footer>
  </form>
</template>
<style scoped>
.edit-panel footer .el-button + .el-button {
  margin-left: 0;
}
</style>
