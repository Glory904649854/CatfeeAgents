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
const DEVICE_TASK_STATUSES = new Set([
  'accepted',
  'running',
  'paused',
  'stopping',
  'stopped',
  'succeeded',
  'failed'
])

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

export interface AgentPeer {
  id: string
  send: (data: string) => void
  close: (code?: number, reason?: string) => void
}

interface HubState {
  peers: Map<string, AgentPeer>
  peerDevices: Map<string, string>
  helloTimers: Map<string, ReturnType<typeof setTimeout>>
  authenticated: Set<string>
  rate: Map<string, { count: number; resetAt: number }>
}

const HUB_KEY = '__catfee_agent_hub__'
const MAX_PEERS = 256
const HELLO_TIMEOUT_MS = 10_000
const MAX_MESSAGE_CHARS = 256_000
const RATE_WINDOW_MS = 10_000
const RATE_MAX_MESSAGES = 200

function state(): HubState {
  const globalStore = globalThis as typeof globalThis & { [HUB_KEY]?: HubState }
  if (!globalStore[HUB_KEY]) {
    globalStore[HUB_KEY] = {
      peers: new Map(),
      peerDevices: new Map(),
      helloTimers: new Map(),
      authenticated: new Set(),
      rate: new Map()
    }
  }
  return globalStore[HUB_KEY]!
}

/** WebSocket 建立后先挂握手超时; 10 秒内没有合法 hello 就断开。 */
export function registerAgentPeer(peer: AgentPeer): boolean {
  const hub = state()
  // peers 是已认证连接, helloTimers 是尚未完成 hello 的连接
  if (hub.peers.size + hub.helloTimers.size >= MAX_PEERS) {
    try {
      peer.close(4008, 'too many connections')
    } catch {
      /* ignore */
    }
    return false
  }
  const timer = setTimeout(() => {
    if (hub.authenticated.has(peer.id)) return
    try {
      peer.send(
        JSON.stringify({ type: 'error', code: 'HELLO_TIMEOUT', message: '未在限定时间内完成认证' })
      )
      peer.close(4001, 'hello timeout')
    } catch {
      /* ignore */
    }
    detachAgent(peer)
  }, HELLO_TIMEOUT_MS)
  // Node 的 timer 不应阻止进程退出
  ;(timer as { unref?: () => void }).unref?.()
  hub.helloTimers.set(peer.id, timer)
  return true
}

function clearHelloTimer(peerId: string): void {
  const hub = state()
  const timer = hub.helloTimers.get(peerId)
  if (timer) clearTimeout(timer)
  hub.helloTimers.delete(peerId)
}

function allowMessage(peerId: string): boolean {
  const hub = state()
  const now = Date.now()
  const entry = hub.rate.get(peerId)
  if (!entry || entry.resetAt <= now) {
    hub.rate.set(peerId, { count: 1, resetAt: now + RATE_WINDOW_MS })
    return true
  }
  entry.count += 1
  return entry.count <= RATE_MAX_MESSAGES
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
  type:
    | 'TASK_ASSIGN'
    | 'TASK_PAUSE'
    | 'TASK_RESUME'
    | 'TASK_STOP'
    | 'MEMORY_UPDATE'
    | 'MEMORY_DELETE'
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
  const deviceId = String(payload.deviceId ?? '').trim()
  const secret = String(payload.secret ?? '')
  if (!deviceId || deviceId.length > 80 || !secret || secret.length > 256) {
    try {
      peer.send(JSON.stringify({ type: 'error', code: 'BAD_HELLO', message: 'hello 参数不合法' }))
      peer.close(4001, 'bad hello')
    } catch {
      /* ignore */
    }
    return false
  }
  const device = findDevice(deviceId)

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

  const hub = state()
  hub.peers.set(deviceId, peer)
  hub.peerDevices.set(peer.id, deviceId)
  hub.authenticated.add(peer.id)
  clearHelloTimer(peer.id)

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
  const hub = state()
  clearHelloTimer(peer.id)
  hub.authenticated.delete(peer.id)
  hub.rate.delete(peer.id)
  const deviceId = hub.peerDevices.get(peer.id)
  if (!deviceId) return

  hub.peerDevices.delete(peer.id)
  if (hub.peers.get(deviceId) !== peer) return

  hub.peers.delete(deviceId)
  insertAgentEvent(deviceId, {
    type: 'device.offline',
    level: 'warn',
    payload: { reason: 'socket closed' }
  })
  interruptDeviceTasks(deviceId)
  publishDeviceUpdate(deviceId, 'offline')
}

