import type {
  CommandStatus,
  DeviceStatus,
  EventLevel,
  TaskStatus,
  TaskType
} from '~~/shared/types'

export interface StatusMeta {
  label: string
  className: string
  dotClassName: string
}

export const DEVICE_STATUS_META: Record<DeviceStatus, StatusMeta> = {
  online: {
    label: '在线',
    className: 'bg-success/10 text-success',
    dotClassName: 'bg-success'
  },
  offline: {
    label: '离线',
    className: 'bg-subtle text-muted',
    dotClassName: 'bg-faint'
  },
  pending: {
    label: '待接入',
    className: 'bg-warning/10 text-warning',
    dotClassName: 'bg-warning'
  },
  revoked: {
    label: '已撤销',
    className: 'bg-danger/8 text-danger',
    dotClassName: 'bg-danger'
  }
}

export const TASK_STATUS_META: Record<TaskStatus, StatusMeta> = {
  queued: {
    label: '已排队',
    className: 'bg-subtle text-muted',
    dotClassName: 'bg-faint'
  },
  waiting_device: {
    label: '等待设备',
    className: 'bg-warning/10 text-warning',
    dotClassName: 'bg-warning'
  },
  accepted: {
    label: '已接收',
    className: 'bg-subtle text-slate',
    dotClassName: 'bg-slate'
  },
  running: {
    label: '执行中',
    className: 'bg-brand/10 text-brand',
    dotClassName: 'bg-brand'
  },
  paused: {
    label: '已暂停',
    className: 'bg-violet/10 text-violet',
    dotClassName: 'bg-violet'
  },
  stopping: {
    label: '停止中',
    className: 'bg-warning/10 text-warning',
    dotClassName: 'bg-warning'
  },
  stopped: {
    label: '已停止',
    className: 'bg-subtle text-muted',
    dotClassName: 'bg-faint'
  },
  succeeded: {
    label: '已完成',
    className: 'bg-success/10 text-success',
    dotClassName: 'bg-success'
  },
  failed: {
    label: '失败',
    className: 'bg-danger/10 text-danger',
    dotClassName: 'bg-danger'
  },
  interrupted: {
    label: '已中断',
    className: 'bg-warning/10 text-warning',
    dotClassName: 'bg-warning'
  },
  expired: {
    label: '已过期',
    className: 'bg-subtle text-muted',
    dotClassName: 'bg-faint'
  }
}

export const EVENT_LEVEL_META: Record<EventLevel, StatusMeta> = {
  debug: { label: 'DEBUG', className: 'bg-subtle text-muted', dotClassName: 'bg-faint' },
  info: { label: 'INFO', className: 'bg-subtle text-slate', dotClassName: 'bg-slate' },
  warn: { label: 'WARN', className: 'bg-warning/10 text-warning', dotClassName: 'bg-warning' },
  error: { label: 'ERROR', className: 'bg-danger/10 text-danger', dotClassName: 'bg-danger' },
  security: {
    label: 'SECURITY',
    className: 'bg-violet/10 text-violet',
    dotClassName: 'bg-violet'
  }
}

export const TASK_TYPE_LABELS: Record<TaskType, string> = {
  'agent.goal': 'Agent 目标',
  'device.diagnostic': '设备诊断',
  'app.flow': '应用流程'
}

export const COMMAND_STATUS_META: Record<CommandStatus, StatusMeta> = {
  pending: { label: '排队中', className: 'bg-warning/10 text-warning', dotClassName: 'bg-warning' },
  sent: { label: '已发送', className: 'bg-slate/10 text-slate', dotClassName: 'bg-slate' },
  acked: { label: '已确认', className: 'bg-success/10 text-success', dotClassName: 'bg-success' },
  failed: { label: '失败', className: 'bg-danger/10 text-danger', dotClassName: 'bg-danger' },
  expired: { label: '已过期', className: 'bg-subtle text-muted', dotClassName: 'bg-faint' }
}

export const COMMAND_TYPE_LABELS: Record<string, string> = {
  TASK_ASSIGN: '下发任务',
  TASK_PAUSE: '暂停任务',
  TASK_RESUME: '继续任务',
  TASK_STOP: '停止任务',
  MEMORY_UPDATE: '更新记忆',
  MEMORY_DELETE: '删除记忆'
}

export function deviceStatusMeta(status: DeviceStatus): StatusMeta {
  return DEVICE_STATUS_META[status] ?? DEVICE_STATUS_META.offline
}

export function taskStatusMeta(status: TaskStatus): StatusMeta {
  return TASK_STATUS_META[status] ?? TASK_STATUS_META.queued
}

export function eventLevelMeta(level: EventLevel): StatusMeta {
  return EVENT_LEVEL_META[level] ?? EVENT_LEVEL_META.info
}

export function commandStatusMeta(status: CommandStatus): StatusMeta {
  return COMMAND_STATUS_META[status] ?? COMMAND_STATUS_META.pending
}

const MEMORY_KIND_META: Record<string, StatusMeta> = {
  entry: { label: '入口', className: 'bg-brand/10 text-brand', dotClassName: 'bg-brand' },
  control: { label: '控件', className: 'bg-violet/10 text-violet', dotClassName: 'bg-violet' },
  pitfall: { label: '坑', className: 'bg-warning/10 text-warning', dotClassName: 'bg-warning' },
  flow: { label: '流程', className: 'bg-success/10 text-success', dotClassName: 'bg-success' },
  preference: { label: '偏好', className: 'bg-slate/10 text-slate', dotClassName: 'bg-slate' }
}

export function memoryKindMeta(kind: string): StatusMeta {
  return MEMORY_KIND_META[kind] ?? MEMORY_KIND_META.entry
}
