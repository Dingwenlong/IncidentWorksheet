import { randomBytes } from 'node:crypto'
import { fail, invalid, page } from './http.mjs'
import { summarize } from './summaries.mjs'

const ORDER_STATES = ['PENDING', 'PROCESSING', 'COMPLETED']
const EQUIPMENT_STATES = ['ACTIVE', 'DISABLED']
const ORDER_FILTERS = new Set(['keyword', 'equipmentId', 'status', 'creatorId', 'dateFrom', 'dateTo', 'page', 'pageSize'])
const MAX_ID = 9223372036854775807n
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const shanghaiCalendar = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

function roles(user, ...allowed) {
  if (!user) fail(401, 'AUTH_REQUIRED', '请先登录')
  if (!allowed.includes(user.role)) fail(403, 'FORBIDDEN', '当前账号无此权限')
}

function checkFields(body, allowed, message = '不支持该字段') {
  if (!body || typeof body !== 'object' || Array.isArray(body)) invalid('body', '请提供 JSON 对象')
  for (const field of Object.keys(body)) {
    if (!allowed.includes(field)) invalid(field, message)
  }
}

const trim = (s) => s.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
const length = (s) => [...s].length
const upperCode = (s) => s.replace(/[a-z]/g, (ch) => ch.toUpperCase())
const searchForm = (s) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
const descendingId = (a, b) => BigInt(b.id) > BigInt(a.id) ? 1 : BigInt(b.id) < BigInt(a.id) ? -1 : 0

function equipmentText(body, field, min, max, shouldTrim = true) {
  const raw = body[field]
  if (typeof raw !== 'string') invalid(field, '请填写文本')
  const value = shouldTrim ? trim(raw) : raw
  const size = length(value)
  if (size < min || size > max) invalid(field, `长度应为 ${min} 至 ${max} 个字符`)
  return value
}

function choice(value, field, values, message = '取值无效') {
  if (!values.includes(value)) invalid(field, message)
  return value
}

function recordId(value, field = 'id', message = '无效的编号') {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value) || BigInt(value) > MAX_ID) {
    invalid(field, message)
  }
  return value
}

function version(value, message = '需要正整数版本') {
  if (!Number.isInteger(value) || value < 1 || value > 2_147_483_647) invalid('version', message)
  return value
}

function equipmentLocation(body) {
  return body.location == null ? null : equipmentText(body, 'location', 0, 200) || null
}

function editEquipment(ctx, statusOnly) {
  const { body, data, params, respond } = ctx
  checkFields(body, statusOnly ? ['version', 'status'] : ['version', 'name', 'location'])
  const wantedVersion = version(body.version)
  if (!statusOnly && !Object.hasOwn(body, 'name') && !Object.hasOwn(body, 'location')) {
    invalid('body', '请指定要修改的字段')
  }
  const id = recordId(params.id)
  const old = data.equipment.get(id)
  if (!old) fail(404, 'NOT_FOUND', '记录不存在')
  if (old.version !== wantedVersion) fail(409, 'VERSION_CONFLICT', '记录已更新，请重新加载')

  const next = { ...old }
  if (statusOnly) {
    next.status = choice(equipmentText(body, 'status', 1, 8, false), 'status', EQUIPMENT_STATES)
  } else {
    if (Object.hasOwn(body, 'name')) next.name = equipmentText(body, 'name', 1, 100)
    if (Object.hasOwn(body, 'location')) next.location = equipmentLocation(body)
  }
  if (next.name === old.name && next.location === old.location && next.status === old.status) {
    return respond(200, old)
  }
  next.version++
  next.updatedAt = new Date().toISOString()
  data.equipment.set(id, next)
  return respond(200, next)
}