/** 设备侧记忆快照 upsert; 由 Agent 在整理完成/手动编辑后主动上传 */
function handleMemorySync(deviceId: string, message: Record<string, unknown>): void {
  const pkg = String(message.package ?? '').trim().slice(0, 120)
  if (!pkg) return
  if (!Array.isArray(message.entries) || message.entries.length > 80) return
  const serialized = JSON.stringify(message.entries)
  if (serialized.length > 128_000) return
  const entries = parseMemoryEntries(serialized)
  const appVersion = String(message.appVersion ?? '').slice(0, 80) || null
  if (entries.length === 0) {
    // 设备清空该 App 记忆时同步删除控制台快照
    getDb()
      .prepare('DELETE FROM device_memories WHERE device_id = ? AND package = ?')
      .run(deviceId, pkg)
  } else {
    upsertMemoryPackage(deviceId, pkg, appVersion, entries)
  }
  publishDeviceUpdate(deviceId, 'memory')
}

export function handleAgentMessage(peer: AgentPeer, raw: string): void {
  if (raw.length > MAX_MESSAGE_CHARS) {
    try {
      peer.send(JSON.stringify({ type: 'error', code: 'TOO_LARGE', message: '消息过大' }))
      peer.close(4009, 'message too large')
    } catch {
      /* ignore */
    }
    return
  }
  if (!allowMessage(peer.id)) {
    try {
      peer.send(JSON.stringify({ type: 'error', code: 'RATE_LIMITED', message: '消息过于频繁' }))
      peer.close(4008, 'rate limited')
    } catch {
      /* ignore */
    }
    return
  }

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
  const hub = state()
  const deviceId = hub.peerDevices.get(peer.id)

  if (type === 'hello') {
    handleAgentHello(peer, message as Record<string, never>)
    return
  }
  if (!hub.authenticated.has(peer.id) || !deviceId) {
    try {
      peer.send(JSON.stringify({ type: 'error', code: 'AUTH_REQUIRED', message: '请先发送 hello' }))
      peer.close(4001, 'auth required')
    } catch {
      /* ignore */
    }
    return
  }

  switch (type) {
    case 'heartbeat': {
      if (deviceId) {
        touchDevice(deviceId)
        peer.send(JSON.stringify({ type: 'heartbeat_ack', serverTime: nowIso() }))
      }
      return
    }
    case 'command_ack': {
      if (!deviceId) return
      const commandId = String(message.commandId ?? '').trim()
      if (!commandId || commandId.length > 80) return
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
      const taskId = String(message.taskId ?? '').trim()
      const status = String(message.status ?? '')
      if (!taskId || taskId.length > 80 || !DEVICE_TASK_STATUSES.has(status)) return
      const payload = isRecord(message.payload) ? message.payload : {}
      applyTaskStatus(deviceId, taskId, status as TaskStatus, payload)
      insertAgentEvent(deviceId, {
        type: `task.${status}`,
        level: status === 'failed' ? 'error' : 'info',
        taskId,
        payload
      })
      return
    }
    case 'event': {
      if (!deviceId) return
      const event = message.event
      if (!isRecord(event)) return
      const eventType = String(event.type ?? '').trim()
      if (!eventType || eventType.length > 80) return
      const eventId = event.id == null ? undefined : String(event.id)
      const taskId = event.taskId == null ? null : String(event.taskId)
      const runId = event.runId == null ? null : String(event.runId)
      const occurredAt = event.occurredAt == null ? undefined : String(event.occurredAt)
      if ((eventId && eventId.length > 80) ||
          (taskId && taskId.length > 80) ||
          (runId && runId.length > 80) ||
          (occurredAt && occurredAt.length > 40)) return
      insertAgentEvent(deviceId, {
        id: eventId,
        taskId,
        runId,
        sequence: typeof event.sequence === 'number' ? event.sequence : null,
        type: eventType,
        level: event.level as EventLevel | undefined,
        payload: isRecord(event.payload) ? event.payload : {},
        occurredAt
      })
      return
    }
    case 'memory_sync': {
      if (!deviceId) return
      handleMemorySync(deviceId, message)
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
  const baseUrl = String(config.publicUrl || 'http://localhost:9777').replace(/\/+$/, '')
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
