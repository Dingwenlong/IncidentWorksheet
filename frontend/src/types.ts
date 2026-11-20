export type Role = 'ADMIN' | 'SUPERVISOR' | 'TECHNICIAN'
export type Enabled = 'ACTIVE' | 'DISABLED'
export type OrderStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED'
export interface Identity {
  id: string
  username: string
  displayName: string
  role: Role
  mustChangePassword: boolean
}
export interface User extends Identity {
  status: Enabled
  version: number
  createdAt: string
  updatedAt: string
}
export interface Equipment {
  id: string
  code: string
  name: string
  location: string | null
  status: Enabled
  version: number
  createdAt: string
  updatedAt: string
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}
export interface OrderHead {
  id: string
  orderNo: string
  title: string
  equipmentId: string
  equipmentCode: string
  equipmentName: string
  status: OrderStatus
  creatorId: string
  creatorName: string
  createdAt: string
  updatedAt: string
  version: number
  textVersion: number
}
export interface Order extends OrderHead {
  faultText: string
  actionText: string
}
export interface Row extends OrderHead {
  summary: { fault: string | null; action: string | null }
}
export type Side = 'fault' | 'action'
export type Algorithm = 'TFIDF' | 'TEXTRANK'
export interface Excerpt {
  sentence: string | null
  start: number | null
  end: number | null
  keywords: string[]
  selection: string
}
export interface Summary {
  algorithm: Algorithm
  algorithmVersion: string
  dictionaryVersion: string
  idfVersion: string | null
  textVersion: number
  fault: Excerpt
  action: Excerpt
}
export interface Detail {
  order: Order
  permissions: { canEdit: boolean; canStart: boolean; canComplete: boolean }
  summaries: Record<Algorithm, Summary>
  events: {
    id: string
    type: 'CREATED' | 'STARTED' | 'COMPLETED'
    fromStatus: OrderStatus | null
    toStatus: OrderStatus
    actorId: string
    actorName: string
    occurredAt: string
  }[]
}
export const roleNames: Record<Role, string> = {
  ADMIN: '系统管理员',
  SUPERVISOR: '维修主管',
  TECHNICIAN: '维修员',
}
export const stateNames: Record<OrderStatus | Enabled, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
  ACTIVE: '启用',
  DISABLED: '停用',
}
const dt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
export const shTime = (value: string) => dt.format(new Date(value))
export const chars = (value: string) => [...value].length
