import { useStore } from 'vuex'
import type { EventSummary } from '~~/shared/types'
import type { RootState } from '~/store'

let source: EventSource | null = null
let refs = 0

/** 全局共享一条 SSE 连接，只在默认布局挂载时建立。 */
export function useLiveStream() {
  const store = useStore<RootState>()

  function start() {
    if (source) return

    const eventSource = new EventSource('/api/stream')
    source = eventSource

    eventSource.onopen = () => {
      store.commit('events/SET_LIVE_CONNECTED', true)
    }

    eventSource.onerror = () => {
      store.commit('events/SET_LIVE_CONNECTED', false)
    }

    eventSource.addEventListener('agent-event', (message) => {
      try {
        const event = JSON.parse((message as MessageEvent).data) as EventSummary
        store.commit('events/PREPEND', event)
      } catch {
        /* ignore malformed payload */
      }
    })

    eventSource.addEventListener('device-update', () => {
      // 页面按需监听 events.latest / device-update，不在全局强制刷新列表。
    })
  }

  function stop() {
    source?.close()
    source = null
    store.commit('events/SET_LIVE_CONNECTED', false)
  }

  onMounted(() => {
    refs += 1
    start()
  })

  onBeforeUnmount(() => {
    refs -= 1
    if (refs <= 0) stop()
  })

  return {
    connected: computed(() => store.state.events.liveConnected)
  }
}
