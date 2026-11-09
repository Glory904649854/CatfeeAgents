import { createEventStream } from 'h3'

/**
 * 控制台实时事件流（SSE）。
 * 与 Agent 的 WebSocket 不同，这里只做服务端到浏览器的单向推送。
 */
export default defineEventHandler((event) => {
  requireUser(event)

  const eventStream = createEventStream(event)
  const bus = getBus()

  // 立即写入首包，确保代理和浏览器尽快进入 open 状态。
  eventStream.push({ event: 'ready', data: JSON.stringify({ ok: true, serverTime: nowIso() }) })

  const onAgentEvent = (payload: unknown) => {
    eventStream.push({
      event: 'agent-event',
      data: JSON.stringify(payload)
    })
  }

  const onDeviceUpdate = (payload: unknown) => {
    eventStream.push({
      event: 'device-update',
      data: JSON.stringify(payload)
    })
  }

  bus.on('agent-event', onAgentEvent)
  bus.on('device-update', onDeviceUpdate)

  const ping = setInterval(() => {
    eventStream.push({ event: 'ping', data: '{}' })
  }, 15_000)

  eventStream.onClosed(() => {
    clearInterval(ping)
    bus.off('agent-event', onAgentEvent)
    bus.off('device-update', onDeviceUpdate)
  })

  return eventStream.send()
})
