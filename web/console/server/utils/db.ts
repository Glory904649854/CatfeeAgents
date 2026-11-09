import Database from 'better-sqlite3'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto'
import { schemaSql } from './schema'
import type { User, UserRole } from '~~/shared/types'

const DB_KEY = '__catfee_console_db__'

interface DbHolder {
  db?: Database.Database
}

function holder(): DbHolder {
  const globalStore = globalThis as typeof globalThis & { [DB_KEY]?: DbHolder }
  if (!globalStore[DB_KEY]) globalStore[DB_KEY] = {}
  return globalStore[DB_KEY]!
}

export function initDatabase(options: {
  file: string
  adminUser: string
  adminPassword: string
}): Database.Database {
  const existing = holder().db
  if (existing) return existing

  const file = resolve(process.cwd(), options.file)
  mkdirSync(dirname(file), { recursive: true })

  const db = new Database(file)
  db.pragma('journal_mode = WAL')
  db.pragma('busy_timeout = 5000')
  db.pragma('foreign_keys = ON')
  db.exec(schemaSql)

  const userCount = db.prepare('SELECT COUNT(*) AS count FROM users').get() as { count: number }
  if (userCount.count === 0) {
    db.prepare(
      `INSERT INTO users (id, username, display_name, password_hash, role, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).run(
      'usr_owner',
      options.adminUser,
      '管理员',
      hashPassword(options.adminPassword),
      'owner',
      new Date().toISOString()
    )
    console.warn(
      `[catfee] 已创建初始管理员账号：${options.adminUser} / ${options.adminPassword}（请尽快修改）`
    )
  }

  holder().db = db
  return db
}

export function getDb(): Database.Database {
  const db = holder().db
  if (!db) throw new Error('Database has not been initialised')
  return db
}

export function hashPassword(password: string): string {
  const salt = randomBytes(16).toString('hex')
  const hash = scryptSync(password, salt, 64).toString('hex')
  return `scrypt$${salt}$${hash}`
}

export function verifyPassword(password: string, stored: string): boolean {
  const [scheme, salt, hash] = stored.split('$')
  if (scheme !== 'scrypt' || !salt || !hash) return false
  const candidate = scryptSync(password, salt, 64)
  const expected = Buffer.from(hash, 'hex')
  return candidate.length === expected.length && timingSafeEqual(candidate, expected)
}

export function mapUser(row: Record<string, unknown>): User {
  return {
    id: String(row.id),
    username: String(row.username),
    displayName: String(row.display_name),
    role: String(row.role) as UserRole,
    createdAt: String(row.created_at)
  }
}
