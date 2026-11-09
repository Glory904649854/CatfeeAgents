/**
 * /api 请求的会话校验。
 * - /api/agent/* 使用设备凭证，在各自 handler 内校验
 * - /api/auth/login、/api/health 公开
 */
const PUBLIC_PATHS = new Set(['/api/auth/login', '/api/health'])

export default defineEventHandler((event) => {
  const path = (event.path || '').split('?')[0]

  if (!path.startsWith('/api/')) return
  if (path.startsWith('/api/agent/')) return
  if (PUBLIC_PATHS.has(path)) return

  const user = resolveSessionUser(event)
  if (!user) {
    throw createError({
      statusCode: 401,
      statusMessage: '未登录或会话已过期',
      data: { message: '未登录或会话已过期' }
    })
  }

  event.context.user = user
})
