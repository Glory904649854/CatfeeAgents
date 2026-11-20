/**
 * 控制台编辑设备上的一条 App 记忆。
 * 通过 MEMORY_UPDATE 指令下发给设备, 同时乐观更新本地快照;
 * 设备应用后会再次 memory_sync, 以设备为准。
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

  const body = (await readBody(event)) as { entry?: Record<string, unknown> } | null
  const entry = sanitizeMemoryEntry(body?.entry)
  if (!entry) throw badRequest('entry 不合法（id/text 不能为空）')

  const command = createCommand({
    deviceId: id,
    taskId: '__memory__',
    type: 'MEMORY_UPDATE',
    payload: { package: pkg, entry }
  })

  // 乐观更新: 设备离线时控制台也能立即看到编辑结果
  const current = loadMemoryPackage(id, pkg)
  const idx = current.entries.findIndex((item) => String(item.id) === String(entry.id))
  if (idx >= 0) {
    current.entries[idx] = { ...current.entries[idx], ...entry }
  } else {
    current.entries.push(entry)
  }
  upsertMemoryPackage(id, pkg, current.appVersion, current.entries.slice(0, 80))

  recordAudit(event, {
    action: 'device.memory_update',
    targetType: 'device',
    targetId: id,
    detail: { package: pkg, entryId: entry.id, commandId: command.id, delivered: command.delivered }
  })

  return {
    ok: true,
    commandId: command.id,
    delivered: command.delivered,
    note: command.delivered ? undefined : '设备离线，指令已排队，重连后应用'
  }
})
