import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { createServer } from 'node:http'
import test from 'node:test'
import { createMockMiddleware } from './middleware.mjs'

const endpoints = [
  'GET /auth/csrf',
  'GET /auth/test-options',
  'POST /auth/test-login',
  'POST /auth/login',
  'GET /auth/me',
  'PUT /auth/password',
  'POST /auth/logout',
  'GET /users',
  'POST /users',
  'PATCH /users/:id',
  'PUT /users/:id/status',
  'POST /users/:id/reset-password',
  'GET /equipment',
  'POST /equipment',
  'PATCH /equipment/:id',
  'PUT /equipment/:id/status',
  'GET /orders',
  'POST /orders',
  'GET /orders/:id',
  'PATCH /orders/:id',
  'POST /orders/:id/transitions',
]

async function startMock(t) {
  const middleware = createMockMiddleware()
  const server = createServer((req, res) => middleware(req, res, () => res.writeHead(404).end()))
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  t.after(() => new Promise(resolve => {
    server.close(resolve)
    server.closeAllConnections()
  }))
  return 'http://127.0.0.1:' + server.address().port + '/api'
}

function session(base, covered = new Set()) {
  let cookie = ''
  let token

  async function request(method, path, { body, key, status = 200, code, csrf = true } = {}) {
    if (csrf && method !== 'GET' && token === undefined) {
      token = (await request('GET', '/auth/csrf')).token
      assert.equal(typeof token, 'string')
      assert.ok(token.length)
    }
    const headers = { Accept: 'application/json' }
    if (cookie) headers.Cookie = cookie
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (csrf && method !== 'GET') headers['X-CSRF-TOKEN'] = token
    if (key) headers['Idempotency-Key'] = key

    const response = await fetch(base + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    })
    const value = await response.text()
    const content = value ? JSON.parse(value) : undefined
    assert.equal(response.status, status, `${method} ${path}: ${value}`)
    if (code) assert.equal(content.code, code, `${method} ${path}`)

    const updatedCookie = response.headers.get('set-cookie')
    if (updatedCookie) cookie = updatedCookie.split(';', 1)[0]
    if (response.ok) {
      const route = path.split('?')[0].replace(/\/(\d+)(?=\/|$)/g, '/:id')
      covered.add(method + ' ' + route)
      if (['/auth/login', '/auth/test-login', '/auth/logout', '/auth/password'].includes(route))
        token = undefined
    }
    return content
  }

  return { request }
}

