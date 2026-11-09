import type { H3Event } from 'h3'
import type { EventLevel, EventSummary, TaskStatus } from '~~/shared/types'
import { createId, nowIso, randomToken, sha256 } from './ids'
import { mapEvent } from './mappers'
import { publishDeviceUpdate, publishEvent } from './bus'

/** 设备执行状态映射：Agent 事件驱动任务状态，控制台只做展示。 */
const TASK_STATUS_BY_EVENT: Record<string, TaskStatus> = {
  'task.accepted': 'accepted',
  'task.started': 'running',
  'task.paused': 'paused',
  'task.resumed': 'running',
  'task.stopping': 'stopping',
  'task.stopped': 'stopped',
  'task.succeeded': 'succeeded',
  'task.failed': 'failed',
  'task.expired': 'expired'
}

const TERMINAL_STATUSES: TaskStatus[] = ['stopped', 'succeeded', 'failed', 'expired']

export interface AgentPeer {
  id: string
  send: (data: string) => void
  close: (code?: number, reason?: string) => void
}

interface HubState {
  peers: Map<string, AgentPeer>
  peerDevices: Map<string, string>
}

const HUB_KEY = '__catfee_agent_hub__'

function state(): HubState {
  const globalStore = globalThis as typeof globalThis & { [HUB_KEY]?: HubState }
  if (!globalStore[HUB_KEY]) {
    globalStore[HUB_KEY] = { peers: new Map(), peerDevices: new Map() }
  }
  return globalStore[HUB_KEY]!
}

export interface DeviceRow extends Record<string, unknown> {
  id: string
  name: string
  secret_hash: string
  revoked_at: string | null
}

export function findDevice(deviceId: string): DeviceRow | null {
  const row = getDb().prepare('SELECT * FROM devices WHERE id = ?').get(deviceId) as
    | DeviceRow
    | undefined
  return row ?? null
}

export function authenticateDeviceHeaders(event: H3Event): DeviceRow {
  const deviceId = getHeader(event, 'x-device-id')
  const secret = getHeader(event, 'x-device-secret')
  if (!deviceId || !secret) {
    throw createError({
      statusCode: 401,
      statusMessage: '缺少设备凭证',
      data: { message: '缺少设备凭证' }
    })
  }
  const device = findDevice(deviceId)
  if (!device || device.revoked_at || device.secret_hash !== sha256(secret)) {
    throw createError({
      statusCode: 401,
      statusMessage: '设备认证失败',
      data: { message: '设备认证失败' }
    })
  }
  return device
}

export function isDeviceOnline(deviceId: string): boolean {
  return state().peers.has(deviceId)
}

export function closeDevicePeer(deviceId: string, reason = 'revoked'): void {
  const peer = state().peers.get(deviceId)
  if (!peer) return
  state().peers.delete(deviceId)
  state().peerDevices.delete(peer.id)
  try {
    peer.close(4003, reason)
  } catch {
    /* peer may already be closed */
  }
}

export function touchDevice(deviceId: string): void {
  const now = nowIso()
  getDb()
    .prepare('UPDATE devices SET last_seen_at = ?, last_heartbeat_at = ? WHERE id = ?')
    .run(now, now, deviceId)
}

export function applyTaskStatus(
  deviceId: string,
  taskId: string,
  status: TaskStatus,
  payload: Record<string, unknown> = {}
): void {
  const db = getDb()
  const row = db
    .prepare('SELECT status, started_at, finished_at FROM tasks WHERE id = ? AND device_id = ?')
    .get(taskId, deviceId) as
    | { status: string; started_at: string | null; finished_at: string | null }
    | undefined
  if (!row) return

  const now = nowIso()
  const startedAt = row.started_at ?? (['running', 'accepted', 'paused'].includes(status) ? now : null)
  const finishedAt = TERMINAL_STATUSES.includes(status) ? now : null
  const error =
    status === 'failed'
      ? String(payload.error ?? payload.message ?? payload.reason ?? '任务执行失败')
      : status === 'interrupted'
        ? String(payload.error ?? '任务中断')
        : null

  db.prepare(
    `UPDATE tasks
        SET status = ?, updated_at = ?, started_at = ?, finished_at = ?, error = ?
      WHERE id = ?`
  ).run(status, now, startedAt, finishedAt, error, taskId)
}

