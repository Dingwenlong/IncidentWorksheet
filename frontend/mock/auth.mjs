import { fail, invalid, page } from './http.mjs'

const demoUsers = {
  ADMIN: 'demo_admin',
  SUPERVISOR: 'demo_supervisor',
  TECHNICIAN: 'demo_technician',
}
const roles = Object.keys(demoUsers)
const statuses = ['ACTIVE', 'DISABLED']

function fields(body, allowed) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) {
    invalid('body', '请提供 JSON 对象')
  }
  for (const key of Object.keys(body)) {
    if (!allowed.includes(key)) invalid(key, '不支持该字段')
  }
}

function readText(body, key, min, max, trim = false) {
  if (typeof body[key] !== 'string') invalid(key, '请填写文本')
  const text = trim ? body[key].trim() : body[key]
  const length = Array.from(text).length
  if (length < min || length > max) invalid(key, `长度应为 ${min} 至 ${max} 个字符`)
  return text
}

function readUsername(body) {
  const text = readText(body, 'username', 4, 32, true)
  if (!/^[A-Za-z0-9_]{4,32}$/.test(text)) invalid('username', '用户名为4至32位字母、数字或下划线')
  return text.toLowerCase()
}

function choice(key, value, choices, message = '取值无效') {
  if (!choices.includes(value)) invalid(key, message)
  return value
}

function findUser(users, username) {
  return [...users.values()].find(user => user.username === username)
}

function identity({ id, username, displayName, role, mustChangePassword }) {
  return { id, username, displayName, role, mustChangePassword }
}

function userView(user) {
  const { status, version, createdAt, updatedAt } = user
  return { ...identity(user), status, version, createdAt, updatedAt }
}

function currentUser({ data, session }) {
  const user = data.users.get(session?.userId)
  if (!user || user.status !== 'ACTIVE' || user.authRevision !== session.authRevision) {
    fail(401, 'AUTH_REQUIRED', '请重新登录')
  }
  return user
}

function beginSession(ctx, scope, user) {
  const session = ctx.state.issueSession(ctx.session, scope, user)
  ctx.setCookie(session.id)
  ctx.respond(200, identity(user))
}

function endSession(ctx) {
  if (ctx.session) ctx.state.sessions.delete(ctx.session.id)
  ctx.setCookie(null)
  ctx.respond(204)
}

function update(user, fields) {
  Object.assign(user, fields, { version: user.version + 1, updatedAt: new Date().toISOString() })
}

export function handleAuth(ctx) {
  const { method, path, body, state, session, respond } = ctx
  switch (`${method} ${path}`) {
    case 'GET /api/auth/csrf': {
      const current = session ?? state.issueSession(null, 'primary')
      if (!session) ctx.setCookie(current.id)
      return respond(200, { token: current.token })
    }
    case 'GET /api/auth/test-options':
      return respond(200, { enabled: Boolean(state.demo) })
    case 'POST /api/auth/login': {
      fields(body, ['username', 'password'])
      const username = readUsername(body)
      const password = readText(body, 'password', 6, 64)
      const user = findUser(state.primary.users, username)
      if (!user || user.status !== 'ACTIVE' || user.password !== password) {
        fail(401, 'INVALID_CREDENTIALS', '用户名或密码错误')
      }
      return beginSession(ctx, 'primary', user)
    }
    case 'POST /api/auth/test-login': {
      if (!state.demo) fail(403, 'TEST_LOGIN_DISABLED', '测试登录未启用')
      fields(body, ['role'])
      const role = choice('role', readText(body, 'role', 1, 16), roles, '请选择有效的测试身份')
      const user = findUser(state.demo.users, demoUsers[role])
      if (!user || user.role !== role || user.status !== 'ACTIVE') {
        fail(403, 'TEST_ACCOUNT_UNAVAILABLE', '该测试账号当前不可用')
      }
      return beginSession(ctx, 'demo', user)
    }
    case 'GET /api/auth/me':
      return respond(200, identity(currentUser(ctx)))
    case 'PUT /api/auth/password': {
      fields(body, ['currentPassword', 'newPassword'])
      const user = currentUser(ctx)
      const original = readText(body, 'currentPassword', 6, 64)
      const password = readText(body, 'newPassword', 8, 64)
      if (original !== user.password) invalid('currentPassword', '当前密码不正确')
      if (original === password) invalid('newPassword', '新密码需要与原密码不同')
      update(user, { password, mustChangePassword: false, authRevision: user.authRevision + 1 })
      return endSession(ctx)
    }
    case 'POST /api/auth/logout':
      return endSession(ctx)
    default:
      return fail(404, 'NOT_FOUND', '记录不存在')
  }
}

