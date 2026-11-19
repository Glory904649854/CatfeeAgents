/**
 * 对比 CatfeeAgents 主模型与百炼 DECISION 接口在“下一步动作”上的延迟。
 *
 * 用法：
 *   set CATFEE_MAIN_API_KEY=...
 *   set DASHSCOPE_API_KEY=...
 *   node scripts/compare-decision.mjs
 *
 * 可选环境变量：
 *   CATFEE_MAIN_BASE_URL / CATFEE_MAIN_MODEL
 *   DECISION_BASE_URL / DECISION_MODEL
 */
const MAIN_BASE = (process.env.CATFEE_MAIN_BASE_URL || '').replace(/\/+$/, '')
const MAIN_KEY = process.env.CATFEE_MAIN_API_KEY
const MAIN_MODEL = process.env.CATFEE_MAIN_MODEL || 'deepseek-v4.1-flash'

const DECISION_BASE = (
  process.env.DECISION_BASE_URL ||
  'https://llm-cev4lnmqq9bzm5of.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
).replace(/\/+$/, '')
const DECISION_KEY = process.env.DASHSCOPE_API_KEY
const DECISION_MODEL = process.env.DECISION_MODEL || 'decision-model-preview'
const MIN_CONFIDENCE = 0.75
const SAMPLES = Number(process.env.SAMPLES || 4)

if (!MAIN_BASE || !MAIN_KEY || !DECISION_KEY) {
  console.error('缺少 CATFEE_MAIN_BASE_URL / CATFEE_MAIN_API_KEY 或 DASHSCOPE_API_KEY')
  process.exit(1)
}

const questions = {
  next_action: {
    type: 'choice',
    instructions: '根据任务目标、当前步骤和最近一次页面观察，选择下一步最合适的动作。',
    criteria: {
      observe: '需要重新读取当前页面或确认上一步操作结果',
      wait: '页面正在加载、动画未结束，或需要短暂等待',
      act: '需要点击、输入、滑动、启动应用等具体设备操作',
      finish: '任务已经完成，可以立即结束'
    }
  }
}