export function interruptDeviceTasks(deviceId: string): void {
  const rows = getDb()
    .prepare(
      `SELECT id FROM tasks
        WHERE device_id = ?
          AND status IN ('accepted', 'running', 'paused', 'stopping')`
    )
    .all(deviceId) as Array<{ id: string }>

  for (const row of rows) {
    applyTaskStatus(deviceId, row.id, 'interrupted', { error: '设备连接中断' })
  }
}

function inferLevel(type: string, level?: EventLevel): EventLevel {
  if (level) return level
  if (type.includes('failed') || type.includes('error') || type.includes('rejected')) return 'error'
  if (type.includes('warn')) return 'warn'
  if (type.startsWith('security.')) return 'security'
  if (type.startsWith('debug.')) return 'debug'
  return 'info'
}

function sanitizePayload(payload: Record<string, unknown>): Record<string, unknown> {
  const serialized = JSON.stringify(payload ?? {})
  if (serialized.length <= 16_000) return payload ?? {}
  return {
    truncated: true,
    originalBytes: serialized.length,
    preview: serialized.slice(0, 16_000)
  }
}

export interface AgentEventInput {
  id?: string
  taskId?: string | null
  runId?: string | null
  sequence?: number | null
  type: string
  level?: EventLevel
  payload?: Record<string, unknown>
  occurredAt?: string
}

