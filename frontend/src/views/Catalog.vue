<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, Search, Archive, UsersRound, ArrowUpRight, RefreshCw } from 'lucide-vue-next'
import { ElButton, ElInput, ElOption, ElSelect } from 'element-plus'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import type { Equipment, User, Enabled, Role, Page } from '../types'
import { roleNames, stateNames, shTime, chars } from '../types'
import { get, write, me, notify, errorText, fieldErrors, conflicted } from '../api'
import Problem from '../components/Problem.vue'
import Pager from '../components/Pager.vue'
import Dialog from '../components/Dialog.vue'
type RecordRow = Equipment | User
type Mode = 'create' | 'edit' | 'status' | 'password'
const users = useRoute().name === 'users',
  noun = users ? '账号' : '设备',
  title = users ? '账号管理' : '设备台账'
const endpoint = users ? '/users' : '/equipment',
  manager = computed(() => users || me.value?.role === 'SUPERVISOR')
const query = reactive({ code: '', name: '', role: '', status: '' }),
  applied = ref({ ...query })
const result = ref<Page<RecordRow>>({ items: [], page: 1, pageSize: 20, total: 0 }),
  page = ref(1),
  size = ref(20)
const loading = ref(false),
  loadFail = ref('')
let sequence = 0
const modal = ref<Mode | null>(null),
  selected = ref<RecordRow | null>(null),
  saving = ref(false),
  err = ref(''),
  errors = ref<Record<string, string>>({}),
  stale = ref(false)
