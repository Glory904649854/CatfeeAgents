export type UserRole = 'owner' | 'admin' | 'operator' | 'viewer'

export interface User {
  id: string
  username: string
  displayName: string
  role: UserRole
  createdAt: string
}

export type DeviceStatus = 'online' | 'offline' | 'revoked' | 'pending'

export interface DeviceSummary {
  id: string
  name: string
  model: string | null
  androidVersion: string | null
  agentVersion: string | null
  capabilities: string[]
  status: DeviceStatus
  lastSeenAt: string | null
  lastHeartbeatAt: string | null
  createdAt: string
  revokedAt: string | null
}

export type TaskStatus =
  | 'queued'
  | 'waiting_device'
  | 'accepted'
  | 'running'
  | 'paused'
  | 'stopping'
  | 'stopped'
  | 'succeeded'
  | 'failed'
  | 'interrupted'
  | 'expired'

export type TaskType = 'agent.goal' | 'device.diagnostic' | 'app.flow'

export interface TaskPolicy {
  allowScreenshot: boolean
  allowRoot: boolean
  timeoutMs: number
}

export interface TaskSummary {
  id: string
  title: string
  type: TaskType
  status: TaskStatus
  deviceId: string
  deviceName: string | null
  createdBy: string
  createdByName: string | null
  payload: Record<string, unknown>
  policy: TaskPolicy
  createdAt: string
  updatedAt: string
  startedAt: string | null
  finishedAt: string | null
  error: string | null
}

export type EventLevel = 'debug' | 'info' | 'warn' | 'error' | 'security'

export interface EventSummary {
  id: string
  deviceId: string
  deviceName: string | null
  taskId: string | null
  runId: string | null
  sequence: number | null
  type: string
  level: EventLevel
  payload: Record<string, unknown>
  occurredAt: string
  receivedAt: string
}

export type CommandType = 'TASK_ASSIGN' | 'TASK_PAUSE' | 'TASK_RESUME' | 'TASK_STOP'
export type CommandStatus = 'pending' | 'sent' | 'acked' | 'failed' | 'expired'

export interface CommandSummary {
  id: string
  deviceId: string
  taskId: string
  type: CommandType
  status: CommandStatus
  payload: Record<string, unknown>
  result: string | null
  createdAt: string
  sentAt: string | null
  ackedAt: string | null
}

export interface AuditSummary {
  id: string
  actorName: string | null
  action: string
  targetType: string | null
  targetId: string | null
  detail: Record<string, unknown>
  createdAt: string
}

export interface OverviewStats {
  devicesTotal: number
  devicesOnline: number
  tasksRunning: number
  tasksToday: number
  tasksFailed: number
  eventsToday: number
}

export interface OverviewPayload {
  stats: OverviewStats
  devices: DeviceSummary[]
  tasks: TaskSummary[]
  events: EventSummary[]
}

export interface DeviceDetailPayload {
  device: DeviceSummary
  tasks: TaskSummary[]
  events: EventSummary[]
  commands: CommandSummary[]
}

export interface TaskDetailPayload {
  task: TaskSummary
  device: DeviceSummary | null
  events: EventSummary[]
  commands: CommandSummary[]
}
