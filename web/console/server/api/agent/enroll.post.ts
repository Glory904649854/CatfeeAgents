import { z } from 'zod'

const schema = z.object({
  token: z.string().trim().min(1),
  name: z.string().trim().min(1).max(64),
  model: z.string().trim().max(120).optional(),
  androidVersion: z.string().trim().max(40).optional(),
  agentVersion: z.string().trim().max(40).optional(),
  capabilities: z.array(z.string().max(64)).max(64).optional()
})

/**
 * APK 使用一次性注册码换取设备身份。
 * 公开接口：仅凭未使用、未过期的 enrollment token 授权。
 */
export default defineEventHandler(async (event) => {
  const parsed = schema.safeParse(await readBody(event))
  if (!parsed.success) throw badRequest('注册参数不合法')

  const db = getDb()
  const rawToken = parsed.data.token.trim()
  const canonical = normalizeEnrollmentCode(rawToken)
  const lookup = (value: string) =>
    db.prepare('SELECT * FROM enrollment_tokens WHERE token = ?').get(value) as
      | Record<string, unknown>
      | undefined
  // 新注册码以 ABCD-2345 展示、无连字符入库；先按原文查，兼容旧 token。
  const tokenRow = lookup(rawToken) ?? (canonical && canonical !== rawToken ? lookup(canonical) : undefined)

  if (!tokenRow) throw notFound('注册码无效')
  if (tokenRow.used_at) throw conflict('注册码已被使用')
  if (Date.parse(String(tokenRow.expires_at)) < Date.now()) throw conflict('注册码已过期')

  const deviceId = createId('dev')
  const deviceSecret = createDeviceSecret()
  const now = nowIso()

  db.prepare(
    `INSERT INTO devices
       (id, name, secret_hash, model, android_version, agent_version, capabilities_json, created_at, last_seen_at, last_heartbeat_at, revoked_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)`
  ).run(
    deviceId,
    parsed.data.name,
    sha256(deviceSecret),
    parsed.data.model ?? null,
    parsed.data.androidVersion ?? null,
    parsed.data.agentVersion ?? null,
    JSON.stringify(parsed.data.capabilities ?? []),
    now
  )

  db.prepare('UPDATE enrollment_tokens SET used_at = ? WHERE token = ?').run(
    now,
    String(tokenRow.token)
  )

  recordAudit(event, {
    action: 'device.enrolled_by_agent',
    targetType: 'device',
    targetId: deviceId,
    detail: { name: parsed.data.name }
  })

  insertAgentEvent(deviceId, {
    type: 'device.enrolled',
    level: 'info',
    payload: {
      name: parsed.data.name,
      agentVersion: parsed.data.agentVersion ?? null
    }
  })

  return {
    deviceId,
    deviceSecret,
    protocolVersion: 1,
    heartbeatSeconds: 30,
    connection: agentConnectionInfo(event)
  }
})
