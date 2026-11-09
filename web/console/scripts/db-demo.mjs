/**
 * 写入演示数据，用于本地视觉验收与空状态之外的页面预览。
 * 不含真实设备或用户数据，可随时用 `pnpm db:reset` 清除。
 *
 * 用法：先启动一次服务（生成表结构和管理员），再执行 `pnpm db:demo`。
 */
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { createHash, randomBytes } from 'node:crypto'
import Database from 'better-sqlite3'

const dbPath = resolve(process.cwd(), process.env.NUXT_DB_PATH || './data/catfee.db')

mkdirSync(dirname(dbPath), { recursive: true })
const db = new Database(dbPath)
db.pragma('journal_mode = WAL')

const initialized = db
  .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'users'")
  .get()
if (!initialized) {
  console.error('数据库尚未初始化。请先运行一次 `pnpm dev`（或 `pnpm build && pnpm start`）再执行演示数据脚本。')
  process.exit(1)
}

const now = Date.now()
const iso = (minutesAgo = 0) => new Date(now - minutesAgo * 60_000).toISOString()
const secret = () => createHash('sha256').update(randomBytes(24)).digest('hex')
const json = (value) => JSON.stringify(value)

const devices = [
  {
    id: 'dev_demo_pixel',
    name: 'Pixel 7 · 测试机',
    model: 'Pixel 7',
    androidVersion: '14',
    agentVersion: '0.9.2',
    capabilities: ['accessibility', 'root', 'ocr', 'screenshot', 'uinput'],
    createdAt: iso(12 * 24 * 60),
    lastHeartbeatAt: iso(0.3),
    lastSeenAt: iso(0.3),
    revokedAt: null
  },
  {
    id: 'dev_demo_xiaomi',
    name: 'Xiaomi 14 · 回归机',
    model: 'Xiaomi 14',
    androidVersion: '15',
    agentVersion: '0.9.2',
    capabilities: ['accessibility', 'ocr', 'screenshot'],
    createdAt: iso(8 * 24 * 60),
    lastHeartbeatAt: iso(18),
    lastSeenAt: iso(18),
    revokedAt: null
  },
  {
    id: 'dev_demo_redmi',
    name: 'Redmi Note 12 · 演示机',
    model: 'Redmi Note 12',
    androidVersion: '13',
    agentVersion: '0.8.7',
    capabilities: ['accessibility', 'root'],
    createdAt: iso(30 * 24 * 60),
    lastHeartbeatAt: iso(3 * 24 * 60),
    lastSeenAt: iso(3 * 24 * 60),
    revokedAt: iso(2 * 24 * 60)
  }
]

