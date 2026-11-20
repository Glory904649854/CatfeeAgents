export default defineEventHandler((event) => {
  requireRole(event, 'owner', 'admin')
  const limit = Math.min(Math.max(Number(getQuery(event).limit) || 50, 1), 200)
  const rows = getDb()
    .prepare('SELECT * FROM audit_events ORDER BY created_at DESC LIMIT ?')
    .all(limit) as Array<Record<string, unknown>>
  return { audits: rows.map(mapAudit) }
})
