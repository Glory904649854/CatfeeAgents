export default defineEventHandler((event) => {
  const query = getQuery(event)
  const status = String(query.status ?? 'all')
  const keyword = String(query.q ?? '').trim()

  const params: string[] = []
  let sql = 'SELECT * FROM devices WHERE 1 = 1'
  if (keyword) {
    sql += ' AND (name LIKE ? OR id LIKE ? OR model LIKE ?)'
    const like = `%${keyword}%`
    params.push(like, like, like)
  }
  sql += ' ORDER BY (revoked_at IS NOT NULL) ASC, COALESCE(last_heartbeat_at, created_at) DESC'

  const rows = getDb().prepare(sql).all(...params) as Array<Record<string, unknown>>
  const devices = rows.map(mapDevice)
  const filtered = status === 'all' ? devices : devices.filter((item) => item.status === status)

  return {
    devices: filtered,
    counts: {
      all: devices.length,
      online: devices.filter((item) => item.status === 'online').length,
      offline: devices.filter((item) => item.status === 'offline').length,
      revoked: devices.filter((item) => item.status === 'revoked').length,
      pending: devices.filter((item) => item.status === 'pending').length
    }
  }
})