const model = reactive({
  code: '',
  name: '',
  location: '',
  role: 'TECHNICIAN' as Role,
  password: '',
  repeat: '',
})
const modalTitle = computed(() =>
  modal.value === 'create'
    ? '新增' + noun
    : modal.value === 'edit'
      ? '编辑' + noun
      : modal.value === 'password'
        ? '重置密码'
        : (selected.value?.status === 'ACTIVE' ? '停用' : '启用') + noun
)
function code(r: RecordRow) {
  return 'username' in r ? r.username : r.code
}
function name(r: RecordRow) {
  return 'displayName' in r ? r.displayName : r.name
}
async function load() {
  const seq = ++sequence
  loading.value = true
  loadFail.value = ''
  const f = applied.value
  try {
    const data = await get<Page<RecordRow>>(endpoint, {
      page: page.value,
      pageSize: size.value,
      status: f.status,
      ...(users ? { username: f.code, displayName: f.name, role: f.role } : { code: f.code, name: f.name }),
    })
    if (seq === sequence) result.value = data
  } catch (e) {
    if (seq === sequence) loadFail.value = errorText(e)
  } finally {
    if (seq === sequence) loading.value = false
  }
}
function search() {
  applied.value = { ...query }
  page.value = 1
  void load()
}
function clear() {
  Object.assign(query, { code: '', name: '', role: '', status: '' })
  search()
}
function paginate(p: number, n: number) {
  page.value = p
  size.value = n
  void load()
}
function edit(mode: Mode, row: RecordRow | null = null) {
  selected.value = row
  err.value = ''
  errors.value = {}
  stale.value = false
  Object.assign(model, {
    code: row ? code(row) : '',
    name: row ? name(row) : '',
    role: row && 'role' in row ? row.role : 'TECHNICIAN',
    location: row && 'location' in row ? row.location || '' : '',
    password: '',
    repeat: '',
  })
  modal.value = mode
}
async function save() {
  if (saving.value || !modal.value) return
  err.value = ''
  errors.value = {}
  stale.value = false
  if (
    users &&
    (modal.value === 'create' || modal.value === 'password') &&
    (chars(model.password) < 8 || chars(model.password) > 64)
  ) {
    err.value = '密码须为 8 至 64 个字符。'
    return
  }
  if (modal.value === 'password' && model.repeat !== model.password) {
    err.value = '两次输入的密码不一致。'
    return
  }
  const row = selected.value,
    path = endpoint + '/' + row?.id
  saving.value = true
  try {
    if (modal.value === 'create') {
      await write(
        endpoint,
        'POST',
        users
          ? { username: model.code, displayName: model.name, role: model.role, password: model.password }
          : { code: model.code, name: model.name, location: model.location || null }
      )
    } else if (row && modal.value === 'edit') {
      await write(
        path,
        'PATCH',
        users
          ? { version: row.version, displayName: model.name }
          : { version: row.version, name: model.name, location: model.location || null }
      )
    } else if (row && modal.value === 'password') {
      await write(path + '/reset-password', 'POST', { version: row.version, newPassword: model.password })
    } else if (row && modal.value === 'status') {
      await write(path + '/status', 'PUT', {
        version: row.version,
        status: row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
      })
    }
    notify(modalTitle.value + '成功')
    modal.value = null
    await load()
  } catch (e) {
    err.value = errorText(e)
    errors.value = fieldErrors(e)
    stale.value = conflicted(e)
  } finally {
    saving.value = false
  }
}
function reloadDialog() {
  modal.value = null
  void load()
}
onMounted(load)
</script>
<template>
  <div class="page-head catalog-heading">
    <h1>{{ title }}</h1>
    <ElButton v-if="manager" type="primary" :icon="Plus" @click="edit('create')">新增{{ noun }}</ElButton>
  </div>
  <form class="filter-bar catalog-filters" @submit.prevent="search">
    <label for="catalog-query-code">
      {{ users ? '用户名' : '设备编号' }}
      <ElInput id="catalog-query-code" v-model="query.code" :placeholder="users ? '输入用户名' : '输入编号'" />
    </label>
    <label for="catalog-query-name">
      {{ users ? '显示名' : '设备名称' }}
      <ElInput id="catalog-query-name" v-model="query.name" placeholder="输入名称" />
    </label>
    <div v-if="users" class="field">
      <label for="catalog-query-role">角色</label>
      <ElSelect id="catalog-query-role" v-model="query.role" aria-label="筛选角色" :empty-values="[null, undefined]">
        <ElOption value="" label="全部角色" />
        <ElOption v-for="(txt, r) in roleNames" :value="r" :key="r" :label="txt" />
      </ElSelect>
    </div>
    <div class="field">
      <label for="catalog-query-status">状态</label>
      <ElSelect id="catalog-query-status" v-model="query.status" aria-label="筛选状态" :empty-values="[null, undefined]">
        <ElOption value="" label="全部状态" />
        <ElOption value="ACTIVE" label="启用" />
        <ElOption value="DISABLED" label="停用" />
      </ElSelect>
    </div>
    <div class="form-actions">
      <ElButton class="search-action" native-type="submit" :icon="Search">查询</ElButton>
      <ElButton link type="primary" @click="clear">清空</ElButton>
    </div>
  </form>
  <section class="panel catalog-panel">
    <div class="panel-head">
      <h2>{{ users ? '账号列表' : '设备列表' }}</h2>
      <button class="icon-btn" aria-label="刷新列表" @click="load"><RefreshCw /></button>
    </div>
    <Problem :message="loadFail"><button class="text-button" @click="load">重新加载</button></Problem>
    <div v-if="loading" class="state-line" role="status">正在加载…</div>
    <div v-else-if="!result.items.length && !loadFail" class="empty">
      <UsersRound v-if="users" /><Archive v-else />
      <h3>未找到匹配{{ noun }}</h3>
      <button class="text-button" @click="clear">清空筛选条件</button>
    </div>
    <div v-else-if="result.items.length" class="table-scroll">
      <table class="catalog-table" :class="{ 'accounts-table': users }" :aria-label="noun + '列表'">
        <thead>
          <tr>
            <th>{{ users ? '用户名' : '设备编号' }}</th>
            <th>{{ users ? '显示名' : '设备名称' }}</th>
            <th>{{ users ? '角色' : '安装位置' }}</th>
            <th>状态</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in result.items" :key="row.id">
            <td>
              <div v-if="'username' in row" class="account-handle">
                <span class="account-initial" :data-role="row.role" aria-hidden="true">{{ Array.from(row.displayName.trim())[0] }}</span>
                <span>{{ row.username }}</span>
              </div>
              <span v-else class="mono">{{ row.code }}</span>
            </td>
            <td class="strong" :data-label="users ? '显示名' : '设备名称'">{{ name(row) }}</td>
            <td :data-label="users ? '角色' : '安装位置'">{{ 'role' in row ? roleNames[row.role] : row.location || '—' }}</td>
            <td>
              <span class="pill" :data-state="row.status">{{ stateNames[row.status] }}</span>
            </td>
            <td class="time" data-label="更新">{{ shTime(row.updatedAt) }}</td>
            <td>
              <div class="row-actions">
                <router-link v-if="!users" :to="{ path: '/orders', query: { equipmentId: row.id } }"
                  >历史工单<ArrowUpRight /></router-link
                ><template v-if="manager"
                  ><button @click="edit('edit', row)">编辑</button
                  ><button v-if="!users || row.id !== me?.id" @click="edit('status', row)">
                    {{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button
                  ><button v-if="users" @click="edit('password', row)">重置密码</button></template
                >
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <Pager :page="page" :page-size="size" :total="result.total" @change="paginate" />
  </section>
  <Dialog v-if="modal" :title="modalTitle" :busy="saving" @close="modal = null"
    ><form class="dialog-body" @submit.prevent="save">
      <Problem :message="err" :fields="errors"
        ><button v-if="stale" type="button" class="text-button" @click="reloadDialog">
          重新加载列表
        </button></Problem
      >
      <template v-if="modal === 'create' || modal === 'edit'">
        <label for="catalog-code">
          {{ users ? '用户名' : '设备编号' }}
          <ElInput
            id="catalog-code"
            v-model="model.code"
            :disabled="modal === 'edit'"
            required
            :pattern="users ? '[A-Za-z0-9_]{4,32}' : undefined"
            :title="users ? '4 至 32 位字母、数字或下划线' : undefined" />
        </label>
        <label for="catalog-name">
          {{ users ? '显示名' : '设备名称' }}
          <ElInput id="catalog-name" v-model="model.name" required />
        </label>
        <div v-if="users" class="field">
          <label for="catalog-role">角色</label>
          <ElSelect id="catalog-role" v-model="model.role" aria-label="账号角色" :disabled="modal === 'edit'">
            <ElOption v-for="(txt, r) in roleNames" :value="r" :key="r" :label="txt" />
          </ElSelect>
        </div>
        <label v-else for="catalog-location">
          安装位置
          <ElInput id="catalog-location" v-model="model.location" placeholder="选填" />
        </label>
      </template>
      <label v-if="users && (modal === 'create' || modal === 'password')" for="catalog-password">
        {{ modal === 'password' ? '临时新密码' : '初始密码' }}
        <ElInput
          id="catalog-password"
          type="password"
          v-model="model.password"
          required
          autocomplete="new-password"
          placeholder="8 至 64 个字符" />
      </label>
      <label v-if="modal === 'password'" for="catalog-confirm">
        确认临时新密码
        <ElInput id="catalog-confirm" v-model="model.repeat" type="password" required autocomplete="new-password" />
      </label>
      <p v-if="modal === 'status' && selected">
        确定{{ selected.status === 'ACTIVE' ? '停用' : '启用' }}{{ noun }}「{{ name(selected) }}（{{
          code(selected)
        }}）」？
      </p>
      <footer>
        <ElButton :disabled="saving" @click="modal = null">取消</ElButton>
        <ElButton type="primary" native-type="submit" :loading="saving" :disabled="saving || stale">
          {{ saving ? '保存中…' : modal === 'status' ? modalTitle : '保存' }}
        </ElButton>
      </footer>
    </form></Dialog
  >
</template>
<style scoped>
.catalog-heading h1 {
  font-weight: 700;
  letter-spacing: -.035em;
}
.catalog-heading .el-button {
  min-height: 40px;
  padding-inline: 18px;
  font-weight: 600;
}
.catalog-filters {
  align-items: flex-end;
  gap: clamp(12px, 1.4vw, 22px);
}
.catalog-filters label {
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
}
.catalog-filters :deep(.el-input__wrapper),
.catalog-filters :deep(.el-select__wrapper) {
  min-height: 38px;
  font-weight: 400;
}
.catalog-filters .el-button {
  min-height: 38px;
  font-weight: 600;
}
.search-action {
  --el-button-text-color: var(--accent);
  --el-button-border-color: var(--accent);
  padding-inline: 16px;
}
.catalog-panel .panel-head {
  min-height: 66px;
  padding-inline: 20px;
}
.catalog-panel .panel-head h2 {
  font-size: clamp(19px, 1.6vw, 24px);
  font-weight: 650;
}
.catalog-table {
  min-width: 716px;
  font-size: clamp(14px, 1.12vw, 16px);
}
.catalog-table th {
  padding: 13px 14px;
  background: #eff4f1;
  color: #526459;
  font-size: 13px;
  font-weight: 600;
}
.catalog-table td {
  padding: 14px;
  line-height: 1.5;
}
.catalog-table :is(th, td):first-child {
  padding-left: 20px;
}
.accounts-table th:first-child {
  padding-left: 66px;
}
.catalog-table :is(th, td):last-child {
  padding-right: 20px;
}
.account-handle {
  display: flex;
  align-items: center;
  gap: 12px;
}
.account-initial {
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #637e92;
  color: white;
  font-weight: 650;
}
.account-initial[data-role='SUPERVISOR'] {
  background: #827360;
}
.account-initial[data-role='ADMIN'] {
  background: #638065;
}
.catalog-table .strong {
  font-weight: 650;
}
.catalog-table .time {
  color: #58675f;
  font-variant-numeric: tabular-nums;
}
.catalog-table .pill {
  padding: 3px 7px;
  border: 1px solid #cbd5cf;
  background: #f5f7f5;
  color: #59645d;
  font-size: 12px;
  font-weight: 600;
}
.catalog-table .pill[data-state='ACTIVE'] {
  border-color: #b5d7bc;
  background: #f3faf3;
  color: #26713d;
}
.catalog-table .row-actions {
  gap: clamp(8px, 1.3vw, 20px);
}
.catalog-table .row-actions :is(a, button) {
  font-size: 14px;
  font-weight: 600;
}
.form-actions .el-button + .el-button,
.dialog-body footer .el-button + .el-button {
  margin-left: 0;
}
@media (max-width: 720px) {
  .catalog-filters {
    gap: 12px;
  }
  .catalog-panel .panel-head {
    min-height: 54px;
    padding-inline: 14px;
  }
  .catalog-table :is(th, td) {
    padding-inline: 10px;
  }
}
@media (max-width: 620px) {
  .catalog-table, .catalog-table tbody {
    display: block;
    width: 100%;
    min-width: 0;
  }
  .catalog-table thead {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
  }
  .catalog-table tbody tr {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 9px 12px;
    padding: 15px;
    border-bottom: 1px solid var(--line);
  }
  .catalog-table tbody td {
    display: block;
    width: auto;
    padding: 0;
    border: 0;
    text-align: left;
  }
  .catalog-table td:nth-child(1) { grid-area: 1 / 1; align-self: center; }
  .catalog-table td:nth-child(4) { grid-area: 1 / 2; align-self: center; }
  .catalog-table td:nth-child(2) { grid-area: 2 / 1; }
  .catalog-table td:nth-child(3) { grid-area: 2 / 2; }
  .catalog-table td:nth-child(5), .catalog-table td:nth-child(6) { grid-column: 1 / -1; }
  .catalog-table td[data-label]::before {
    content: attr(data-label);
    color: var(--muted);
    font-size: 12px;
    font-weight: 400;
    margin-right: 6px;
  }
  .catalog-table .row-actions { flex-wrap: wrap; gap: 18px; }
}
</style>
