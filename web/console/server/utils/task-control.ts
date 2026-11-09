import type { H3Event } from 'h3'

const ACTION_TO_COMMAND = {
  pause: 'TASK_PAUSE',
  resume: 'TASK_RESUME',
  stop: 'TASK_STOP'
} as const

const TERMINAL = new Set(['succeeded', 'failed', 'stopped', 'expired'])

export function controlTask(
  event: H3Event,
  taskId: string,
  action: 'pause' | 'resume' | 'stop'
): { ok: true; commandId: string; delivered: boolean; note?: string } {
  const user = requireUser(event)
  const db = getDb()

  const task = db.prepare('SELECT * FROM tasks WHERE id = ?').get(taskId) as
    | Record<string, unknown>
    | undefined
  if (!task) throw notFound('任务不存在')

  const status = String(task.status)
  const deviceId = String(task.device_id)

  if (TERMINAL.has(status)) throw conflict('任务已结束，无法继续控制')
  if (action === 'pause' && !['accepted', 'running'].includes(status)) {
    throw conflict('当前任务状态不支持暂停')
  }
  if (action === 'resume' && status !== 'paused') {
    throw conflict('当前任务不在暂停状态')
  }

  const command = createCommand({
    deviceId,
    taskId,
    type: ACTION_TO_COMMAND[action],
    payload: { taskId, requestedBy: user.username }
  })

  // 设备离线时停止任务：直接终止并让排队命令失效，避免重连后误执行。
  if (action === 'stop' && !command.delivered) {
    db.prepare(
      "UPDATE commands SET status = 'expired' WHERE task_id = ? AND status IN ('pending', 'sent')"
    ).run(taskId)
    applyTaskStatus(deviceId, taskId, 'stopped', { reason: '已由控制台取消' })
  }

  recordAudit(event, {
    action: `task.${action}`,
    targetType: 'task',
    targetId: taskId,
    detail: { commandId: command.id, delivered: command.delivered }
  })

  return {
    ok: true,
    commandId: command.id,
    delivered: command.delivered,
    note: command.delivered ? undefined : '设备离线，控制指令已排队'
  }
}