const tools = [
  {
    type: 'function',
    function: {
      name: 'observe',
      description: '读取当前页面元素，确认上一步操作结果',
      parameters: { type: 'object', properties: {} }
    }
  },
  {
    type: 'function',
    function: {
      name: 'wait',
      description: '等待页面加载或动画结束',
      parameters: {
        type: 'object',
        properties: { ms: { type: 'integer', description: '等待毫秒数' } },
        required: ['ms']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'tap_text',
      description: '按文字点击一个可见控件',
      parameters: {
        type: 'object',
        properties: { text: { type: 'string' } },
        required: ['text']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'input_text',
      description: '向当前聚焦输入框写入文本',
      parameters: {
        type: 'object',
        properties: { text: { type: 'string' } },
        required: ['text']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'swipe',
      description: '滑动屏幕',
      parameters: {
        type: 'object',
        properties: {
          x1: { type: 'integer' },
          y1: { type: 'integer' },
          x2: { type: 'integer' },
          y2: { type: 'integer' }
        },
        required: ['x1', 'y1', 'x2', 'y2']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'finish',
      description: '任务已经完成，提交结果',
      parameters: {
        type: 'object',
        properties: { summary: { type: 'string' } },
        required: ['summary']
      }
    }
  }
]

const scenarios = [
  {
    name: '页面加载中',
    expect: 'wait',
    weight: 0.15,
    state: {
      goal: '等待页面加载完成后再继续',
      step: 5,
      last_tool: 'tap',
      observation: '点击登录后页面白屏，顶部显示「加载中…」',
      foreground_app: 'com.example.app'
    }
  },
  {
    name: '确认页面跳转',
    expect: 'observe',
    weight: 0.25,
    state: {
      goal: '确认刚才的点击是否打开了目标页面，还没到填写步骤',
      step: 6,
      last_tool: 'tap',
      observation: '页面已切换，包含标题和一个继续按钮；需要先确认当前页面结构',
      foreground_app: 'com.example.app'
    }
  },
  {
    name: '登录页可操作',
    expect: 'act',
    weight: 0.25,
    state: {
      goal: '使用测试账号登录',
      step: 7,
      last_tool: 'observe',
      observation: '登录页有手机号、密码输入框和登录按钮，均可见',
      foreground_app: 'com.example.app'
    }
  },
  {
    name: '关闭遮挡弹窗',
    expect: 'act',
    weight: 0.20,
    state: {
      goal: '关闭版本更新弹窗后继续检查登录页',
      step: 8,
      last_tool: 'observe',
      observation: '底部弹出「版本更新」，有「稍后再说」和「立即更新」两个按钮',
      foreground_app: 'com.example.app'
    }
  },
  {
    name: '任务完成',
    expect: 'finish',
    weight: 0.15,
    state: {
      goal: '确认登录页标题和输入框是否正常',
      step: 9,
      last_tool: 'observe',
      observation: '登录页标题、手机号输入框、密码输入框均正常显示，页面没有报错',
      foreground_app: 'com.example.app'
    }
  }
]

async function timed(fn) {
  const t0 = performance.now()
  try {
    const detail = await fn()
    return { ms: Math.round(performance.now() - t0), ...detail }
  } catch (error) {
    return { ms: Math.round(performance.now() - t0), error: String(error) }
  }
}

async function callDecision(state) {
  const res = await fetch(`${DECISION_BASE}/systemone`, {
    method: 'POST',
    headers: { Authorization: DECISION_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: DECISION_MODEL, state, questions })
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`DECISION HTTP ${res.status}: ${text.slice(0, 200)}`)
  const data = JSON.parse(text)
  const answer = data.answers?.next_action
  if (!answer?.choice) throw new Error(`DECISION 响应缺少 choice: ${text.slice(0, 200)}`)
  return {
    action: answer.choice,
    confidence: Number(answer.confidence ?? 0),
    probabilities: answer.probabilities
  }
}

async function callMain(state) {
  const res = await fetch(`${MAIN_BASE}/chat/completions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${MAIN_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: MAIN_MODEL,
      temperature: 0.2,
      max_tokens: 120,
      tools,
      tool_choice: 'auto',
      messages: [
        {
          role: 'system',
          content:
            '你是 Android Agent 的决策器。根据目标和当前页面状态，只调用一个最合适的工具。' +
            '不要输出解释，不要调用多个工具。'
        },
        { role: 'user', content: JSON.stringify(state) }
      ]
    })
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`主模型 HTTP ${res.status}: ${text.slice(0, 200)}`)
  const data = JSON.parse(text)
  const message = data.choices?.[0]?.message
  const call = message?.tool_calls?.[0]
  let action = ''
  if (call?.function?.name === 'tap_text' || call?.function?.name === 'input_text' ||
      call?.function?.name === 'swipe') action = 'act'
  else if (call?.function?.name === 'observe') action = 'observe'
  else if (call?.function?.name === 'wait') action = 'wait'
  else if (call?.function?.name === 'finish') action = 'finish'
  else {
    const content = message?.content || ''
    const match = content.match(/"(?:action|tool)"\s*:\s*"([^"]+)"/)
    action = match?.[1] || 'unknown'
  }
  return { action, usage: data.usage }
}

function median(values) {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.floor(sorted.length / 2)]
}

function mode(values) {
  const counts = new Map()
  for (const value of values) counts.set(value, (counts.get(value) || 0) + 1)
  return [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] || ''
}

console.log(
  `主模型: ${MAIN_MODEL} @ ${MAIN_BASE}\nDECISION: ${DECISION_MODEL} @ ${DECISION_BASE}\n` +
    `每个场景 ${SAMPLES} 次采样（含 1 次预热）\n`
)

const rows = []
for (const scenario of scenarios) {
  await timed(() => callDecision(scenario.state))
  await timed(() => callMain(scenario.state))

  const decisionSamples = []
  const mainSamples = []
  for (let i = 0; i < SAMPLES; i++) {
    decisionSamples.push(await timed(() => callDecision(scenario.state)))
    mainSamples.push(await timed(() => callMain(scenario.state)))
  }

  const decisionOk = decisionSamples.filter((s) => !s.error)
  const mainOk = mainSamples.filter((s) => !s.error)
  const decisionMs = median(decisionOk.map((s) => s.ms))
  const mainMs = median(mainOk.map((s) => s.ms))
  const decisionAction = mode(decisionOk.map((s) => s.action)) || 'ERR'
  const mainAction = mode(mainOk.map((s) => s.action)) || 'ERR'
  const confidence = median(decisionOk.map((s) => Number(s.confidence ?? 0)))
  const decisionAccuracy =
    decisionOk.filter((s) => s.action === scenario.expect).length / Math.max(decisionOk.length, 1)
  const mainAccuracy =
    mainOk.filter((s) => s.action === scenario.expect).length / Math.max(mainOk.length, 1)
  const fast = decisionAction === 'observe' || decisionAction === 'wait'
  const fastEligible = fast && confidence >= MIN_CONFIDENCE
  const decisionModeMs = fastEligible ? decisionMs : decisionMs + mainMs
  const saved = mainMs - decisionModeMs

  rows.push({
    scenario: scenario.name,
    expect: scenario.expect,
    weight: scenario.weight,
    decisionAction,
    decisionAccuracy,
    confidence,
    decisionMs,
    mainAction,
    mainAccuracy,
    mainMs,
    fastEligible,
    decisionModeMs,
    saved
  })
}

const pad = (text, width) => String(text).padEnd(width, ' ')
console.log(
  pad('场景', 16) +
    pad('期望', 8) +
    pad('DECISION', 18) +
    pad('置信度', 9) +
    pad('主模型', 16) +
    pad('主模型ms', 10) +
    pad('快路径', 8) +
    pad('接入后ms', 10) +
    '节省ms'
)
console.log('-'.repeat(110))
for (const row of rows) {
  console.log(
    pad(row.scenario, 16) +
      pad(row.expect, 8) +
      pad(`${row.decisionAction} ${(row.decisionAccuracy * 100).toFixed(0)}%`, 18) +
      pad((row.confidence * 100).toFixed(0) + '%', 9) +
      pad(`${row.mainAction} ${(row.mainAccuracy * 100).toFixed(0)}%`, 16) +
      pad(row.mainMs, 10) +
      pad(row.fastEligible ? '是' : '否', 8) +
      pad(row.decisionModeMs, 10) +
      row.saved
  )
}

const mainMedian = median(rows.map((r) => r.mainMs))
const decisionMedian = median(rows.map((r) => r.decisionMs))
const integratedMedian = median(rows.map((r) => r.decisionModeMs))
const avgMain = rows.reduce((sum, r) => sum + r.mainMs, 0) / rows.length
const avgIntegrated = rows.reduce((sum, r) => sum + r.decisionModeMs, 0) / rows.length
const weightTotal = rows.reduce((sum, r) => sum + r.weight, 0)
const weightedMain = rows.reduce((sum, r) => sum + r.mainMs * r.weight, 0) / weightTotal
const weightedIntegrated =
  rows.reduce((sum, r) => sum + r.decisionModeMs * r.weight, 0) / weightTotal
const fastCount = rows.filter((r) => r.fastEligible).length
const decisionAccuracy =
  rows.reduce((sum, r) => sum + r.decisionAccuracy, 0) / rows.length
const mainAccuracy = rows.reduce((sum, r) => sum + r.mainAccuracy, 0) / rows.length

const signed = (value) => `${value >= 0 ? '+' : ''}${Math.round(value)}ms`

console.log('\n汇总')
console.log(`  主模型下一步中位延迟: ${mainMedian}ms，动作准确率 ${(mainAccuracy * 100).toFixed(0)}%`)
console.log(`  DECISION 中位延迟: ${decisionMedian}ms，动作准确率 ${(decisionAccuracy * 100).toFixed(0)}%`)
console.log(`  接入 DECISION 后单步中位耗时: ${integratedMedian}ms（${fastCount}/${rows.length} 个场景走快路径）`)
console.log(`  等权平均: 主模型 ${Math.round(avgMain)}ms → 接入后 ${Math.round(avgIntegrated)}ms（${signed(avgIntegrated - avgMain)}）`)
console.log(
  `  按场景权重(15/25/25/20/15): 主模型 ${Math.round(weightedMain)}ms → ` +
    `接入后 ${Math.round(weightedIntegrated)}ms（${signed(weightedIntegrated - weightedMain)}）`
)
console.log(
  '  说明: 快路径只跳过 observe/wait；act/finish 仍会额外付出一次 DECISION 判定时间。'
)
