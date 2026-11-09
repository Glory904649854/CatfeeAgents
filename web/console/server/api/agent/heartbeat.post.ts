export default defineEventHandler((event) => {
  const device = authenticateDeviceHeaders(event)
  touchDevice(device.id)

  const pending = getDb()
    .prepare(
      "SELECT COUNT(*) AS count FROM commands WHERE device_id = ? AND status IN ('pending', 'sent')"
    )
    .get(device.id) as { count: number }

  return {
    ok: true,
    serverTime: nowIso(),
    heartbeatSeconds: 30,
    pendingCommands: pending.count
  }
})
