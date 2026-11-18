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

  const devicesTotal = scalar('SELECT COUNT(*) AS count FROM devices WHERE revoked_at IS NULL')
  const devicesOnline = scalar(
    'SELECT COUNT(*) AS count FROM devices WHERE revoked_at IS NULL AND last_heartbeat_at >= ?',
    onlineThreshold
  )
  const devicesPending = scalar(
    'SELECT COUNT(*) AS count FROM devices WHERE revoked_at IS NULL AND last_heartbeat_at IS NULL'
  )
  const devicesOffline = Math.max(0, devicesTotal - devicesOnline - devicesPending)

  const tasksSucceededToday = scalar(
    "SELECT COUNT(*) AS count FROM tasks WHERE status = 'succeeded' AND updated_at >= ?",
    todayIso
  )
  const tasksFailedToday = scalar(
    "SELECT COUNT(*) AS count FROM tasks WHERE status = 'failed' AND updated_at >= ?",
    todayIso
  )
  const tasksStoppedToday = scalar(
    "SELECT COUNT(*) AS count FROM tasks WHERE status = 'stopped' AND updated_at >= ?",
    todayIso
  )
  const terminalToday = tasksSucceededToday + tasksFailedToday + tasksStoppedToday
  const successRate =
    terminalToday > 0 ? Math.round((tasksSucceededToday / terminalToday) * 100) : 0

  const durationRows = db
    .prepare(
      `SELECT started_at, finished_at FROM tasks
        WHERE status IN ('succeeded', 'failed', 'stopped')
          AND started_at IS NOT NULL
          AND finished_at IS NOT NULL
          AND updated_at >= ?`
    )
    .all(todayIso) as Array<{ started_at: string; finished_at: string }>
  const durations = durationRows
    .map((row) => Date.parse(row.finished_at) - Date.parse(row.started_at))
    .filter((value) => Number.isFinite(value) && value >= 0)
  const avgDurationMs = durations.length
    ? Math.round(durations.reduce((sum, value) => sum + value, 0) / durations.length)
    : 0

  const stats = {
    devicesTotal,
    devicesOnline,
    devicesOffline,
    devicesPending,
    tasksRunning: scalar(
      `SELECT COUNT(*) AS count FROM tasks
        WHERE status IN ('queued', 'waiting_device', 'accepted', 'running', 'paused', 'stopping')`
    ),
    tasksToday: scalar('SELECT COUNT(*) AS count FROM tasks WHERE created_at >= ?', todayIso),
    tasksSucceededToday,
    tasksFailed: tasksFailedToday,
    eventsToday: scalar(
      'SELECT COUNT(*) AS count FROM agent_events WHERE received_at >= ?',
      todayIso
    ),
    successRate,
    avgDurationMs
  }

  const taskStatusCounts = {
    queued: 0,
    waiting_device: 0,
    accepted: 0,
    running: 0,
    paused: 0,
    stopping: 0,
    succeeded: 0,
    failed: 0,
    stopped: 0,
    expired: 0
  }
  const statusRows = db
    .prepare('SELECT status, COUNT(*) AS count FROM tasks WHERE created_at >= ? GROUP BY status')
    .all(todayIso) as Array<{ status: string; count: number }>
  for (const row of statusRows) {
    if (row.status in taskStatusCounts) {
      taskStatusCounts[row.status as keyof typeof taskStatusCounts] = Number(row.count)
    }
  }

  const days: string[] = []
  for (let i = 6; i >= 0; i -= 1) {
    days.push(new Date(Date.now() - i * 86_400_000).toISOString().slice(0, 10))
  }
  const trendRows = db
    .prepare(
      `SELECT substr(created_at, 1, 10) AS day,
              COUNT(*) AS created,
              SUM(CASE WHEN status = 'succeeded' THEN 1 ELSE 0 END) AS succeeded,
              SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failed
         FROM tasks
        WHERE created_at >= ?
        GROUP BY day`
    )
    .all(`${days[0]}T00:00:00.000Z`) as Array<{
    day: string
    created: number
    succeeded: number
    failed: number
  }>
  const trendMap = new Map(trendRows.map((row) => [row.day, row]))
  const taskTrend = days.map((day) => {
    const row = trendMap.get(day)
    return {
      date: day,
      label: day.slice(5),
      created: Number(row?.created ?? 0),
      succeeded: Number(row?.succeeded ?? 0),
      failed: Number(row?.failed ?? 0)
    }
  })

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
        WHERE t.status IN ('queued', 'waiting_device', 'accepted', 'running', 'paused', 'stopping')
        ORDER BY t.created_at DESC
        LIMIT 6`
    )
    .all() as Array<Record<string, unknown>>

  const events = db
    .prepare(
      `SELECT e.*, d.name AS device_name
         FROM agent_events e
         LEFT JOIN devices d ON d.id = e.device_id
        ORDER BY e.occurred_at DESC
        LIMIT 8`
    )
    .all() as Array<Record<string, unknown>>

  return {
    stats,
    taskStatusCounts,
    taskTrend,
    devices: devices.map(mapDevice),
    tasks: tasks.map(mapTask),
    events: events.map(mapEvent)
  }
})
