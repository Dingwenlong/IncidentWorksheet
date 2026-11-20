export class MockError extends Error {
  constructor(status, code, message, fieldErrors) {
    super(message)
    this.status = status
    this.payload = { code, message, ...(fieldErrors ? { fieldErrors } : {}) }
  }
}

export function fail(status, code, message) {
  throw new MockError(status, code, message)
}

export function invalid(field, message) {
  throw new MockError(422, 'VALIDATION_FAILED', '请检查输入字段', { [field]: message })
}

export function page(rows, query) {
  const number = pageParameter(query, 'page', 1, 2_147_483_647)
  const size = pageParameter(query, 'pageSize', 20, 100)
  const offset = (number - 1) * size
  return { items: rows.slice(offset, offset + size), total: rows.length, page: number, pageSize: size }
}

function pageParameter(query, key, fallback, max) {
  const value = query.get(key)
  if (value === null || value === '') return fallback
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > max) {
    invalid(key, key === 'page' ? '页码须为正整数' : '每页须为 1 至 100 条')
  }
  return Number(value)
}

export async function readJson(request) {
  const chunks = []
  let length = 0
  for await (const chunk of request) {
    length += chunk.length
    if (length > 128 * 1024) fail(413, 'PAYLOAD_TOO_LARGE', '请求内容过长')
    chunks.push(chunk)
  }
  if (!length) return undefined
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    invalid('body', '请求必须是有效的 JSON 对象')
  }
}
