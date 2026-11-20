<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { UserRound, LockKeyhole, ArrowRight, Eye, EyeOff, LogOut } from 'lucide-vue-next'
import { csrf, forgetToken, get, me, write, notify, errorText, fieldErrors } from '../api'
import { clearSession, landing, home } from '../router'
import type { Identity, Role } from '../types'
import { chars } from '../types'
import Problem from '../components/Problem.vue'
const route = useRoute(),
  router = useRouter(),
  change = computed(() => route.name === 'password')
const username = ref(''),
  password = ref(''),
  newPass = ref(''),
  repeat = ref(''),
  visible = ref(false),
  busy = ref(false),
  err = ref(''),
  fields = ref<Record<string, string>>({})
const testAccounts: { role: Role; label: string }[] = [
  { role: 'ADMIN', label: '管理员测试' },
  { role: 'SUPERVISOR', label: '主管测试' },
  { role: 'TECHNICIAN', label: '维修员测试' },
]
const testsEnabled = ref(false)
const pendingRole = ref<Role | null>(null)

onMounted(() => {
  csrf().catch((e) => (err.value = errorText(e)))
  if (!change.value) {
    get<{ enabled: boolean }>('/auth/test-options', {}, undefined, false)
      .then((options) => { testsEnabled.value = options.enabled === true })
      .catch(() => { testsEnabled.value = false })
  }
})
async function logout() {
  busy.value = true
  try {
    await write('/auth/logout', 'POST')
    clearSession(true)
    await router.replace('/login')
  } catch (e) {
    err.value = errorText(e)
  } finally {
    busy.value = false
  }
}
async function submit(role?: Role) {
  if (busy.value) return
  err.value = ''
  fields.value = {}
  if (
    change.value &&
    (newPass.value !== repeat.value || chars(newPass.value) < 8 || chars(newPass.value) > 64)
  ) {
    err.value = newPass.value !== repeat.value ? '两次输入的新密码不一致。' : '新密码须为 8 至 64 个字符。'
    return
  }
  busy.value = true
  pendingRole.value = role ?? null
  try {
    if (change.value) {
      await write('/auth/password', 'PUT', { currentPassword: password.value, newPassword: newPass.value })
      clearSession()
      notify('密码已更新，请重新登录')
      await router.replace('/login')
    } else {
      const identity = role
        ? await write<Identity>('/auth/test-login', 'POST', { role })
        : await write<Identity>('/auth/login', 'POST', { username: username.value.trim(), password: password.value })
      if (role) clearSession(true)
      me.value = identity
      forgetToken()
      await csrf()
      await router.replace(landing())
    }
  } catch (e) {
    err.value = errorText(e)
    fields.value = fieldErrors(e)
    if (!change.value && !role) password.value = ''
  } finally {
    pendingRole.value = null
    busy.value = false
  }
}
</script>
<template>
  <main class="auth-page">
    <section class="auth-art">
      <div class="auth-legend"><span class="line"></span>设备维修管理</div>
      <h1>故障工单<br />摘要系统</h1>
      <div class="machine" aria-hidden="true"></div>
    </section>
    <section class="auth-right">
      <form @submit.prevent="submit()" class="auth-form">
        <h2>{{ change ? '修改密码' : '账号登录' }}</h2>
        <p v-if="change && me?.mustChangePassword" class="muted">首次登录或密码已重置，请先设置新密码。</p>
        <Problem :message="err" :fields="fields" /><label v-if="!change"
          >用户名
          <div class="input-icon">
            <UserRound /><input
              v-model="username"
              autocomplete="username"
              required
              placeholder="请输入用户名"
              autocapitalize="none"
            /></div
        ></label>
        <label
          >{{ change ? '当前密码' : '密码' }}
          <div class="input-icon">
            <LockKeyhole /><input
              v-model="password"
              :type="visible ? 'text' : 'password'"
              autocomplete="current-password"
              required
              placeholder="请输入密码"
            /><button
              type="button"
              class="bare"
              :aria-label="visible ? '隐藏密码' : '显示密码'"
              @click="visible = !visible"
            >
              <EyeOff v-if="visible" /><Eye v-else />
            </button></div
        ></label>
        <template v-if="change"
          ><label
            >新密码<input
              type="password"
              v-model="newPass"
              required
              autocomplete="new-password"
              placeholder="8 至 64 个字符" /></label
          ><label
            >确认新密码<input type="password" v-model="repeat" required autocomplete="new-password" /></label
        ></template>
        <button class="primary wide" :disabled="busy">
          {{ busy && !pendingRole ? '处理中…' : change ? '保存新密码' : '登录系统' }}<ArrowRight :size="17" />
        </button>
        <div v-if="!change && testsEnabled" class="test-accounts" role="group" aria-label="测试账号登录">
          <button
            v-for="account in testAccounts"
            :key="account.role"
            type="button"
            :disabled="busy"
            @click="submit(account.role)"
          >{{ pendingRole === account.role ? '登录中…' : account.label }}</button>
        </div>
        <div v-if="change" class="auth-links">
          <router-link v-if="!me?.mustChangePassword" :to="home()">返回首页</router-link
          ><button type="button" class="text-button" :disabled="busy" @click="logout">
            <LogOut :size="15" />退出登录
          </button>
        </div>
      </form>
    </section>
  </main>
</template>
<style scoped>
.test-accounts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  padding-top: 14px;
  border-top: 1px solid rgb(205 234 223 / 20%);
}
.test-accounts button {
  min-height: 38px;
  padding: 4px;
  background: rgb(9 38 34 / 26%);
  border: 1px solid rgb(205 234 223 / 36%);
  border-radius: 6px;
  color: #e7f5ef;
  font-size: 13px;
  white-space: nowrap;
}
.test-accounts button:hover:not(:disabled) {
  background: rgb(198 236 215 / 13%);
  border-color: #a1cdb9;
}
</style>
