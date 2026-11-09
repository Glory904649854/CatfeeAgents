import type { ActionContext, Module } from 'vuex'
import type { DeviceDetailPayload, DeviceSummary } from '~~/shared/types'
import type { RootState } from '../index'

interface DeviceListResponse {
  devices: DeviceSummary[]
  counts: Record<string, number>
}

export interface DevicesState {
  items: DeviceSummary[]
  counts: Record<string, number>
  loading: boolean
  detail: DeviceDetailPayload | null
  detailLoading: boolean
}

type Ctx = ActionContext<DevicesState, RootState>

export const devices: Module<DevicesState, RootState> = {
  namespaced: true,

  state: (): DevicesState => ({
    items: [],
    counts: { all: 0, online: 0, offline: 0, revoked: 0, pending: 0 },
    loading: false,
    detail: null,
    detailLoading: false
  }),

  mutations: {
    SET_ITEMS(state, items: DeviceSummary[]) {
      state.items = items
    },
    SET_COUNTS(state, counts: Record<string, number>) {
      state.counts = counts
    },
    SET_LOADING(state, value: boolean) {
      state.loading = value
    },
    SET_DETAIL(state, detail: DeviceDetailPayload | null) {
      state.detail = detail
    },
    SET_DETAIL_LOADING(state, value: boolean) {
      state.detailLoading = value
    }
  },

  actions: {
    async fetch({ commit }: Ctx, params: { status?: string; q?: string } = {}) {
      commit('SET_LOADING', true)
      try {
        const response = await apiRequest<DeviceListResponse>('/api/devices', {
          query: {
            status: params.status || 'all',
            q: params.q || undefined
          }
        })
        commit('SET_ITEMS', response.devices)
        commit('SET_COUNTS', response.counts)
      } finally {
        commit('SET_LOADING', false)
      }
    },

    async fetchDetail({ commit }: Ctx, id: string) {
      commit('SET_DETAIL_LOADING', true)
      try {
        const detail = await apiRequest<DeviceDetailPayload>(`/api/devices/${id}`)
        commit('SET_DETAIL', detail)
      } finally {
        commit('SET_DETAIL_LOADING', false)
      }
    },

    async revoke({ commit }: Ctx, id: string) {
      const { device } = await apiRequest<{ device: DeviceSummary }>(`/api/devices/${id}/revoke`, {
        method: 'POST'
      })
      commit('SET_DETAIL', null)
      return device
    }
  }
}
