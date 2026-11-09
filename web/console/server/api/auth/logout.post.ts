export default defineEventHandler((event) => {
  const user = requireUser(event)
  recordAudit(event, { action: 'auth.logout', targetType: 'user', targetId: user.id })
  destroySession(event)
  return { ok: true }
})
