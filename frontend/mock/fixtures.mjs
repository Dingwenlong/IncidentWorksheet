import { randomUUID } from 'node:crypto'

const accounts = [
  ['admin', '系统管理员', 'ADMIN'],
  ['supervisor', '维修主管', 'SUPERVISOR'],
  ['technician', '维修员', 'TECHNICIAN'],
]

function createDataset(scope) {
  const demo = scope === 'demo'
  const createdAt = new Date().toISOString()
  const users = new Map(accounts.map(([key, name, role], index) => {
    const id = String(index + 1)
    return [id, {
      id,
      username: (demo ? 'demo_' : 'mock_') + key,
      displayName: (demo ? '测试' : '模拟') + name,
      role,
      password: demo ? randomUUID() : `mock-${key}-2026`,
      authRevision: 1,
      status: 'ACTIVE',
      mustChangePassword: false,
      version: 1,
      createdAt,
      updatedAt: createdAt,
    }]
  }))
  const equipment = new Map([
    ['1', { id: '1', code: 'P-02', name: '二号循环泵', location: '泵房', status: 'ACTIVE', version: 1, createdAt, updatedAt: createdAt }],
    ['2', { id: '2', code: 'M-04', name: '四号输送电机', location: '装配线', status: 'ACTIVE', version: 1, createdAt, updatedAt: createdAt }],
  ])
  const date = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' })
    .format(new Date(createdAt)).replaceAll('-', '')
  const creatorName = users.get('3').displayName
  const order = {
    id: '1',
    orderNo: `WO-${date}-00000001`,
    equipmentId: '1',
    equipmentCode: 'P-02',
    equipmentName: '二号循环泵',
    title: demo ? '循环泵启动报警（测试工单）' : '循环泵启动报警（Mock 工单）',
    faultText: '控制柜显示故障码 E17，二号循环泵启动后电机未转动。现场检查未发现泵体泄漏。',
    actionText: '复位后仍未恢复。更换接触器后试机，电机恢复转动。',
    status: 'COMPLETED',
    creatorId: '3',
    creatorName,
    version: 3,
    textVersion: 2,
    createdAt,
    updatedAt: createdAt,
  }
  const events = [
    ['CREATED', null, 'PENDING'],
    ['STARTED', 'PENDING', 'PROCESSING'],
    ['COMPLETED', 'PROCESSING', 'COMPLETED'],
  ].map(([type, fromStatus, toStatus], index) => ({
    id: String(index + 1), type, fromStatus, toStatus,
    actorId: '3', actorName: creatorName, occurredAt: createdAt,
  }))
  return {
    users, equipment,
    orders: new Map([['1', order]]),
    events: new Map([['1', events]]),
    idempotency: new Map(),
    counters: { user: 3, equipment: 2, order: 1, event: 3 },
  }
}

export function createMockState() {
  const sessions = new Map()
  return {
    primary: createDataset('primary'),
    demo: createDataset('demo'),
    sessions,
    issueSession(previous, scope, user) {
      if (previous) sessions.delete(previous.id)
      const session = {
        id: randomUUID(),
        token: randomUUID(),
        scope,
        userId: user?.id,
        authRevision: user?.authRevision,
        expiresAt: Date.now() + 30 * 60 * 1000,
      }
      sessions.set(session.id, session)
      return session
    },
  }
}
