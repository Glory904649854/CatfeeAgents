import type { MemoryKind } from '~~/shared/types'

const MEMORY_KINDS: MemoryKind[] = ['entry', 'control', 'pitfall', 'flow', 'preference']

/** 设备/控制台写入记忆时的字段收敛, 防止任意大字段或非法类型进库 */
export function sanitizeMemoryEntry(raw: unknown): Record<string, unknown> | null {
  if (!raw || typeof raw !== 'object') return null
  const e = raw as Record<string, unknown>
  const text = String(e.text ?? '').trim().slice(0, 500)
  if (!text) return null
  const kind: MemoryKind = MEMORY_KINDS.includes(String(e.kind) as MemoryKind)
    ? (String(e.kind) as MemoryKind)
    : 'entry'
  const links = Array.isArray(e.links)
    ? e.links.map((x) => String(x).slice(0, 64)).slice(0, 8)
    : []
  const clampInt = (value: unknown, max: number) =>
    Math.max(0, Math.min(max, Math.floor(Number(value) || 0)))
  return {
    id: String(e.id ?? '').slice(0, 64),
    kind,
    page: String(e.page ?? '').slice(0, 80),
    text,
    selector: String(e.selector ?? '').slice(0, 160),
    precondition: String(e.precondition ?? '').slice(0, 120),
    links,
    confidence: Math.min(1, Math.max(0, Number(e.confidence) || 0.6)),
    success: clampInt(e.success, 9999),
    fail: clampInt(e.fail, 9999),
    createdAt: Number(e.createdAt) || Date.now(),
    lastUsedAt: Number(e.lastUsedAt) || 0,
    lastVerifiedAt: Number(e.lastVerifiedAt) || 0,
    sourceRun: String(e.sourceRun ?? '').slice(0, 80)
  }
}

export function parseMemoryEntries(raw: unknown): Record<string, unknown>[] {
  if (typeof raw !== 'string') return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .map((item) => sanitizeMemoryEntry(item))
      .filter((item): item is Record<string, unknown> => item !== null)
      .slice(0, 80)
  } catch {
    return []
  }
}

export function upsertMemoryPackage(
  deviceId: string,
  pkg: string,
  appVersion: string | null,
  entries: Record<string, unknown>[]
): void {
  getDb()
    .prepare(
      `INSERT INTO device_memories (device_id, package, app_version, entries_json, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(device_id, package) DO UPDATE SET
         app_version = excluded.app_version,
         entries_json = excluded.entries_json,
         updated_at = excluded.updated_at`
    )
    .run(deviceId, pkg, appVersion, JSON.stringify(entries), nowIso())
}

export function loadMemoryPackage(
  deviceId: string,
  pkg: string
): { appVersion: string | null; entries: Record<string, unknown>[] } {
  const row = getDb()
    .prepare('SELECT app_version, entries_json FROM device_memories WHERE device_id = ? AND package = ?')
    .get(deviceId, pkg) as { app_version: string | null; entries_json: string } | undefined
  return {
    appVersion: row?.app_version ?? null,
    entries: parseMemoryEntries(row?.entries_json ?? '[]')
  }
}
