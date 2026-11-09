export default defineEventHandler((event) => {
  const query = getQuery(event)
  const level = String(query.level ?? 'all')
  const type = String(query.type ?? '').trim()
  const deviceId = String(query.deviceId ?? '').trim()
  const taskId = String(query.taskId ?? '').trim()
  const limit = Math.min(Math.max(Number(query.limit) || 100, 1), 500)

  const params: unknown[] = []
  let sql = `SELECT e.*, d.name AS device_name
               FROM agent_events e
               LEFT JOIN devices d ON d.id = e.device_id
              WHERE 1 = 1`

  if (level !== 'all') {
    sql += ' AND e.level = ?'
    params.push(level)
  }
  if (type) {
    sql += ' AND e.type LIKE ?'
    params.push(`${type}%`)
  }
  if (deviceId) {
    sql += ' AND e.device_id = ?'
    params.push(deviceId)
  }
  if (taskId) {
    sql += ' AND e.task_id = ?'
    params.push(taskId)
  }
  sql += ' ORDER BY e.occurred_at DESC LIMIT ?'
  params.push(limit)

  const rows = getDb().prepare(sql).all(...params) as Array<Record<string, unknown>>

  return { events: rows.map(mapEvent) }
})
