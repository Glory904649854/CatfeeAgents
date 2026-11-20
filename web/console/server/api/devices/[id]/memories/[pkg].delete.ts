/**
 * 控制台删除设备上的一条 App 记忆。
 * 通过 MEMORY_DELETE 指令下发给设备, 并乐观更新本地快照。
 */
export default defineEventHandler(async (event) => {
  const id = String(getRouterParam(event, 'id') ?? '')
  const pkg = decodeURIComponent(String(getRouterParam(event, 'pkg') ?? '')).trim()
  requireRole(event, 'owner', 'admin', 'operator')
  if (!id || !pkg) throw badRequest('缺少设备或包名')

  const db = getDb()
  const device = db.prepare('SELECT id, revoked_at FROM devices WHERE id = ?').get(id) as
    | { id: string; revoked_at: string | null }
    | undefined
  if (!device) throw notFound('设备不存在')
  if (device.revoked_at) throw conflict('设备授权已撤销')

  const entryId = String(getQuery(event).entryId ?? '').trim()
  if (!entryId) throw badRequest('缺少 entryId')

  const command = createCommand({
    deviceId: id,
    taskId: '__memory__',
    type: 'MEMORY_DELETE',
    payload: { package: pkg, entryId }
  })

  const current = loadMemoryPackage(id, pkg)
  const next = current.entries.filter((item) => String(item.id) !== entryId)
  upsertMemoryPackage(id, pkg, current.appVersion, next)

  recordAudit(event, {
    action: 'device.memory_delete',
    targetType: 'device',
    targetId: id,
    detail: { package: pkg, entryId, commandId: command.id, delivered: command.delivered }
  })

  return {
    ok: true,
    commandId: command.id,
    delivered: command.delivered,
    note: command.delivered ? undefined : '设备离线，指令已排队，重连后应用'
  }
})
