export default defineEventHandler((event) => {
  const config = useRuntimeConfig(event)
  const db = getDb()

  const counts = {
    devices: (db.prepare('SELECT COUNT(*) AS count FROM devices').get() as { count: number })
      .count,
    tasks: (db.prepare('SELECT COUNT(*) AS count FROM tasks').get() as { count: number }).count,
    events: (db.prepare('SELECT COUNT(*) AS count FROM agent_events').get() as { count: number })
      .count
  }

  return {
    version: '0.1.0',
    uptimeSeconds: Math.round(process.uptime()),
    dbPath: String(config.dbPath),
    connection: agentConnectionInfo(event),
    auth: {
      sessionDays: Number(config.sessionDays) || 30
    },
    runtime: {
      node: process.version,
      platform: `${process.platform}/${process.arch}`
    },
    counts
  }
})
