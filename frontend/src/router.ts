import { createRouter, createWebHistory } from 'vue-router'
import { ApiError, forgetToken, get, me } from './api'
import type { Identity, Role } from './types'
const roles: Role[] = ['TECHNICIAN', 'SUPERVISOR']
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('./views/Message.vue') },
    { path: '/login', name: 'login', component: () => import('./views/Auth.vue') },
    { path: '/change-password', name: 'password', component: () => import('./views/Auth.vue') },
    { path: '/orders', name: 'orders', component: () => import('./views/Orders.vue'), meta: { roles } },
    {
      path: '/orders/new',
      name: 'new',
      component: () => import('./views/OrderForm.vue'),
      meta: { roles: ['TECHNICIAN'] },
    },
    {
      path: '/orders/:id/edit',
      name: 'edit',
      component: () => import('./views/OrderForm.vue'),
      meta: { roles: ['TECHNICIAN'] },
    },
    { path: '/orders/:id', name: 'detail', component: () => import('./views/Detail.vue'), meta: { roles } },
    {
      path: '/equipment',
      name: 'equipment',
      component: () => import('./views/Catalog.vue'),
      meta: { roles },
    },
    {
      path: '/admin/users',
      name: 'users',
      component: () => import('./views/Catalog.vue'),
      meta: { roles: ['ADMIN'] },
    },
    { path: '/forbidden', name: 'forbidden', component: () => import('./views/Message.vue') },
    { path: '/connection', name: 'connection', component: () => import('./views/Message.vue') },
    { path: '/:pathMatch(.*)*', name: 'missing', component: () => import('./views/Message.vue') },
  ],
  scrollBehavior(_to, _from, saved) {
    return saved || { top: 0 }
  },
})
let initialized = false
const returnKey = 'workbench:return'
export const home = () => (me.value?.role === 'ADMIN' ? '/admin/users' : '/orders')
function permitted(path: string) {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\')) return false
  const r = router.resolve(path)
  return Boolean(me.value && r.meta.roles && (r.meta.roles as Role[]).includes(me.value.role))
}
export function landing() {
  if (me.value?.mustChangePassword) return '/change-password'
  const path = sessionStorage.getItem(returnKey) || ''
  sessionStorage.removeItem(returnKey)
  return permitted(path) ? path : home()
}
export function clearSession(explicit = false) {
  me.value = null
  forgetToken()
  if (explicit) sessionStorage.removeItem(returnKey)
}
router.beforeEach(async (to) => {
  if (to.name === 'connection') return true
  if (!initialized) {
    try {
      me.value = await get<Identity>('/auth/me', {}, undefined, false)
      initialized = true
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) initialized = true
      else return { name: 'connection' }
    }
  }
  if (!me.value) {
    if (to.name === 'login') return true
    if (to.meta.roles) sessionStorage.setItem(returnKey, to.fullPath)
    return { name: 'login' }
  }
  if (me.value.mustChangePassword && to.name !== 'password') return { name: 'password' }
  if (to.name === 'login' || to.name === 'home') return landing()
  if (to.meta.roles && !(to.meta.roles as Role[]).includes(me.value.role)) return { name: 'forbidden' }
  return true
})
window.addEventListener('identity-required', (event) => {
  const code = (event as CustomEvent<string>).detail
  if (code === 'AUTH_REQUIRED') {
    const current = router.currentRoute.value
    if (current.meta.roles) sessionStorage.setItem(returnKey, current.fullPath)
    clearSession()
    void router.replace('/login')
  } else if (me.value) {
    me.value.mustChangePassword = true
    void router.replace('/change-password')
  }
})
export function backToList(): string {
  const ref = window.history.state?.listFrom
  return typeof ref === 'string' && router.resolve(ref).name === 'orders' ? ref : '/orders'
}
