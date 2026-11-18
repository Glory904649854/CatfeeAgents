import { z } from 'zod'

const schema = z.object({
  note: z.string().trim().max(120).optional()
})

/**
 * 控制台生成一次性设备注册码。
 * APK 使用该注册码调用 /api/agent/enroll 换取 deviceId + deviceSecret。
 */
export default defineEventHandler(async (event) => {
  const user = requireUser(event)
  const body = schema.safeParse((await readBody(event)) ?? {})
  if (!body.success) throw badRequest('参数不合法')

  const token = createEnrollmentCode()
  const createdAt = new Date().toISOString()
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString()

  getDb()
    .prepare(
      `INSERT INTO enrollment_tokens (token, note, created_by, expires_at, used_at, created_at)
       VALUES (?, ?, ?, ?, NULL, ?)`
    )
    .run(token, body.data.note ?? null, user.id, expiresAt, createdAt)

  recordAudit(event, {
    action: 'device.enroll_invite',
    targetType: 'enrollment_token',
    targetId: token,
    detail: { expiresAt }
  })

  return {
    token: formatEnrollmentCode(token),
    expiresAt,
    connection: agentConnectionInfo(event)
  }
})
