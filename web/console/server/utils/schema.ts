/**
 * SQLite 建表语句。
 * 放在 TypeScript 中而不是 ?raw 导入，确保 Nitro 生产构建不会丢失文件。
 * 所有时间使用 ISO-8601 UTC 字符串，展示层再转本地时间。
 */
export const schemaSql = /* sql */ `
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  display_name  TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'owner',
  created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS enrollment_tokens (
  token       TEXT PRIMARY KEY,
  note        TEXT,
  created_by  TEXT NOT NULL,
  expires_at  TEXT NOT NULL,
  used_at     TEXT,
  created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
  id                TEXT PRIMARY KEY,
  name              TEXT NOT NULL,
  secret_hash       TEXT NOT NULL,
  model             TEXT,
  android_version   TEXT,
  agent_version     TEXT,
  capabilities_json TEXT NOT NULL DEFAULT '[]',
  created_at        TEXT NOT NULL,
  last_seen_at      TEXT,
  last_heartbeat_at TEXT,
  revoked_at        TEXT
);
CREATE INDEX IF NOT EXISTS idx_devices_heartbeat ON devices(last_heartbeat_at);

CREATE TABLE IF NOT EXISTS tasks (
  id           TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  type         TEXT NOT NULL,
  status       TEXT NOT NULL,
  device_id    TEXT NOT NULL,
  created_by   TEXT NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  policy_json  TEXT NOT NULL DEFAULT '{}',
  created_at   TEXT NOT NULL,
  updated_at   TEXT NOT NULL,
  started_at   TEXT,
  finished_at  TEXT,
  error        TEXT
);
CREATE INDEX IF NOT EXISTS idx_tasks_device ON tasks(device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status, created_at DESC);

CREATE TABLE IF NOT EXISTS commands (
  id           TEXT PRIMARY KEY,
  device_id    TEXT NOT NULL,
  task_id      TEXT NOT NULL,
  type         TEXT NOT NULL,
  status       TEXT NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  result       TEXT,
  created_at   TEXT NOT NULL,
  sent_at      TEXT,
  acked_at     TEXT
);
CREATE INDEX IF NOT EXISTS idx_commands_device ON commands(device_id, created_at);
CREATE INDEX IF NOT EXISTS idx_commands_task ON commands(task_id, created_at);

CREATE TABLE IF NOT EXISTS agent_events (
  id            TEXT PRIMARY KEY,
  device_id     TEXT NOT NULL,
  task_id       TEXT,
  run_id        TEXT,
  sequence      INTEGER,
  type          TEXT NOT NULL,
  level         TEXT NOT NULL DEFAULT 'info',
  payload_json  TEXT NOT NULL DEFAULT '{}',
  occurred_at   TEXT NOT NULL,
  received_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_device ON agent_events(device_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_task ON agent_events(task_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_events_received ON agent_events(received_at DESC);

CREATE TABLE IF NOT EXISTS audit_events (
  id          TEXT PRIMARY KEY,
  actor_id    TEXT,
  actor_name  TEXT,
  action      TEXT NOT NULL,
  target_type TEXT,
  target_id   TEXT,
  detail_json TEXT NOT NULL DEFAULT '{}',
  created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_events(created_at DESC);
`
