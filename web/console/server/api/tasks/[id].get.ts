export default defineEventHandler((event) => {
  const id = String(getRouterParam(event, 'id') ?? '')
  const db = getDb()

  const row = db
    .prepare(
      `SELECT t.*, d.name AS device_name, u.display_name AS created_by_name
         FROM tasks t
         LEFT JOIN devices d ON d.id = t.device_id
         LEFT JOIN users u ON u.id = t.created_by
        WHERE t.id = ?`
    )
    .get(id) as Record<string, unknown> | undefined
  if (!row) throw notFound('任务不存在')

  const deviceRow = db.prepare('SELECT * FROM devices WHERE id = ?').get(row.device_id) as
    | Record<string, unknown>
    | undefined

  const events = db
    .prepare(
      `SELECT e.*, d.name AS device_name
         FROM agent_events e
         LEFT JOIN devices d ON d.id = e.device_id
        WHERE e.task_id = ?
        ORDER BY e.occurred_at DESC`
    )
    .all(id) as Array<Record<string, unknown>>

  const commands = db
    .prepare('SELECT * FROM commands WHERE task_id = ? ORDER BY created_at ASC')
    .all(id) as Array<Record<string, unknown>>

  return {
    task: mapTask(row),
    device: deviceRow ? mapDevice(deviceRow) : null,
    events: events.map(mapEvent),
    commands: commands.map(mapCommand)
  }
})
