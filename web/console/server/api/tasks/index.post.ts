import { z } from 'zod'

const schema = z.object({
  deviceId: z.string().min(1),
  title: z.string().trim().min(1).max(120),
  type: z.enum(['agent.goal', 'device.diagnostic', 'app.flow']),
  goal: z.string().max(4000).optional().default(''),
  params: z.record(z.string(), z.unknown()).optional().default({}),
  policy: z
    .object({
      allowScreenshot: z.boolean().optional(),
      allowRoot: z.boolean().optional(),
      // -1 表示不限制时长，直到任务完成或从 Web 端手动停止
      timeoutMs: z.number().int().min(-1).max(3_600_000).optional()
    })
    .optional()
})

export default defineEventHandler(async (event) => {
  const user = requireRole(event, 'owner', 'admin', 'operator')
  const parsed = schema.safeParse(await readBody(event))
  if (!parsed.success) throw badRequest('任务参数不合法')

  const db = getDb()
  const device = db.prepare('SELECT * FROM devices WHERE id = ?').get(parsed.data.deviceId) as
    | Record<string, unknown>
    | undefined
  if (!device) throw notFound('设备不存在')
  if (device.revoked_at) throw conflict('设备已撤销授权，不能下发任务')
  if (parsed.data.policy?.allowRoot && !['owner', 'admin'].includes(user.role)) {
    throw forbidden('只有管理员可以授予 Root 能力')
  }

  const policy = {
    allowScreenshot: parsed.data.policy?.allowScreenshot ?? true,
    allowRoot: parsed.data.policy?.allowRoot ?? false,
    timeoutMs: parsed.data.policy?.timeoutMs ?? -1
  }

  const taskId = createId('task')
  const now = nowIso()

  db.prepare(
    `INSERT INTO tasks
       (id, title, type, status, device_id, created_by, payload_json, policy_json, created_at, updated_at)
     VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?)`
  ).run(
    taskId,
    parsed.data.title,
    parsed.data.type,
    parsed.data.deviceId,
    user.id,
    JSON.stringify({ goal: parsed.data.goal, params: parsed.data.params }),
    JSON.stringify(policy),
    now,
    now
  )

  const command = createCommand({
    deviceId: parsed.data.deviceId,
    taskId,
    type: 'TASK_ASSIGN',
    payload: {
      protocolVersion: 1,
      taskId,
      title: parsed.data.title,
      type: parsed.data.type,
      goal: parsed.data.goal,
      params: parsed.data.params,
      policy
    }
  })

  if (!command.delivered) {
    db.prepare("UPDATE tasks SET status = 'waiting_device', updated_at = ? WHERE id = ?").run(
      nowIso(),
      taskId
    )
  }

  recordAudit(event, {
    action: 'task.create',
    targetType: 'task',
    targetId: taskId,
    detail: {
      deviceId: parsed.data.deviceId,
      type: parsed.data.type,
      policy,
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
    .get(taskId) as Record<string, unknown>

  return {
    task: mapTask(row),
    commandId: command.id,
    delivered: command.delivered
  }
})
