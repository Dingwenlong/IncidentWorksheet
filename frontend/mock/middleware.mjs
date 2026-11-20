import { handleAuth, handleUsers } from './auth.mjs'
import { createMockState } from './fixtures.mjs'
import { MockError, fail, readJson } from './http.mjs'
import { handleEquipment, handleOrders } from './work.mjs'

const cookieName = 'WORKORDER_MOCK_SESSION'
const ttl = 30 * 60
const publicPaths = new Set([
  '/api/auth/csrf', '/api/auth/test-options', '/api/auth/login', '/api/auth/test-login',
])
const changePasswordPaths = new Set(['/api/auth/me', '/api/auth/password', '/api/auth/logout'])
const handlers = { auth: handleAuth, users: handleUsers, equipment: handleEquipment, orders: handleOrders }

function send(response, status, payload) {
  response.statusCode = status
  response.setHeader('Cache-Control', 'no-store')
  if (payload === undefined) return response.end()
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  response.end(JSON.stringify(payload))
}

function cookieId(header) {
  const prefix = cookieName + '='
  return header?.split(';').map((cookie) => cookie.trim())
    .find((cookie) => cookie.startsWith(prefix))?.slice(prefix.length)
}

export function createMockMiddleware() {
  const state = createMockState()

  return async (request, response, next) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1')
    const path = url.pathname
    if (path !== '/api' && !path.startsWith('/api/')) return next()

    const method = request.method ?? 'GET'
    const respond = (status, payload) => send(response, status, payload)
    const setCookie = (id) => response.setHeader(
      'Set-Cookie',
      `${cookieName}=${id ?? ''}; Path=/api; HttpOnly; SameSite=Strict${id ? '' : '; Max-Age=0'}`,
    )
    let session = state.sessions.get(cookieId(request.headers.cookie))
    const now = Date.now()
    if (session && session.expiresAt <= now) {
      state.sessions.delete(session.id)
      session = undefined
    }
    if (session) session.expiresAt = now + ttl * 1000
    const data = session?.scope === 'demo' ? state.demo : state.primary
    const user = data.users.get(session?.userId)
    const [, , collection, id] = path.split('/')

    try {
      if (!publicPaths.has(path)) {
        if (!user || user.status !== 'ACTIVE' || session.authRevision !== user.authRevision) {
          if (session?.userId) {
            state.sessions.delete(session.id)
            setCookie(null)
          }
          fail(401, 'AUTH_REQUIRED', '登录已失效，请重新登录')
        }
        if (user.mustChangePassword && !changePasswordPaths.has(path)) {
          fail(403, 'PASSWORD_CHANGE_REQUIRED', '请先修改密码')
        }
      }

      const writes = !['GET', 'HEAD', 'OPTIONS'].includes(method)
      if (writes && (!session || request.headers['x-csrf-token'] !== session.token)) {
        fail(403, 'INVALID_CSRF_TOKEN', '表单令牌已失效，请重新提交')
      }
      if (collection === 'users' && user.role !== 'ADMIN') {
        fail(403, 'FORBIDDEN', '无权执行此操作')
      }
      const handler = Object.hasOwn(handlers, collection) ? handlers[collection] : undefined
      if (!handler) fail(404, 'NOT_FOUND', '记录不存在')
      const body = writes ? await readJson(request) : undefined
      await handler({
        method, path, query: url.searchParams, params: { id }, body,
        headers: request.headers, session, user, data, state, respond, setCookie,
      })
    } catch (error) {
      if (error instanceof MockError) return respond(error.status, error.payload)
      console.error('[workorder mock]', error)
      return respond(500, { code: 'MOCK_ERROR', message: 'Mock 请求处理失败' })
    }
  }
}
