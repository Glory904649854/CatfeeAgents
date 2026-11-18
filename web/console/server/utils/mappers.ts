import type {
  AuditSummary,
  CommandStatus,
  CommandSummary,
  CommandType,
  DeviceStatus,
  DeviceSummary,
  EventLevel,
  EventSummary,
  TaskPolicy,
  TaskStatus,
  TaskSummary,
  TaskType
} from '~~/shared/types'

export const DEFAULT_TASK_POLICY: TaskPolicy = {
  allowScreenshot: true,
  allowRoot: false,
  timeoutMs: -1
}

export function parseJson<T>(value: unknown, fallback: T): T {
  if (value == null) return fallback
  if (typeof value !== 'string') return value as T
  try {
    return JSON.parse(value) as T
  } catch {
    return fallback
  }
}

function onlineWindowMs(): number {
  return 90_000
}

export function deriveDeviceStatus(row: Record<string, unknown>): DeviceStatus {
  if (row.revoked_at) return 'revoked'
  if (!row.last_heartbeat_at) return 'pending'
  const heartbeat = Date.parse(String(row.last_heartbeat_at))
  return Date.now() - heartbeat <= onlineWindowMs() ? 'online' : 'offline'
}

export function mapDevice(row: Record<string, unknown>): DeviceSummary {
  return {
    id: String(row.id),
    name: String(row.name),
    model: row.model == null ? null : String(row.model),
    androidVersion: row.android_version == null ? null : String(row.android_version),
    agentVersion: row.agent_version == null ? null : String(row.agent_version),
    capabilities: parseJson<string[]>(row.capabilities_json, []),
    status: deriveDeviceStatus(row),
    lastSeenAt: row.last_seen_at == null ? null : String(row.last_seen_at),
    lastHeartbeatAt: row.last_heartbeat_at == null ? null : String(row.last_heartbeat_at),
    createdAt: String(row.created_at),
    revokedAt: row.revoked_at == null ? null : String(row.revoked_at)
  }
}

export function mapTask(row: Record<string, unknown>): TaskSummary {
  return {
    id: String(row.id),
    title: String(row.title),
    type: String(row.type) as TaskType,
    status: String(row.status) as TaskStatus,
    deviceId: String(row.device_id),
    deviceName: row.device_name == null ? null : String(row.device_name),
    createdBy: String(row.created_by),
    createdByName: row.created_by_name == null ? null : String(row.created_by_name),
    payload: parseJson<Record<string, unknown>>(row.payload_json, {}),
    policy: { ...DEFAULT_TASK_POLICY, ...parseJson<Partial<TaskPolicy>>(row.policy_json, {}) },
    createdAt: String(row.created_at),
    updatedAt: String(row.updated_at),
    startedAt: row.started_at == null ? null : String(row.started_at),
    finishedAt: row.finished_at == null ? null : String(row.finished_at),
    error: row.error == null ? null : String(row.error)
  }
}

export function mapEvent(row: Record<string, unknown>): EventSummary {
  return {
    id: String(row.id),
    deviceId: String(row.device_id),
    deviceName: row.device_name == null ? null : String(row.device_name),
    taskId: row.task_id == null ? null : String(row.task_id),
    runId: row.run_id == null ? null : String(row.run_id),
    sequence: row.sequence == null ? null : Number(row.sequence),
    type: String(row.type),
    level: String(row.level) as EventLevel,
    payload: parseJson<Record<string, unknown>>(row.payload_json, {}),
    occurredAt: String(row.occurred_at),
    receivedAt: String(row.received_at)
  }
}

export function mapCommand(row: Record<string, unknown>): CommandSummary {
  return {
    id: String(row.id),
    deviceId: String(row.device_id),
    taskId: String(row.task_id),
    type: String(row.type) as CommandType,
    status: String(row.status) as CommandStatus,
    payload: parseJson<Record<string, unknown>>(row.payload_json, {}),
    result: row.result == null ? null : String(row.result),
    createdAt: String(row.created_at),
    sentAt: row.sent_at == null ? null : String(row.sent_at),
    ackedAt: row.acked_at == null ? null : String(row.acked_at)
  }
}

export function mapAudit(row: Record<string, unknown>): AuditSummary {
  return {
    id: String(row.id),
    actorName: row.actor_name == null ? null : String(row.actor_name),
    action: String(row.action),
    targetType: row.target_type == null ? null : String(row.target_type),
    targetId: row.target_id == null ? null : String(row.target_id),
    detail: parseJson<Record<string, unknown>>(row.detail_json, {}),
    createdAt: String(row.created_at)
  }
}
