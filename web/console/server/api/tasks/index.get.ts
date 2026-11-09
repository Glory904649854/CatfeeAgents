export default defineEventHandler((event) => {
  const query = getQuery(event)
  const status = String(query.status ?? 'all')
  const deviceId = String(query.deviceId ?? '').trim()
  const limit = Math.min(Math.max(Number(query.limit) || 50, 1), 200)

  const db = getDb()
  const params: unknown[] = []
  let sql = `SELECT t.*, d.name AS device_name, u.display_name AS created_by_name
               FROM tasks t
               LEFT JOIN devices d ON d.id = t.device_id
               LEFT JOIN users u ON u.id = t.created_by
              WHERE 1 = 1`

  if (status !== 'all') {
    if (status === 'active') {
      sql += " AND t.status IN ('queued', 'waiting_device', 'accepted', 'running', 'paused', 'stopping')"
    } else {
      sql += ' AND t.status = ?'
      params.push(status)
    }
  }
  if (deviceId) {
    sql += ' AND t.device_id = ?'
    params.push(deviceId)
  }
  sql += ' ORDER BY t.created_at DESC LIMIT ?'
  params.push(limit)

  const rows = db.prepare(sql).all(...params) as Array<Record<string, unknown>>

  const countRows = db
    .prepare('SELECT status, COUNT(*) AS count FROM tasks GROUP BY status')
    .all() as Array<{ status: string; count: number }>

  const counts: Record<string, number> = { all: 0 }
  for (const row of countRows) {
    counts[row.status] = row.count
    counts.all = (counts.all ?? 0) + row.count
  }
  counts.active =
    (counts.queued ?? 0) +
    (counts.waiting_device ?? 0) +
    (counts.accepted ?? 0) +
    (counts.running ?? 0) +
    (counts.paused ?? 0) +
    (counts.stopping ?? 0)

  return { tasks: rows.map(mapTask), counts }
})