const insertDevice = db.prepare(
  `INSERT OR REPLACE INTO devices
     (id, name, secret_hash, model, android_version, agent_version, capabilities_json,
      created_at, last_seen_at, last_heartbeat_at, revoked_at)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
)

for (const device of devices) {
  insertDevice.run(
    device.id,
    device.name,
    secret(),
    device.model,
    device.androidVersion,
    device.agentVersion,
    json(device.capabilities),
    device.createdAt,
    device.lastSeenAt,
    device.lastHeartbeatAt,
    device.revokedAt
  )
}

const policy = { allowScreenshot: true, allowRoot: false, timeoutMs: 120000 }

const tasks = [
  {
    id: 'task_demo_001',
    title: '每日巡检：应用可用性',
    type: 'device.diagnostic',
    status: 'running',
    deviceId: 'dev_demo_pixel',
    createdAt: iso(6),
    updatedAt: iso(0.5),
    startedAt: iso(5.8),
    finishedAt: null,
    goal: '检查授权应用的启动、登录页与核心页面是否可正常访问',
    error: null
  },
  {
    id: 'task_demo_002',
    title: '表单填写回归',
    type: 'app.flow',
    status: 'paused',
    deviceId: 'dev_demo_pixel',
    createdAt: iso(42),
    updatedAt: iso(28),
    startedAt: iso(40),
    finishedAt: null,
    goal: '按模板完成一次表单填写并校验结果',
    error: null
  },
  {
    id: 'task_demo_003',
    title: '设备健康检查',
    type: 'device.diagnostic',
    status: 'succeeded',
    deviceId: 'dev_demo_xiaomi',
    createdAt: iso(130),
    updatedAt: iso(118),
    startedAt: iso(126),
    finishedAt: iso(118),
    goal: '采集设备版本、Root 状态与无障碍服务状态',
    error: null
  },
  {
    id: 'task_demo_004',
    title: '登录流程验证',
    type: 'app.flow',
    status: 'failed',
    deviceId: 'dev_demo_xiaomi',
    createdAt: iso(250),
    updatedAt: iso(242),
    startedAt: iso(248),
    finishedAt: iso(242),
    goal: '验证测试账号登录流程',
    error: '目标控件未找到：登录按钮'
  },
  {
    id: 'task_demo_005',
    title: '采集应用版本信息',
    type: 'device.diagnostic',
    status: 'waiting_device',
    deviceId: 'dev_demo_xiaomi',
    createdAt: iso(25),
    updatedAt: iso(25),
    startedAt: null,
    finishedAt: null,
    goal: '读取目标应用当前版本号并回传',
    error: null
  }
]

const insertTask = db.prepare(
  `INSERT OR REPLACE INTO tasks
     (id, title, type, status, device_id, created_by, payload_json, policy_json,
      created_at, updated_at, started_at, finished_at, error)
   VALUES (?, ?, ?, ?, ?, 'usr_owner', ?, ?, ?, ?, ?, ?, ?)`
)

for (const task of tasks) {
  insertTask.run(
    task.id,
    task.title,
    task.type,
    task.status,
    task.deviceId,
    json({ goal: task.goal, params: {} }),
    json(policy),
    task.createdAt,
    task.updatedAt,
    task.startedAt,
    task.finishedAt,
    task.error
  )
}

const commands = [
  ['cmd_demo_001', 'dev_demo_pixel', 'task_demo_001', 'TASK_ASSIGN', 'acked', iso(6), iso(6), iso(5.9)],
  ['cmd_demo_002', 'dev_demo_pixel', 'task_demo_002', 'TASK_PAUSE', 'acked', iso(28), iso(28), iso(27.8)],
  ['cmd_demo_003', 'dev_demo_xiaomi', 'task_demo_003', 'TASK_ASSIGN', 'acked', iso(130), iso(130), iso(129.5)],
  ['cmd_demo_004', 'dev_demo_xiaomi', 'task_demo_004', 'TASK_ASSIGN', 'acked', iso(250), iso(250), iso(249.4)],
  ['cmd_demo_005', 'dev_demo_xiaomi', 'task_demo_005', 'TASK_ASSIGN', 'pending', iso(25), null, null]
]

const insertCommand = db.prepare(
  `INSERT OR REPLACE INTO commands
     (id, device_id, task_id, type, status, payload_json, result, created_at, sent_at, acked_at)
   VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)`
)

for (const command of commands) {
  insertCommand.run(
    command[0],
    command[1],
    command[2],
    command[3],
    command[4],
    json({ taskId: command[2], protocolVersion: 1 }),
    command[5],
    command[6],
    command[7]
  )
}

const eventRows = []
let eventSeq = 0

function addEvent(deviceId, taskId, minutesAgo, type, level, payload, runId = null) {
  eventSeq += 1
  const occurredAt = iso(minutesAgo)
  eventRows.push({
    id: `evt_demo_${String(eventSeq).padStart(3, '0')}`,
    deviceId,
    taskId,
    runId,
    sequence: eventSeq,
    type,
    level,
    payload,
    occurredAt
  })
}

addEvent('dev_demo_pixel', 'task_demo_001', 5.9, 'task.accepted', 'info', { executor: 'agent' }, 'run_demo_001')
addEvent('dev_demo_pixel', 'task_demo_001', 5.8, 'task.started', 'info', { goal: '每日巡检：应用可用性' }, 'run_demo_001')
addEvent('dev_demo_pixel', 'task_demo_001', 4.2, 'tool.called', 'info', { tool: 'observe', step: 1 }, 'run_demo_001')
addEvent('dev_demo_pixel', 'task_demo_001', 4.1, 'tool.result', 'info', { tool: 'observe', summary: '已进入目标应用首页' }, 'run_demo_001')
addEvent('dev_demo_pixel', 'task_demo_001', 2.4, 'tool.called', 'info', { tool: 'screenshot', step: 2 }, 'run_demo_001')
addEvent('dev_demo_pixel', 'task_demo_001', 2.3, 'tool.result', 'info', { tool: 'screenshot', summary: '页面结构正常，无异常弹窗' }, 'run_demo_001')
addEvent('dev_demo_pixel', 'task_demo_001', 0.6, 'model.response', 'info', { summary: '继续检查登录页可用性' }, 'run_demo_001')

addEvent('dev_demo_pixel', 'task_demo_002', 39.5, 'task.started', 'info', { goal: '表单填写回归' }, 'run_demo_002')
addEvent('dev_demo_pixel', 'task_demo_002', 36, 'tool.result', 'info', { tool: 'input_text', summary: '已填写第 1 组字段' }, 'run_demo_002')
addEvent('dev_demo_pixel', 'task_demo_002', 29, 'task.paused', 'warn', { reason: '控制台请求暂停' }, 'run_demo_002')

addEvent('dev_demo_xiaomi', 'task_demo_003', 126, 'task.started', 'info', { goal: '设备健康检查' }, 'run_demo_003')
addEvent('dev_demo_xiaomi', 'task_demo_003', 121, 'tool.result', 'info', { tool: 'device_info', summary: 'Android 15 / 无 Root 权限' }, 'run_demo_003')
addEvent('dev_demo_xiaomi', 'task_demo_003', 118, 'task.succeeded', 'info', { summary: '健康检查完成，共 3 项通过' }, 'run_demo_003')

addEvent('dev_demo_xiaomi', 'task_demo_004', 248, 'task.started', 'info', { goal: '登录流程验证' }, 'run_demo_004')
addEvent('dev_demo_xiaomi', 'task_demo_004', 244, 'tool.result', 'warn', { tool: 'observe', summary: '未发现登录按钮，尝试滚动' }, 'run_demo_004')
addEvent('dev_demo_xiaomi', 'task_demo_004', 242, 'task.failed', 'error', { error: '目标控件未找到：登录按钮' }, 'run_demo_004')

addEvent('dev_demo_xiaomi', 'task_demo_005', 25, 'task.queued', 'info', { reason: '设备离线，等待重新连接' })

addEvent('dev_demo_pixel', null, 0.4, 'device.online', 'info', { agentVersion: '0.9.2' })
addEvent('dev_demo_xiaomi', null, 18, 'device.offline', 'warn', { reason: 'socket closed' })
addEvent('dev_demo_redmi', null, 2 * 24 * 60, 'security.device_revoked', 'security', { revokedBy: 'admin' })

const insertEvent = db.prepare(
  `INSERT OR REPLACE INTO agent_events
     (id, device_id, task_id, run_id, sequence, type, level, payload_json, occurred_at, received_at)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
)

for (const row of eventRows) {
  insertEvent.run(
    row.id,
    row.deviceId,
    row.taskId,
    row.runId,
    row.sequence,
    row.type,
    row.level,
    json(row.payload),
    row.occurredAt,
    row.occurredAt
  )
}

console.log(
  `demo data written: ${devices.length} devices, ${tasks.length} tasks, ${eventRows.length} events`
)
db.close()
