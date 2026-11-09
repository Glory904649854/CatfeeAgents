import type { ActionContext, Module } from 'vuex'
import type { EventSummary } from '~~/shared/types'
import type { RootState } from '../index'

export interface EventsState {
  items: EventSummary[]
  loading: boolean
  liveConnected: boolean
  latest: EventSummary | null
}

type Ctx = ActionContext<EventsState, RootState>

const MAX_BUFFERED_EVENTS = 200

export const events: Module<EventsState, RootState> = {
  namespaced: true,

  state: (): EventsState => ({
    items: [],
    loading: false,
    liveConnected: false,
    latest: null
  }),

  mutations: {
    SET_ITEMS(state, items: EventSummary[]) {
      state.items = items
    },
    SET_LOADING(state, value: boolean) {
      state.loading = value
    },
    SET_LIVE_CONNECTED(state, value: boolean) {
      state.liveConnected = value
    },
    PREPEND(state, event: EventSummary) {
      if (!event?.id) return
      if (state.items.some((item) => item.id === event.id)) return
      state.items = [event, ...state.items].slice(0, MAX_BUFFERED_EVENTS)
      state.latest = event
    },
    SET_LATEST(state, event: EventSummary | null) {
      state.latest = event
    }
  },

  actions: {
    async fetch(
      { commit }: Ctx,
      params: { level?: string; type?: string; deviceId?: string; taskId?: string; limit?: number } = {}
    ) {
      commit('SET_LOADING', true)
      try {
        const response = await apiRequest<{ events: EventSummary[] }>('/api/events', {
          query: {
            level: params.level || 'all',
            type: params.type || undefined,
            deviceId: params.deviceId || undefined,
            taskId: params.taskId || undefined,
            limit: params.limit || 100
          }
        })
        commit('SET_ITEMS', response.events)
      } finally {
        commit('SET_LOADING', false)
      }
    }
  }
}
