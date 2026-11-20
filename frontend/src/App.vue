<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ClipboardList,
  Cpu,
  UserRound,
  KeyRound,
  LogOut,
  PanelLeft,
  X,
  Menu,
  ArrowUpRight,
  Check,
} from 'lucide-vue-next'
import { me, notice, write, notify, errorText } from './api'
import { clearSession, home } from './router'
import { roleNames } from './types'
const route = useRoute(),
  router = useRouter(),
  open = ref(false),
  loggingOut = ref(false)
const shell = computed(() => !!me.value && !['login', 'password', 'connection'].includes(String(route.name)))
const navigation: Record<string, [string, string]> = {
  orders: ['工单管理', '工单记录'],
  new: ['工单管理', '新建工单'],
  detail: ['工单管理', '工单详情'],
  edit: ['工单管理', '编辑工单'],
  equipment: ['设备管理', '设备台账'],
  users: ['系统管理', '账号管理'],
  forbidden: ['系统', '无权访问'],
  missing: ['系统', '页面不存在'],
}
const breadcrumb = computed(() => navigation[String(route.name)] ?? ['系统', '首页'])
router.afterEach(() => {
  open.value = false
})
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await write('/auth/logout', 'POST')
    clearSession(true)
    await router.replace('/login')
  } catch (e) {
    notify(errorText(e))
  } finally {
    loggingOut.value = false
  }
}
</script>
<template>
  <div v-if="shell && me" class="app">
    <button v-if="open" class="scrim" aria-label="关闭导航" @click="open = false"></button>
    <aside class="side" :class="{ open }">
      <router-link :to="home()" class="brand">
        <span class="brand-mark" aria-hidden="true">工</span><strong>故障工单</strong>
      </router-link>
      <button class="mobile-close icon-btn" aria-label="关闭导航" @click="open = false"><X /></button>
      <div id="nav-group" class="side-cap">{{ me.role === 'ADMIN' ? '系统管理' : '维修业务' }}</div>
      <nav aria-labelledby="nav-group">
        <router-link v-if="me.role === 'ADMIN'" to="/admin/users"><UserRound />账号管理</router-link>
        <template v-else
          ><router-link to="/orders" :class="{ selected: route.path.startsWith('/orders') }"
            ><ClipboardList />工单记录</router-link
          ><router-link to="/equipment"><Cpu />设备台账</router-link></template
        >
      </nav>
      <div class="side-bottom">
        <div class="profile">
          <span class="avatar" :data-role="me.role" aria-hidden="true">{{ Array.from(me.displayName)[0] }}</span>
          <div>
            <b>{{ me.displayName }}</b
            ><small>{{ roleNames[me.role] }}</small>
          </div>
        </div>
        <router-link to="/change-password" class="side-action"
          ><KeyRound />修改密码 <ArrowUpRight class="end"
        /></router-link>
        <button class="side-action" :disabled="loggingOut" @click="logout"><LogOut />退出登录</button>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <button class="mobile-toggle icon-btn" aria-label="打开导航" @click="open = true"><Menu /></button
        ><PanelLeft class="breadcrumb-icon" /><span>{{ breadcrumb[0] }}</span><i aria-hidden="true">/</i><strong aria-current="page">{{ breadcrumb[1] }}</strong>
      </header>
      <main id="main"><router-view :key="String(route.name) + String(route.params.id || '')" /></main>
    </div>
  </div>
  <router-view v-else :key="String(route.name)" />
  <Transition
    ><div v-if="notice" class="toast" role="status"><Check :size="16" />{{ notice }}</div></Transition
  >
</template>
