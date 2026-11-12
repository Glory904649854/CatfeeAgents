/**
 * DECISION 路由 vs 普通 chat 模型的延迟对比。
 *
 * 仅用于评估"下一步动作分类"的接口延迟，不代表完整任务耗时。
 * Key 从环境变量读取，不写入仓库：
 *   set DASHSCOPE_API_KEY=sk-...
 *   node scripts/bench-decision.mjs
 */
const BASE =
  process.env.DECISION_BASE_URL ||
  'https://llm-cev4lnmqq9bzm5of.cn-beijing.maas.aliyuncs.com/compatible-mode/v1'
const KEY = process.env.DASHSCOPE_API_KEY
if (!KEY) {
  console.error('缺少 DASHSCOPE_API_KEY')
  process.exit(1)
}

const state = {
  goal: '检查目标应用的登录页是否可用，如有弹窗先关闭',
  step: 4,
  last_tool: 'observe',
  observation:
    '当前为应用首页，顶部有“登录”按钮；底部出现“版本更新”弹窗，包含“稍后再说”和“立即更新”。任务尚未完成。',
  foreground_app: 'com.example.app'
}

const questions = {
  next_action: {
    type: 'choice',
    instructions: '根据目标和当前页面，选择下一步最合适的动作。',
    criteria: {
      observe: '需要重新读取页面或确认上一步结果',
      wait: '页面正在加载或动画未结束',
      act: '需要点击、输入、滑动或启动应用',
      finish: '任务已经完成，可以结束'
    }
  }
}

const chatSystem = `你是 Android Agent 的快速路由器。只输出一行 JSON，不要解释。
可选 action: observe / wait / act / finish。
根据目标与页面状态选择下一步动作，并给出 0~1 的 confidence。
输出格式: {"action":"...","confidence":0.0}`

async function timed(fn) {
  const t0 = performance.now()
  try {
    const detail = await fn()
    return { ms: Math.round(performance.now() - t0), ...detail }
  } catch (error) {
    return { ms: Math.round(performance.now() - t0), error: String(error) }
  }
}

async function callDecision() {
  const res = await fetch(`${BASE}/systemone`, {
    method: 'POST',
    headers: { Authorization: KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'decision-model-preview', state, questions })
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 200)}`)
  const data = JSON.parse(text)
  return {
    choice: data.answers?.next_action?.choice,
    confidence: data.answers?.next_action?.confidence,
    serverLatency: data.latency_ms
  }
}

async function callChat(model) {
  const res = await fetch(`${BASE}/chat/completions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model,
      temperature: 0.2,
      max_tokens: 60,
      messages: [
        { role: 'system', content: chatSystem },
        { role: 'user', content: JSON.stringify(state) }
      ]
    })
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 200)}`)
  const data = JSON.parse(text)
  return {
    content: data.choices?.[0]?.message?.content?.replace(/\s+/g, ' ').slice(0, 100),
    usage: data.usage
  }
}

async function series(label, fn, runs = 6) {
  await timed(fn)
  const results = []
  for (let i = 0; i < runs; i++) results.push(await timed(fn))
  const ok = results.filter((r) => !r.error)
  const times = ok.map((r) => r.ms).sort((a, b) => a - b)
  console.log(`\n${label}`)
  console.log('  runs:', results.map((r) => (r.error ? `ERR(${r.ms}ms)` : `${r.ms}ms`)).join(', '))
  if (times.length) {
    const median = times[Math.floor(times.length / 2)]
    const avg = Math.round(times.reduce((a, b) => a + b, 0) / times.length)
    console.log(`  min=${times[0]}ms median=${median}ms avg=${avg}ms max=${times[times.length - 1]}ms`)
  }
  if (ok[0]) console.log('  sample:', JSON.stringify(ok[0]))
  return times
}

console.log('DECISION vs chat model（仅路由判断）')
const decision = await series('DECISION /systemone', callDecision, 8)
await series('chat deepseek-v4.1-flash', () => callChat('deepseek-v4.1-flash'), 6)
if (decision.length) {
  console.log(`\nDECISION median ${decision[Math.floor(decision.length / 2)]}ms`)
}
