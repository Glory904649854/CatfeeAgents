export default defineEventHandler((event) => {
  const user = requireRole(event, 'owner', 'admin')
  const id = String(getRouterParam(event, 'id') ?? '')
  const db = getDb()

  const row = db.prepare('SELECT * FROM devices WHERE id = ?').get(id) as
    | Record<string, unknown>
    | undefined
  if (!row) throw notFound('设备不存在')
  if (row.revoked_at) throw conflict('设备已撤销授权')

  db.prepare('UPDATE devices SET revoked_at = ?, last_heartbeat_at = NULL WHERE id = ?').run(
    new Date().toISOString(),
    id
  )

  closeDevicePeer(id, 'device revoked')
  interruptDeviceTasks(id)
  insertAgentEvent(id, {
    type: 'security.device_revoked',
    level: 'security',
    payload: { revokedBy: user.username }
  })
  publishDeviceUpdate(id, 'revoked')

  recordAudit(event, {
    action: 'device.revoke',
    targetType: 'device',
    targetId: id
  })

  const updated = db.prepare('SELECT * FROM devices WHERE id = ?').get(id) as Record<string, unknown>
  return { device: mapDevice(updated) }
})
