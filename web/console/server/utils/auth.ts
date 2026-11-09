import type { H3Event } from 'h3'
import type { User } from '~~/shared/types'
import { createId, nowIso, randomToken, sha256 } from './ids'

export const SESSION_COOKIE = 'catfee_session'

function sessionMaxAge(event: H3Event): number {
  const config = useRuntimeConfig(event)
  const days = Number(config.sessionDays) || 30
  return days * 24 * 60 * 60
}

function secureCookies(event: H3Event): boolean {
  const config = useRuntimeConfig(event)
  return String(config.publicUrl || '').startsWith('https://')
}

export function createSession(event: H3Event, userId: string): { token: string; expiresAt: string } {
  const token = randomToken(32)
  const createdAt = nowIso()
  const expiresAt = new Date(Date.now() + sessionMaxAge(event) * 1000).toISOString()
  const db = getDb()

  db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(createdAt)
  db.prepare(
    'INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)'
  ).run(sha256(token), userId, createdAt, expiresAt)

  setCookie(event, SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: 'lax',
    path: '/',
    maxAge: sessionMaxAge(event),
    secure: secureCookies(event)
  })

  return { token, expiresAt }
}

export function resolveSessionUser(event: H3Event): User | null {
  const token = getCookie(event, SESSION_COOKIE)
  if (!token) return null

  const row = getDb()
    .prepare(
      `SELECT s.expires_at AS session_expires_at, u.*
         FROM sessions s
         JOIN users u ON u.id = s.user_id
        WHERE s.token_hash = ?`
    )
    .get(sha256(token)) as Record<string, unknown> | undefined

  if (!row) return null

  if (Date.parse(String(row.session_expires_at)) < Date.now()) {
    getDb().prepare('DELETE FROM sessions WHERE token_hash = ?').run(sha256(token))
    return null
  }

  return mapUser(row)
}

export function destroySession(event: H3Event): void {
  const token = getCookie(event, SESSION_COOKIE)
  if (token) getDb().prepare('DELETE FROM sessions WHERE token_hash = ?').run(sha256(token))
  deleteCookie(event, SESSION_COOKIE, { path: '/' })
}

export function requireUser(event: H3Event): User {
  const existing = event.context.user as User | undefined
  if (existing) return existing

  const user = resolveSessionUser(event)
  if (!user) {
    throw createError({
      statusCode: 401,
      statusMessage: '未登录或会话已过期',
      data: { message: '未登录或会话已过期' }
    })
  }
  event.context.user = user
  return user
}

export function recordAudit(
  event: H3Event,
  input: {
    action: string
    targetType?: string
    targetId?: string
    detail?: Record<string, unknown>
  }
): void {
  const user = event.context.user as User | undefined
  getDb()
    .prepare(
      `INSERT INTO audit_events (id, actor_id, actor_name, action, target_type, target_id, detail_json, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    )
    .run(
      createId('aud'),
      user?.id ?? null,
      user?.displayName ?? null,
      input.action,
      input.targetType ?? null,
      input.targetId ?? null,
      JSON.stringify(input.detail ?? {}),
      nowIso()
    )
}

export function sessionSummary(event: H3Event) {
  return {
    session: {
      expiresInDays: Number(useRuntimeConfig(event).sessionDays) || 30,
      secureCookie: secureCookies(event)
    }
  }
}