/** 写入一条 Agent 事件，并同步任务状态、推送给 Web 控制台。 */
export function insertAgentEvent(deviceId: string, input: AgentEventInput): EventSummary | null {
  const db = getDb()
  const device = db.prepare('SELECT name FROM devices WHERE id = ?').get(deviceId) as
    | { name: string }
    | undefined
  if (!device) return null

  const id = input.id ?? createId('evt')
  const receivedAt = nowIso()
  const occurredAt = input.occurredAt ?? receivedAt
  const level = inferLevel(input.type, input.level)
  const payload = sanitizePayload(input.payload ?? {})

  db.prepare(
    `INSERT OR IGNORE INTO agent_events
       (id, device_id, task_id, run_id, sequence, type, level, payload_json, occurred_at, received_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).run(
    id,
    deviceId,
    input.taskId ?? null,
    input.runId ?? null,
    input.sequence ?? null,
    input.type,
    level,
    JSON.stringify(payload),
    occurredAt,
    receivedAt
  )

  const nextStatus = TASK_STATUS_BY_EVENT[input.type]
  if (nextStatus && input.taskId) {
    applyTaskStatus(deviceId, input.taskId, nextStatus, payload)
  }

  const row = db
    .prepare(
      `SELECT e.*, d.name AS device_name
         FROM agent_events e
         LEFT JOIN devices d ON d.id = e.device_id
        WHERE e.id = ?`
    )
    .get(id) as Record<string, unknown>

  const dto = mapEvent(row)
  publishEvent(dto)
  return dto
}

export function createCommand(input: {
  deviceId: string
  taskId: string
  type: 'TASK_ASSIGN' | 'TASK_PAUSE' | 'TASK_RESUME' | 'TASK_STOP'
  payload: Record<string, unknown>
}): { id: string; delivered: boolean } {
  const id = createId('cmd')
  getDb()
    .prepare(
      `INSERT INTO commands (id, device_id, task_id, type, status, payload_json, created_at)
       VALUES (?, ?, ?, ?, 'pending', ?, ?)`
    )
    .run(id, input.deviceId, input.taskId, input.type, JSON.stringify(input.payload), nowIso())

  return { id, delivered: sendCommand(id) }
}

export function sendCommand(commandId: string): boolean {
  const db = getDb()
  const row = db.prepare('SELECT * FROM commands WHERE id = ?').get(commandId) as
    | Record<string, unknown>
    | undefined
  if (!row) return false

  const peer = state().peers.get(String(row.device_id))
  if (!peer) return false

  try {
    peer.send(
      JSON.stringify({
        type: 'command',
        commandId: String(row.id),
        taskId: String(row.task_id),
        commandType: String(row.type),
        payload: JSON.parse(String(row.payload_json ?? '{}')),
        sentAt: nowIso()
      })
    )
    db.prepare("UPDATE commands SET status = 'sent', sent_at = ? WHERE id = ?").run(
      nowIso(),
      commandId
    )
    return true
  } catch {
    return false
  }
}

export function flushPendingCommands(deviceId: string): number {
  const rows = getDb()
    .prepare(
      `SELECT id FROM commands
        WHERE device_id = ? AND status IN ('pending', 'sent')
        ORDER BY created_at ASC
        LIMIT 20`
    )
    .all(deviceId) as Array<{ id: string }>

  let delivered = 0
  for (const row of rows) {
    if (sendCommand(row.id)) delivered += 1
  }
  return delivered
}

export function handleAgentHello(
  peer: AgentPeer,
  payload: {
    deviceId?: string
    secret?: string
    agentVersion?: string
    androidVersion?: string
    model?: string
    capabilities?: unknown
  }
): boolean {
  const deviceId = String(payload.deviceId ?? '')
  const secret = String(payload.secret ?? '')
  const device = deviceId ? findDevice(deviceId) : null

  if (!device || device.revoked_at || device.secret_hash !== sha256(secret)) {
    try {
      peer.send(JSON.stringify({ type: 'error', code: 'AUTH_FAILED', message: '设备认证失败' }))
      peer.close(4001, 'auth failed')
    } catch {
      /* ignore */
    }
    return false
  }

  const capabilities = Array.isArray(payload.capabilities)
    ? payload.capabilities.filter((item): item is string => typeof item === 'string').slice(0, 64)
    : null

  getDb()
    .prepare(
      `UPDATE devices
          SET agent_version = COALESCE(?, agent_version),
              android_version = COALESCE(?, android_version),
              model = COALESCE(?, model),
              capabilities_json = COALESCE(?, capabilities_json),
              last_seen_at = ?,
              last_heartbeat_at = ?
        WHERE id = ?`
    )
    .run(
      payload.agentVersion ?? null,
      payload.androidVersion ?? null,
      payload.model ?? null,
      capabilities ? JSON.stringify(capabilities) : null,
      nowIso(),
      nowIso(),
      deviceId
    )

  const previous = state().peers.get(deviceId)
  if (previous && previous !== peer) {
    state().peerDevices.delete(previous.id)
    try {
      previous.close(4000, 'replaced')
    } catch {
      /* ignore */
    }
  }

  state().peers.set(deviceId, peer)
  state().peerDevices.set(peer.id, deviceId)

  peer.send(
    JSON.stringify({
      type: 'hello_ack',
      protocolVersion: 1,
      serverTime: nowIso(),
      heartbeatSeconds: 30,
      pendingCommands: (
        getDb()
          .prepare(
            "SELECT COUNT(*) AS count FROM commands WHERE device_id = ? AND status IN ('pending', 'sent')"
          )
          .get(deviceId) as { count: number }
      ).count
    })
  )

  insertAgentEvent(deviceId, {
    type: 'device.online',
    level: 'info',
    payload: {
      agentVersion: payload.agentVersion ?? null,
      androidVersion: payload.androidVersion ?? null
    }
  })
  publishDeviceUpdate(deviceId, 'online')
  flushPendingCommands(deviceId)
  return true
}

export function detachAgent(peer: AgentPeer): void {
  const deviceId = state().peerDevices.get(peer.id)
  if (!deviceId) return

  state().peerDevices.delete(peer.id)
  if (state().peers.get(deviceId) !== peer) return

  state().peers.delete(deviceId)
  insertAgentEvent(deviceId, {
    type: 'device.offline',
    level: 'warn',
    payload: { reason: 'socket closed' }
  })
  interruptDeviceTasks(deviceId)
  publishDeviceUpdate(deviceId, 'offline')
}

export function handleAgentMessage(peer: AgentPeer, raw: string): void {
  let message: Record<string, unknown>
  try {
    message = JSON.parse(raw) as Record<string, unknown>
  } catch {
    try {
      peer.send(JSON.stringify({ type: 'error', code: 'BAD_JSON', message: '消息不是合法 JSON' }))
    } catch {
      /* ignore */
    }
    return
  }

  const type = String(message.type ?? '')
  const deviceId = state().peerDevices.get(peer.id)

  switch (type) {
    case 'hello': {
      handleAgentHello(peer, message as Record<string, never>)
      return
    }
    case 'heartbeat': {
      if (deviceId) {
        touchDevice(deviceId)
        peer.send(JSON.stringify({ type: 'heartbeat_ack', serverTime: nowIso() }))
      }
      return
    }
    case 'command_ack': {
      if (!deviceId) return
      const commandId = String(message.commandId ?? '')
      const status = String(message.status ?? '')
      const accepted = status === 'accepted' || status === 'applied' || status === 'acked'
      getDb()
        .prepare('UPDATE commands SET status = ?, result = ?, acked_at = ? WHERE id = ? AND device_id = ?')
        .run(
          accepted ? 'acked' : 'failed',
          JSON.stringify(message.detail ?? { status }),
          nowIso(),
          commandId,
          deviceId
        )
      insertAgentEvent(deviceId, {
        type: 'command.ack',
        level: accepted ? 'info' : 'warn',
        taskId: message.taskId ? String(message.taskId) : null,
        payload: { commandId, status, detail: message.detail ?? null }
      })
      return
    }
    case 'task_state': {
      if (!deviceId) return
      const taskId = String(message.taskId ?? '')
      const status = String(message.status ?? '') as TaskStatus
      if (taskId && status) {
        applyTaskStatus(deviceId, taskId, status, (message.payload as Record<string, unknown>) ?? {})
        insertAgentEvent(deviceId, {
          type: `task.${status}`,
          level: status === 'failed' ? 'error' : 'info',
          taskId,
          payload: (message.payload as Record<string, unknown>) ?? {}
        })
      }
      return
    }
    case 'event': {
      if (!deviceId) return
      const event = (message.event ?? {}) as Record<string, unknown>
      const eventType = String(event.type ?? '')
      if (!eventType) return
      insertAgentEvent(deviceId, {
        id: event.id ? String(event.id) : undefined,
        taskId: event.taskId ? String(event.taskId) : null,
        runId: event.runId ? String(event.runId) : null,
        sequence: typeof event.sequence === 'number' ? event.sequence : null,
        type: eventType,
        level: event.level as EventLevel | undefined,
        payload: (event.payload as Record<string, unknown>) ?? {},
        occurredAt: event.occurredAt ? String(event.occurredAt) : undefined
      })
      return
    }
    default: {
      try {
        peer.send(
          JSON.stringify({ type: 'error', code: 'UNKNOWN_TYPE', message: `未知消息类型: ${type}` })
        )
      } catch {
        /* ignore */
      }
    }
  }
}

export function agentConnectionInfo(event: H3Event) {
  const config = useRuntimeConfig(event)
  const baseUrl = String(config.publicUrl || 'http://localhost:3000').replace(/\/+$/, '')
  const wsBase = baseUrl.replace(/^http/, 'ws')
  return {
    baseUrl,
    wsUrl: `${wsBase}/api/agent/_ws`,
    enrollUrl: `${baseUrl}/api/agent/enroll`
  }
}

export function createDeviceSecret(): string {
  return randomToken(32)
}
