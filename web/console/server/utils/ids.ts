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

/** 32 个字符，去掉 I/O/0/1 等易混字符；256 % 32 === 0，取模无偏。 */
const ENROLLMENT_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

/** 一次性设备注册码：8 位、40 bit 熵，展示时格式化为 ABCD-2345。 */
export function createEnrollmentCode(): string {
  const bytes = randomBytes(8)
  let code = ''
  for (let i = 0; i < 8; i += 1) {
    code += ENROLLMENT_ALPHABET[(bytes[i] ?? 0) % ENROLLMENT_ALPHABET.length]
  }
  return code
}

/** 去掉空格、连字符并转大写，兼容手输/扫码/粘贴。 */
export function normalizeEnrollmentCode(code: string): string {
  return code.trim().toUpperCase().replace(/[^A-Z0-9]/g, '')
}

export function formatEnrollmentCode(code: string): string {
  const canonical = normalizeEnrollmentCode(code)
  return canonical.length === 8 ? `${canonical.slice(0, 4)}-${canonical.slice(4)}` : canonical
}
