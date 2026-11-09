import { randomBytes, createHash } from 'node:crypto'

export function createId(prefix: string): string {
  return `${prefix}_${randomBytes(9).toString('base64url')}`
}

export function nowIso(): string {
  return new Date().toISOString()
}

export function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex')
}

export function randomToken(bytes = 24): string {
  return randomBytes(bytes).toString('base64url')
}
