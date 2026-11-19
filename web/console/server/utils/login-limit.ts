/**
 * 登录失败限流：同一来源 + 用户名在窗口期内失败过多时暂时拒绝。
 * 单进程内存实现，够用于单实例部署；多实例部署请换成共享存储。
 */

interface Attempt {
  count: number
  resetAt: number
}

const attempts = new Map<string, Attempt>()
const MAX_FAILURES = 8
const WINDOW_MS = 10 * 60 * 1000
const MAX_ENTRIES = 5000

function sweep(now: number): void {
  if (attempts.size < MAX_ENTRIES) return
  for (const [key, entry] of attempts) {
    if (entry.resetAt <= now) attempts.delete(key)
  }
}

export function loginBlocked(key: string): boolean {
  const entry = attempts.get(key)
  if (!entry) return false
  if (entry.resetAt <= Date.now()) {
    attempts.delete(key)
    return false
  }
  return entry.count >= MAX_FAILURES
}

export function recordLoginFailure(key: string): void {
  const now = Date.now()
  sweep(now)
  const entry = attempts.get(key)
  if (!entry || entry.resetAt <= now) {
    attempts.set(key, { count: 1, resetAt: now + WINDOW_MS })
    return
  }
  entry.count += 1
}

export function clearLoginFailures(key: string): void {
  attempts.delete(key)
}
