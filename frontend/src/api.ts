import { ref } from 'vue'
import type { Identity } from './types'

export const me = ref<Identity | null>(null)
export const notice = ref('')
let timer: ReturnType<typeof setTimeout>
export function notify(message: string) {
  notice.value = message
  clearTimeout(timer)
  timer = setTimeout(() => (notice.value = ''), 4000)
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields: Record<string, string> = {}
  ) {
    super(message)
  }
}
export const errorText = (e: unknown) => (e instanceof Error ? e.message : '请求失败，请重试。')
export const fieldErrors = (e: unknown) => (e instanceof ApiError ? e.fields : {})
export const conflicted = (e: unknown) => e instanceof ApiError && e.status === 409
let token: Promise<string> | null = null
export function forgetToken() {
  token = null
}
export function csrf(): Promise<string> {
  if (!token)
    token = read<{ token: string }>('/auth/csrf', {}, false)
      .then((x) => x.token)
      .catch((e) => {
        token = null
        throw e
      })
  return token
}
async function read<T>(path: string, init: RequestInit, handle = true): Promise<T> {
  let result: Response
  try {
    result = await fetch('/api' + path, { credentials: 'same-origin', ...init })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, 'NETWORK', '连接失败，请检查网络后重试。')
  }
  if (result.status === 204) return undefined as T
  const payload = await result.json().catch(() => null)
  if (!result.ok) {
    const e = new ApiError(
      result.status,
      payload?.code || 'HTTP_ERROR',
      payload?.message || '服务暂不可用，请重试。',
      payload?.fieldErrors || {}
    )
    if (e.code === 'INVALID_CSRF_TOKEN') {
      forgetToken()
      void csrf().catch(() => {})
      e.message = '页面令牌已失效，请再次提交。'
    }
    if (handle && (e.code === 'AUTH_REQUIRED' || e.code === 'PASSWORD_CHANGE_REQUIRED'))
      window.dispatchEvent(new CustomEvent('identity-required', { detail: e.code }))
    throw e
  }
  return payload as T
}
export const get = <T>(
  path: string,
  params: Record<string, unknown> = {},
  signal?: AbortSignal,
  handle = true
) => {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') query.set(k, String(v))
  })
  return read<T>(path + (query.size ? '?' + query.toString() : ''), { signal }, handle)
}
export async function write<T>(
  path: string,
  method: 'POST' | 'PUT' | 'PATCH',
  body?: unknown,
  extra: Record<string, string> = {}
): Promise<T> {
  const headers: Record<string, string> = { 'X-CSRF-TOKEN': await csrf(), ...extra }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  return read<T>(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
}
