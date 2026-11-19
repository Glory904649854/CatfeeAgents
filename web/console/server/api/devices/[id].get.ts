export default defineEventHandler((event) => {
  const id = String(getRouterParam(event, 'id') ?? '')
  const db = getDb()

  const deviceRow = db.prepare('SELECT * FROM devices WHERE id = ?').get(id) as
    | Record<string, unknown>
    | undefined
  if (!deviceRow) throw notFound('设备不存在')

  const tasks = db
    .prepare(
      `SELECT t.*, d.name AS device_name, u.display_name AS created_by_name
         FROM tasks t
         LEFT JOIN devices d ON d.id = t.device_id
         LEFT JOIN users u ON u.id = t.created_by
        WHERE t.device_id = ?
        ORDER BY t.created_at DESC
        LIMIT 20`
    )
    .all(id) as Array<Record<string, unknown>>

  const events = db
    .prepare(
      `SELECT e.*, d.name AS device_name
         FROM agent_events e
         LEFT JOIN devices d ON d.id = e.device_id
        WHERE e.device_id = ?
        ORDER BY e.occurred_at DESC
        LIMIT 40`
    )
    .all(id) as Array<Record<string, unknown>>

  const commands = db
    .prepare('SELECT * FROM commands WHERE device_id = ? ORDER BY created_at DESC LIMIT 20')
    .all(id) as Array<Record<string, unknown>>

  const memories = db
    .prepare('SELECT * FROM device_memories WHERE device_id = ? ORDER BY updated_at DESC')
    .all(id) as Array<Record<string, unknown>>

  return {
    device: mapDevice(deviceRow),
    online: isDeviceOnline(id),
    tasks: tasks.map(mapTask),
    events: events.map(mapEvent),
    commands: commands.map(mapCommand),
    memories: memories.map((row) => {
      let entries: unknown[] = []
      try {
        const parsed = JSON.parse(String(row.entries_json ?? '[]'))
        if (Array.isArray(parsed)) entries = parsed
      } catch {
        entries = []
      }
      return {
        package: String(row.package),
        appVersion: row.app_version ? String(row.app_version) : null,
        updatedAt: String(row.updated_at),
        entries
      }
    })
  }
})