function foldDisplayName(text) {
  return text.normalize('NFKD').replace(/\p{Mark}/gu, '').toLowerCase()
}

function listUsers(users, query) {
  const rawRole = query.get('role')
  const role = rawRole === null ? null : choice('role', rawRole, roles)
  const rawStatus = query.get('status')
  const status = rawStatus === null ? null : choice('status', rawStatus, statuses)
  const username = (query.get('username') ?? '').trim().toLowerCase()
  const displayName = foldDisplayName((query.get('displayName') ?? '').trim())
  const rows = [...users.values()].filter(user =>
    (!role || user.role === role) && (!status || user.status === status)
    && user.username.includes(username) && foldDisplayName(user.displayName).includes(displayName)
  )
  rows.sort((left, right) => Number(right.id) - Number(left.id))
  return page(rows.map(userView), query)
}

function createUser({ users, counters }, body) {
  fields(body, ['username', 'displayName', 'role', 'password'])
  const username = readUsername(body)
  const displayName = readText(body, 'displayName', 1, 40, true)
  const role = choice('role', readText(body, 'role', 1, 16), roles)
  const password = readText(body, 'password', 8, 64)
  if (findUser(users, username)) fail(409, 'USERNAME_EXISTS', '用户名已存在')
  const now = new Date().toISOString()
  const user = {
    id: String(++counters.user), username, displayName, role, password, status: 'ACTIVE',
    mustChangePassword: true, version: 1, authRevision: 1, createdAt: now, updatedAt: now,
  }
  users.set(user.id, user)
  return userView(user)
}

function findVersionedUser(users, id, body) {
  if (!/^[1-9]\d*$/.test(id) || BigInt(id) > 9223372036854775807n) invalid('id', '无效的编号')
  const version = body.version
  if (!Number.isInteger(version) || version < 1 || version > 2147483647) {
    invalid('version', '需要正整数版本')
  }
  const user = users.get(id)
  if (!user) fail(404, 'NOT_FOUND', '记录不存在')
  if (user.version !== version) fail(409, 'VERSION_CONFLICT', '记录已更新，请重新加载')
  return user
}

export function handleUsers(ctx) {
  const { method, path, params, body, query, data, session, respond } = ctx
  const { users } = data
  if (path === '/api/users') {
    if (method === 'GET') return respond(200, listUsers(users, query))
    if (method === 'POST') return respond(201, createUser(data, body))
  }
  const id = params?.id
  if (id && method === 'PATCH' && path === `/api/users/${id}`) {
    fields(body, ['version', 'displayName'])
    const user = findVersionedUser(users, id, body)
    const displayName = readText(body, 'displayName', 1, 40, true)
    if (user.displayName !== displayName) update(user, { displayName })
    return respond(200, userView(user))
  }
  if (id && method === 'PUT' && path === `/api/users/${id}/status`) {
    fields(body, ['version', 'status'])
    const user = findVersionedUser(users, id, body)
    const status = choice('status', readText(body, 'status', 1, 8), statuses)
    if (user.id === session.userId && status === 'DISABLED') {
      fail(409, 'SELF_DISABLE_FORBIDDEN', '不能停用自己的账号')
    }
    if (user.status !== status) {
      update(user, { status, authRevision: user.authRevision + (status === 'DISABLED' ? 1 : 0) })
    }
    return respond(200, userView(user))
  }
  if (id && method === 'POST' && path === `/api/users/${id}/reset-password`) {
    fields(body, ['version', 'newPassword'])
    const user = findVersionedUser(users, id, body)
    const password = readText(body, 'newPassword', 8, 64)
    if (password === user.password) invalid('newPassword', '新密码不能与原密码相同')
    update(user, { password, mustChangePassword: true, authRevision: user.authRevision + 1 })
    return respond(200, userView(user))
  }
  return fail(404, 'NOT_FOUND', '记录不存在')
}