export function handleEquipment(ctx) {
  const { method, path, query, body, user, data, params, respond } = ctx
  if (path === '/api/equipment' && method === 'GET') {
    roles(user, 'TECHNICIAN', 'SUPERVISOR')
    const code = upperCode(trim(query.get('code') ?? ''))
    const name = searchForm(trim(query.get('name') ?? ''))
    const status = query.has('status') ? choice(query.get('status'), 'status', EQUIPMENT_STATES) : null
    const matches = [...data.equipment.values()].filter(
      (item) => item.code.includes(code) && searchForm(item.name).includes(name) && (!status || item.status === status)
    )
    return respond(200, page(matches.sort(descendingId), query))
  }
  if (path === '/api/equipment' && method === 'POST') {
    roles(user, 'SUPERVISOR')
    checkFields(body, ['code', 'name', 'location'])
    const code = upperCode(equipmentText(body, 'code', 1, 40))
    const name = equipmentText(body, 'name', 1, 100)
    const location = equipmentLocation(body)
    if ([...data.equipment.values()].some((item) => item.code === code)) {
      fail(409, 'EQUIPMENT_CODE_EXISTS', '设备编号已存在')
    }
    const now = new Date().toISOString()
    const id = String(++data.counters.equipment)
    const equipment = { id, code, name, location, status: 'ACTIVE', version: 1, createdAt: now, updatedAt: now }
    data.equipment.set(id, equipment)
    return respond(201, equipment)
  }
  if (params.id && method === 'PATCH' && path === `/api/equipment/${params.id}`) {
    roles(user, 'SUPERVISOR')
    return editEquipment(ctx, false)
  }
  if (params.id && method === 'PUT' && path === `/api/equipment/${params.id}/status`) {
    roles(user, 'SUPERVISOR')
    return editEquipment(ctx, true)
  }
  fail(404, 'NOT_FOUND', '接口不存在')
}

function orderText(body, key) {
  const value = body[key]
  if (typeof value !== 'string') invalid(key, '请提供文本值')
  if (/[\uD800-\uDFFF]/u.test(value)) invalid(key, '文本含无效的 Unicode 字符')
  if (key === 'title') {
    const title = trim(value)
    if (!title || length(title) > 100) invalid(key, '请输入 1 至 100 个字符的标题')
    return title
  }
  if (length(value) > 5000) invalid(key, '原文最多 5000 个字符')
  if (trim(value)) return value
  if (key === 'faultText') invalid(key, '请填写故障描述')
  return ''
}

const orderId = (value, key) => recordId(value, key, '编号必须为正十进制 BIGINT 字符串')

function viewOrder(data, order) {
  return { ...order, creatorName: data.users.get(String(order.creatorId))?.displayName ?? order.creatorName }
}

function localDay(utc) {
  const parts = Object.fromEntries(
    shanghaiCalendar.formatToParts(new Date(utc)).map(({ type, value }) => [type, value])
  )
  return `${parts.year.padStart(4, '0')}-${parts.month}-${parts.day}`
}

