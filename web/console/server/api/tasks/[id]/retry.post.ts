export default defineEventHandler(async (event) => {
  const user = requireRole(event, 'owner', 'admin', 'operator')
  const id = String(getRouterParam(event, 'id') ?? '')
  const db = getDb()

  const source = db.prepare('SELECT * FROM tasks WHERE id = ?').get(id) as
    | Record<string, unknown>
    | undefined
  if (!source) throw notFound('任务不存在')

  const status = String(source.status)
  if (!['succeeded', 'failed', 'stopped', 'expired', 'interrupted'].includes(status)) {
    throw conflict('只有已结束的任务可以重试')
  }

  const deviceId = String(source.device_id)
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId) as
    | Record<string, unknown>
    | undefined
  if (!device) throw notFound('设备不存在')
  if (device.revoked_at) throw conflict('设备已撤销授权，不能重试')

  const payload = parseJson<Record<string, unknown>>(source.payload_json, {})
  const policy = {
    ...DEFAULT_TASK_POLICY,
    ...parseJson<Record<string, unknown>>(source.policy_json, {})
  }
  if (policy.allowRoot && !['owner', 'admin'].includes(user.role)) {
    throw forbidden('只有管理员可以授予 Root 能力')
  }

  const goal = String(payload.goal ?? '').trim()
  if (!goal) throw conflict('原任务没有可重试的目标')

  const title = `${String(source.title)}（重试）`.slice(0, 120)
  const newTaskId = createId('task')
  const now = nowIso()

  db.prepare(
    `INSERT INTO tasks
       (id, title, type, status, device_id, created_by, payload_json, policy_json, created_at, updated_at)
     VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?)`
  ).run(
    newTaskId,
    title,
    String(source.type),
    deviceId,
    user.id,
    JSON.stringify({ ...payload, goal, retryOf: id }),
    JSON.stringify(policy),
    now,
    now
  )

  const command = createCommand({
    deviceId,
    taskId: newTaskId,
    type: 'TASK_ASSIGN',
    payload: {
      protocolVersion: 1,
      taskId: newTaskId,
      title,
      type: String(source.type),
      goal,
      params: payload.params ?? {},
      policy,
      retryOf: id
    }
  })

  if (!command.delivered) {
    db.prepare("UPDATE tasks SET status = 'waiting_device', updated_at = ? WHERE id = ?").run(
      nowIso(),
      newTaskId
    )
  }

  recordAudit(event, {
    action: 'task.retry',
    targetType: 'task',
    targetId: newTaskId,
    detail: {
      sourceTaskId: id,
      deviceId,
      delivered: command.delivered
    }
  })

  const row = db
    .prepare(
      `SELECT t.*, d.name AS device_name, u.display_name AS created_by_name
         FROM tasks t
         LEFT JOIN devices d ON d.id = t.device_id
         LEFT JOIN users u ON u.id = t.created_by
        WHERE t.id = ?`
    )
    .get(newTaskId) as Record<string, unknown>

  return {
    task: mapTask(row),
    commandId: command.id,
    delivered: command.delivered
  }
})
