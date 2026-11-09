import { EventEmitter } from 'node:events'
import type { EventSummary } from '~~/shared/types'

const BUS_KEY = '__catfee_console_bus__'

interface BusHolder {
  bus?: EventEmitter
}

function holder(): BusHolder {
  const globalStore = globalThis as typeof globalThis & { [BUS_KEY]?: BusHolder }
  if (!globalStore[BUS_KEY]) globalStore[BUS_KEY] = {}
  return globalStore[BUS_KEY]!
}

export function getBus(): EventEmitter {
  if (!holder().bus) {
    const bus = new EventEmitter()
    bus.setMaxListeners(200)
    holder().bus = bus
  }
  return holder().bus!
}

/** 推送给 Web 控制台的 SSE 订阅者。 */
export function publishEvent(event: EventSummary): void {
  getBus().emit('agent-event', event)
}

export function publishDeviceUpdate(deviceId: string, status: string): void {
  getBus().emit('device-update', { deviceId, status })
}