function queryDate(query, field) {
  const raw = query.get(field)
  if (raw === null) return null
  if (/^(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(raw)) {
    const date = new Date(raw + 'T00:00:00Z')
    if (Number.isFinite(date.valueOf()) && date.toISOString().slice(0, 10) === raw) return raw
  }
  invalid(field, '请使用有效日期 YYYY-MM-DD')
}

function listOrders({ query, data, respond }) {
  for (const key of query.keys()) {
    if (!ORDER_FILTERS.has(key) || query.getAll(key).length !== 1) invalid(key, '未知或重复的查询条件')
  }
  const keyword = trim(query.get('keyword') ?? '')
  if (length(keyword) > 200) invalid('keyword', '关键词最多 200 个字符')
  const term = searchForm(keyword)
  const equipmentId = query.has('equipmentId') ? orderId(query.get('equipmentId'), 'equipmentId') : null
  const creatorId = query.has('creatorId') ? orderId(query.get('creatorId'), 'creatorId') : null
  const status = query.has('status') ? choice(query.get('status'), 'status', ORDER_STATES, '工单状态无效') : null
  const from = queryDate(query, 'dateFrom')
  const to = queryDate(query, 'dateTo')
  if (from && to && from > to) invalid('dateTo', '结束日期不得早于开始日期')
  for (const [key, max] of [['page', 2_147_483_647], ['pageSize', 100]]) {
    if (!query.has(key)) continue
    const value = query.get(key)
    if (!/^[1-9][0-9]{0,9}$/.test(value) || Number(value) > max) {
      invalid(key, '页码或页大小超出有效范围')
    }
  }
  const matches = [...data.orders.values()].filter((order) => {
    if (equipmentId && order.equipmentId !== equipmentId) return false
    if (creatorId && order.creatorId !== creatorId) return false
    if (status && order.status !== status) return false
    if (from || to) {
      const day = localDay(order.createdAt)
      if ((from && day < from) || (to && day > to)) return false
    }
    return !term || [order.orderNo, order.title, order.equipmentCode, order.equipmentName, order.faultText, order.actionText]
      .some((raw) => searchForm(raw).includes(term))
  })
  matches.sort((a, b) => b.createdAt.localeCompare(a.createdAt) || descendingId(a, b))
  const result = page(matches, query)
  result.items = result.items.map((order) => {
    const { faultText, actionText, ...head } = viewOrder(data, order)
    const { fault, action } = summarize(order).TFIDF
    return { ...head, summary: { fault: fault.sentence, action: action.sentence } }
  })
  return respond(200, result)
}

function addEvent(data, order, type, fromStatus, user) {
  const events = data.events.get(order.id) ?? []
  events.push({
    id: String(++data.counters.event),
    type,
    fromStatus,
    toStatus: order.status,
    actorId: String(user.id),
    actorName: user.displayName,
    occurredAt: order.updatedAt,
  })
  data.events.set(order.id, events)
}

function createOrder({ body, data, headers, user, respond }) {
  checkFields(body, ['equipmentId', 'title', 'faultText', 'actionText'], '不允许提交此字段')
  const token = headers['idempotency-key']
  if (typeof token !== 'string' || !UUID_V4.test(token)) {
    invalid('Idempotency-Key', '创建工单需要有效的 UUIDv4 请求标识')
  }
  const equipmentId = orderId(body.equipmentId, 'equipmentId')
  const title = orderText(body, 'title')
  const faultText = orderText(body, 'faultText')
  const actionText = Object.hasOwn(body, 'actionText') ? orderText(body, 'actionText') : ''
  const creatorId = String(user.id)
  const key = creatorId + ':' + token.toLowerCase()
  const payload = JSON.stringify([equipmentId, title, faultText, actionText])
  const previous = data.idempotency.get(key)
  if (previous) {
    if (previous.payload !== payload) fail(409, 'IDEMPOTENCY_KEY_REUSED', '该请求标识已用于不同的工单内容')
    return respond(200, viewOrder(data, data.orders.get(previous.id)))
  }

  const equipment = data.equipment.get(equipmentId)
  if (!equipment) fail(404, 'NOT_FOUND', '未找到设备')
  if (equipment.status !== 'ACTIVE') fail(409, 'EQUIPMENT_DISABLED', '设备已停用，请重新选择')
  const now = new Date().toISOString()
  const id = String(++data.counters.order)
  const orderNo = `WO-${localDay(now).replaceAll('-', '')}-${randomBytes(4).toString('hex').toUpperCase()}`
  const order = {
    id, orderNo, equipmentId, equipmentCode: equipment.code, equipmentName: equipment.name,
    title, faultText, actionText, status: 'PENDING', creatorId, creatorName: user.displayName,
    version: 1, textVersion: 1, createdAt: now, updatedAt: now,
  }
  data.orders.set(id, order)
  data.idempotency.set(key, { id, payload })
  addEvent(data, order, 'CREATED', null, user)
  return respond(201, order)
}

function readOrder(data, id) {
  const order = data.orders.get(orderId(id, 'id'))
  if (!order) fail(404, 'NOT_FOUND', '未找到工单')
  return order
}

function detailOrder({ data, params, user, respond }) {
  const order = readOrder(data, params.id)
  const own = user.role === 'TECHNICIAN' && String(user.id) === order.creatorId
  return respond(200, {
    order: viewOrder(data, order),
    permissions: {
      canEdit: own && order.status !== 'COMPLETED',
      canStart: own && order.status === 'PENDING',
      canComplete: own && order.status === 'PROCESSING',
    },
    summaries: summarize(order),
    events: data.events.get(order.id) ?? [],
  })
}

const EDITABLE = ['title', 'faultText', 'actionText']

function changeOrder({ body, data, params, user, respond }, transition) {
  const id = orderId(params.id, 'id')
  let command = 'EDIT'
  let allowed = ['version', ...EDITABLE]
  if (transition) {
    if (!body || typeof body !== 'object' || Array.isArray(body)) invalid('body', '请提供 JSON 对象')
    command = choice(body.action, 'action', ['START', 'COMPLETE'], '请选择开始处理或完成工单')
    allowed = command === 'START' ? ['action', 'version'] : ['action', ...allowed]
  }
  checkFields(body, allowed, '不允许提交此字段')
  if (!transition && !EDITABLE.some((field) => Object.hasOwn(body, field))) {
    invalid('body', '请至少提交一个可编辑字段')
  }
  const wantedVersion = version(body.version, '记录版本必须为正整数')
  const edits = Object.fromEntries(
    EDITABLE.filter((field) => Object.hasOwn(body, field)).map((field) => [field, orderText(body, field)])
  )
  const old = readOrder(data, id)
  if (old.creatorId !== String(user.id)) fail(403, 'FORBIDDEN', '仅能处理本人创建的工单')
  if (old.version !== wantedVersion) fail(409, 'VERSION_CONFLICT', '工单已被更新，请重新加载')
  if (old.status === 'COMPLETED' ||
      command === 'START' && old.status !== 'PENDING' ||
      command === 'COMPLETE' && old.status !== 'PROCESSING') {
    fail(409, 'ORDER_STATE_CONFLICT', '当前状态不允许该操作，请重新加载')
  }

  const next = { ...old, ...edits }
  if (command === 'START') next.status = 'PROCESSING'
  if (command === 'COMPLETE') next.status = 'COMPLETED'
  if (next.status === 'COMPLETED' && !next.actionText) invalid('actionText', '完成工单前请填写处理经过')
  const textChanged = next.faultText !== old.faultText || next.actionText !== old.actionText
  if (!textChanged && next.title === old.title && next.status === old.status) return respond(200, viewOrder(data, old))
  next.version++
  if (textChanged) next.textVersion++
  next.updatedAt = new Date().toISOString()
  data.orders.set(id, next)
  if (next.status !== old.status) addEvent(data, next, command === 'START' ? 'STARTED' : 'COMPLETED', old.status, user)
  return respond(200, viewOrder(data, next))
}

export function handleOrders(ctx) {
  const { method, path, params, user } = ctx
  if (path === '/api/orders' && method === 'GET') {
    roles(user, 'TECHNICIAN', 'SUPERVISOR')
    return listOrders(ctx)
  }
  if (path === '/api/orders' && method === 'POST') {
    roles(user, 'TECHNICIAN')
    return createOrder(ctx)
  }
  const orderPath = `/api/orders/${params.id}`
  const transition = path === orderPath + '/transitions'
  if (params.id && method === 'GET' && path === orderPath) {
    roles(user, 'TECHNICIAN', 'SUPERVISOR')
    return detailOrder(ctx)
  }
  if (params.id && (method === 'PATCH' && path === orderPath || method === 'POST' && transition)) {
    roles(user, 'TECHNICIAN')
    return changeOrder(ctx, transition)
  }
  fail(404, 'NOT_FOUND', '接口不存在')
}
