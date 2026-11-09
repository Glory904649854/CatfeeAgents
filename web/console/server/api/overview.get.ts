export default defineEventHandler(() => {
  const db = getDb()
  const now = new Date()
  const todayStart = new Date(now)
  todayStart.setHours(0, 0, 0, 0)
  const todayIso = todayStart.toISOString()
  const onlineThreshold = new Date(Date.now() - 90_000).toISOString()

  const scalar = (sql: string, ...params: unknown[]): number => {
    const row = db.prepare(sql).get(...params) as { count: number }
    return Number(row?.count ?? 0)
  }

  const stats = {
    devicesTotal: scalar('SELECT COUNT(*) AS count FROM devices WHERE revoked_at IS NULL'),
    devicesOnline: scalar(
      'SELECT COUNT(*) AS count FROM devices WHERE revoked_at IS NULL AND last_heartbeat_at >= ?',
      onlineThreshold
    ),
    tasksRunning: scalar(
      `SELECT COUNT(*) AS count FROM tasks
        WHERE status IN ('queued', 'waiting_device', 'accepted', 'running', 'paused', 'stopping')`
    ),
    tasksToday: scalar('SELECT COUNT(*) AS count FROM tasks WHERE created_at >= ?', todayIso),
    tasksFailed: scalar(
      "SELECT COUNT(*) AS count FROM tasks WHERE status = 'failed' AND updated_at >= ?",
      todayIso
    ),
    eventsToday: scalar('SELECT COUNT(*) AS count FROM agent_events WHERE received_at >= ?', todayIso)
  }

  const devices = db
    .prepare(
      `SELECT * FROM devices
        WHERE revoked_at IS NULL
        ORDER BY COALESCE(last_heartbeat_at, created_at) DESC
        LIMIT 6`
    )
    .all() as Array<Record<string, unknown>>

  const tasks = db
    .prepare(
      `SELECT t.*, d.name AS device_name, u.display_name AS created_by_name
         FROM tasks t
         LEFT JOIN devices d ON d.id = t.device_id
         LEFT JOIN users u ON u.id = t.created_by
        ORDER BY t.created_at DESC
        LIMIT 8`
    )
    .all() as Array<Record<string, unknown>>

  const events = db
    .prepare(
      `SELECT e.*, d.name AS device_name
         FROM agent_events e
         LEFT JOIN devices d ON d.id = e.device_id
        ORDER BY e.occurred_at DESC
        LIMIT 12`
    )
    .all() as Array<Record<string, unknown>>

  return {
    stats,
    devices: devices.map(mapDevice),
    tasks: tasks.map(mapTask),
    events: events.map(mapEvent)
  }
})
