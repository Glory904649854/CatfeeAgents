import { z } from 'zod'

const loginSchema = z.object({
  username: z.string().trim().min(1).max(64),
  password: z.string().min(1).max(200)
})

export default defineEventHandler(async (event) => {
  const parsed = loginSchema.safeParse(await readBody(event))
  if (!parsed.success) throw badRequest('请输入用户名和密码')

  const limitKey = `${getRequestIP(event) || 'unknown'}:${parsed.data.username.toLowerCase()}`
  if (loginBlocked(limitKey)) {
    throw createError({
      statusCode: 429,
      statusMessage: '登录尝试过于频繁，请稍后再试',
      data: { message: '登录尝试过于频繁，请稍后再试' }
    })
  }

  const row = getDb()
    .prepare('SELECT * FROM users WHERE username = ?')
    .get(parsed.data.username) as Record<string, unknown> | undefined

  if (!row || !verifyPassword(parsed.data.password, String(row.password_hash))) {
    recordLoginFailure(limitKey)
    throw unauthorized('用户名或密码错误')
  }
  clearLoginFailures(limitKey)

  const user = mapUser(row)
  createSession(event, user.id)
  event.context.user = user
  recordAudit(event, { action: 'auth.login', targetType: 'user', targetId: user.id })

  return { user }
})