test('Mock implements all 21 routes and an account-to-completion workflow', async (t) => {
  const base = await startMock(t)
  const covered = new Set()
  const admin = session(base, covered).request
  const supervisor = session(base, covered).request
  const technician = session(base, covered).request

  await admin('GET', '/auth/me', { status: 401, code: 'AUTH_REQUIRED' })
  assert.deepEqual(await admin('GET', '/auth/test-options'), { enabled: true })
  await admin('POST', '/auth/login', {
    body: { username: 'mock_admin', password: 'mock-admin-2026' },
  })
  assert.equal((await admin('GET', '/auth/me')).role, 'ADMIN')

  const password = 'mock-temp-2026'
  const newPassword = 'mock-new-2026'
  const role = 'TECHNICIAN'
  const user = await admin('POST', '/users', {
    body: { username: 'mock_new_tech', displayName: '模拟维修员', role, password }, status: 201,
  })
  assert.equal(user.mustChangePassword, true)
  const users = await admin('GET', '/users?username=mock_new_tech')
  assert.equal(users.total, 1)
  assert.equal(users.items[0].id, user.id)
  assert.equal(Object.hasOwn(users.items[0], 'password'), false)
  const updated = await admin('PATCH', '/users/' + user.id, {
    body: { version: user.version, displayName: '张师傅' },
  })
  assert.equal(updated.displayName, '张师傅')
  await admin('PUT', '/users/' + user.id + '/status', {
    body: { version: user.version, status: 'DISABLED' }, status: 409, code: 'VERSION_CONFLICT',
  })
  const disabled = await admin('PUT', '/users/' + user.id + '/status', {
    body: { version: updated.version, status: 'DISABLED' },
  })
  const enabled = await admin('PUT', '/users/' + user.id + '/status', {
    body: { version: disabled.version, status: 'ACTIVE' },
  })
  const reset = await admin('POST', '/users/' + user.id + '/reset-password', {
    body: { version: enabled.version, newPassword },
  })
  assert.equal(reset.mustChangePassword, true)
  await technician('POST', '/auth/login', { body: { username: user.username, password: newPassword } })
  await technician('GET', '/orders', { status: 403, code: 'PASSWORD_CHANGE_REQUIRED' })
  await technician('PUT', '/auth/password', {
    body: { currentPassword: newPassword, newPassword: password }, status: 204,
  })
  await technician('GET', '/auth/me', { status: 401, code: 'AUTH_REQUIRED' })
  await technician('POST', '/auth/login', { body: { username: user.username, password } })
  await technician('GET', '/users', { status: 403, code: 'FORBIDDEN' })

  const demoSupervisor = await supervisor('POST', '/auth/test-login', { body: { role: 'SUPERVISOR' } })
  assert.equal(demoSupervisor.role, 'SUPERVISOR')
  const setup = { code: ' mock-p1 ', name: '模拟循环泵', location: '测试泵房' }
  await supervisor('POST', '/equipment', {
    body: setup, csrf: false, status: 403, code: 'INVALID_CSRF_TOKEN',
  })
  const equipment = await supervisor('POST', '/equipment', { body: setup, status: 201 })
  assert.equal(equipment.code, 'MOCK-P1')
  const edited = await supervisor('PATCH', '/equipment/' + equipment.id, {
    body: { version: equipment.version, location: null },
  })
  assert.equal(edited.location, null)
  await supervisor('PUT', '/equipment/' + equipment.id + '/status', {
    body: { version: edited.version, status: 'ACTIVE' },
  })
  assert.equal((await supervisor('GET', '/equipment?code=MOCK-P1')).total, 1)

  await technician('POST', '/auth/test-login', { body: { role } })
  const input = { title: '循环泵故障', equipmentId: equipment.id, faultText: '🔧 E17！\r\n循环泵无法启动。' }
  const key = randomUUID()
  const order = await technician('POST', '/orders', { body: input, key, status: 201 })
  assert.equal((await technician('POST', '/orders', { body: input, key })).id, order.id)
  const path = '/orders/' + order.id
  const draft = await technician('PATCH', path, {
    body: { version: order.version, title: '循环泵报警' },
  })
  assert.equal(draft.title, '循环泵报警')
  const working = await technician('POST', path + '/transitions', {
    body: { version: draft.version, action: 'START' },
  })
  await technician('POST', path + '/transitions', {
    body: { version: working.version, action: 'COMPLETE' },
    status: 422, code: 'VALIDATION_FAILED',
  })
  const completed = await technician('POST', path + '/transitions', {
    body: { version: working.version, action: 'COMPLETE', actionText: '更换接触器后恢复。' },
  })
  assert.equal(completed.status, 'COMPLETED')
  const detail = await supervisor('GET', path)
  assert.deepEqual(detail.events.map(event => event.type), ['CREATED', 'STARTED', 'COMPLETED'])
  assert.deepEqual(Object.keys(detail.summaries).sort(), ['TEXTRANK', 'TFIDF'])
  for (const summary of Object.values(detail.summaries)) {
    const { sentence, start, end } = summary.fault
    assert.equal(input.faultText.slice(start, end), sentence)
    assert.equal(summary.textVersion, completed.textVersion)
  }
  const history = await supervisor('GET', '/orders?status=COMPLETED&keyword=E17&equipmentId=' + equipment.id)
  assert.equal(history.total, 1)
  assert.equal(history.items[0].summary.action, detail.summaries.TFIDF.action.sentence)

  await supervisor('POST', '/auth/logout', { status: 204 })
  await supervisor('GET', '/auth/me', { status: 401, code: 'AUTH_REQUIRED' })
  assert.deepEqual([...covered].sort(), [...endpoints].sort())
})

test('Mock test login is isolated and failed login preserves the active identity', async (t) => {
  const base = await startMock(t)
  const client = session(base).request
  await client('POST', '/auth/test-login', { body: { role: 'ADMIN' } })
  assert.equal((await client('GET', '/auth/me')).username, 'demo_admin')
  const demoOnlyUser = 'mock_only_in_demo'
  await client('POST', '/users', {
    body: {
      username: demoOnlyUser, password: 'demo-only-2026',
      displayName: '仅测试库', role: 'TECHNICIAN',
    }, status: 201,
  })
  await client('POST', '/auth/test-login', {
    body: { role: 'UNKNOWN' }, status: 422, code: 'VALIDATION_FAILED',
  })
  await client('POST', '/auth/login', {
    body: { username: 'mock_admin', password: 'mock-invalid' },
    status: 401, code: 'INVALID_CREDENTIALS',
  })
  assert.equal((await client('GET', '/auth/me')).username, 'demo_admin')
  await client('POST', '/auth/login', { body: { username: 'mock_admin', password: 'mock-admin-2026' } })
  assert.equal((await client('GET', '/auth/me')).username, 'mock_admin')
  assert.equal((await client('GET', '/users?username=' + demoOnlyUser)).total, 0)
  const cleanMock = session(await startMock(t)).request
  await cleanMock('POST', '/auth/test-login', { body: { role: 'ADMIN' } })
  assert.equal((await cleanMock('GET', '/users?username=' + demoOnlyUser)).total, 0)
})
