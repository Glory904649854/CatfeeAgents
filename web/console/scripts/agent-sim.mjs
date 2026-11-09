/**
 * Agent 协议联调脚本：模拟一台 Android 设备完成注册、连接、接任务、上报事件。
 * 仅用于本地开发，不包含任何真实设备数据。
 *
 * 运行：pnpm agent:sim
 * 前置：控制台已启动，且管理员账号可用（默认 admin / change-me）。
 */
const BASE = (process.env.CATFEE_BASE_URL || 'http://localhost:3000').replace(/\/+$/, '')
const USERNAME = process.env.CATFEE_ADMIN_USER || 'admin'
const PASSWORD = process.env.CATFEE_ADMIN_PASSWORD || 'change-me'

function fail(message) {
  console.error(`[sim] ${message}`)
  process.exit(1)
}

async function request(path, { method = 'GET', body, cookie } = {}) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(cookie ? { cookie } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })
  const text = await response.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!response.ok) {
    throw new Error(`${method} ${path} -> ${response.status}: ${text}`)
  }
  return { data, response }
}

if (typeof WebSocket === 'undefined') {
  fail('当前 Node 不支持全局 WebSocket，请使用 Node 22.21+ / 24+')
}

console.log(`[sim] base=${BASE}`)

const login = await request('/api/auth/login', {
  method: 'POST',
  body: { username: USERNAME, password: PASSWORD }
})
const rawCookies =
  typeof login.response.headers.getSetCookie === 'function'
    ? login.response.headers.getSetCookie()
    : [login.response.headers.get('set-cookie')].filter(Boolean)
const cookie = rawCookies.map((value) => value.split(';')[0]).join('; ')
if (!cookie) fail('登录未返回会话 Cookie')
console.log('[sim] 控制台登录成功')

const invite = (
  await request('/api/devices/enroll', {
    method: 'POST',
    cookie,
    body: { note: 'agent-sim' }
  })
).data

const enrollment = (
  await request('/api/agent/enroll', {
    method: 'POST',
    body: {
      token: invite.token,
      name: '模拟器 · 协议测试',
      model: 'Agent Simulator',
      androidVersion: '14',
      agentVersion: '0.0.0-sim',
      capabilities: ['accessibility', 'screenshot', 'ocr']
    }
  })
).data
console.log(`[sim] 设备已注册 ${enrollment.deviceId}`)

const created = (
  await request('/api/tasks', {
    method: 'POST',
    cookie,
    body: {
      deviceId: enrollment.deviceId,
      title: '协议联调：模拟任务',
      type: 'device.diagnostic',
      goal: '验证 Web 控制台与 Agent WebSocket 的完整闭环',
      policy: { allowScreenshot: true, allowRoot: false, timeoutMs: 60000 }
    }
  })
).data
const taskId = created.task.id
console.log(`[sim] 任务已创建 ${taskId}（delivered=${created.delivered}）`)

const ws = new WebSocket(enrollment.connection.wsUrl)
let sequence = 0
let finished = false
const runTag = Date.now().toString(36)

const timeout = setTimeout(() => fail('等待 Agent 流程超时'), 20000)

function send(message) {
  ws.send(JSON.stringify(message))
}

function pushEvent(type, payload = {}, level = 'info') {
  sequence += 1
  send({
    type: 'event',
    event: {
      id: `sim_${runTag}_${sequence}`,
      taskId,
      runId: 'run_sim',
      sequence,
      type,
      level,
      payload,
      occurredAt: new Date().toISOString()
    }
  })
}

ws.addEventListener('open', () => {
  console.log('[sim] WebSocket 已连接，发送 hello')
  send({
    type: 'hello',
    deviceId: enrollment.deviceId,
    secret: enrollment.deviceSecret,
    agentVersion: '0.0.0-sim',
    androidVersion: '14',
    model: 'Agent Simulator',
    capabilities: ['accessibility', 'screenshot', 'ocr']
  })
})

ws.addEventListener('message', (event) => {
  let message
  try {
    message = JSON.parse(event.data)
  } catch {
    return
  }

  if (message.type === 'hello_ack') {
    console.log('[sim] hello_ack，等待任务指令')
    send({ type: 'heartbeat' })
    return
  }

  if (message.type === 'error') {
    fail(`服务端错误：${message.code || ''} ${message.message || ''}`)
    return
  }

  if (message.type === 'command' && message.commandType === 'TASK_ASSIGN') {
    if (message.taskId !== taskId) return
    console.log(`[sim] 收到任务指令 ${message.commandId}`)
    send({
      type: 'command_ack',
      commandId: message.commandId,
      taskId: message.taskId,
      status: 'accepted'
    })

    pushEvent('task.accepted', { executor: 'agent-sim' })
    setTimeout(() => pushEvent('task.started', { goal: '协议联调' }), 120)
    setTimeout(() => pushEvent('tool.called', { tool: 'observe', step: 1 }), 300)
    setTimeout(
      () => pushEvent('tool.result', { tool: 'observe', summary: '已读取模拟页面结构' }),
      450
    )
    setTimeout(() => {
      pushEvent('task.succeeded', { summary: '协议联调完成' })
      finished = true
      setTimeout(verify, 600)
    }, 700)
  }
})

ws.addEventListener('error', () => fail('WebSocket 连接失败'))
ws.addEventListener('close', (event) => {
  if (!finished) console.log(`[sim] WebSocket 关闭 code=${event.code}`)
})

async function verify() {
  try {
    const detail = (await request(`/api/tasks/${taskId}`, { cookie })).data
    console.log(
      `[sim] 验证任务状态=${detail.task.status} 事件数=${detail.events.length} 指令数=${detail.commands.length}`
    )
    if (detail.task.status !== 'succeeded') fail('任务未按预期完成')
    clearTimeout(timeout)
    ws.close()
    console.log('[sim] 协议联调通过')
    process.exit(0)
  } catch (error) {
    fail(error.message)
  }
}
