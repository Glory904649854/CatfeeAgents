import type { ActionContext, Module } from 'vuex'
import type { TaskDetailPayload, TaskSummary } from '~~/shared/types'
import type { RootState } from '../index'

interface TaskListResponse {
  tasks: TaskSummary[]
  counts: Record<string, number>
}

export interface TasksState {
  items: TaskSummary[]
  counts: Record<string, number>
  loading: boolean
  detail: TaskDetailPayload | null
  detailLoading: boolean
}

type Ctx = ActionContext<TasksState, RootState>

export const tasks: Module<TasksState, RootState> = {
  namespaced: true,

  state: (): TasksState => ({
    items: [],
    counts: { all: 0, active: 0 },
    loading: false,
    detail: null,
    detailLoading: false
  }),

  mutations: {
    SET_ITEMS(state, items: TaskSummary[]) {
      state.items = items
    },
    SET_COUNTS(state, counts: Record<string, number>) {
      state.counts = counts
    },
    SET_LOADING(state, value: boolean) {
      state.loading = value
    },
    SET_DETAIL(state, detail: TaskDetailPayload | null) {
      state.detail = detail
    },
    SET_DETAIL_LOADING(state, value: boolean) {
      state.detailLoading = value
    }
  },

  actions: {
    async fetch({ commit }: Ctx, params: { status?: string; deviceId?: string } = {}) {
      commit('SET_LOADING', true)
      try {
        const response = await apiRequest<TaskListResponse>('/api/tasks', {
          query: {
            status: params.status || 'all',
            deviceId: params.deviceId || undefined
          }
        })
        commit('SET_ITEMS', response.tasks)
        commit('SET_COUNTS', response.counts)
      } finally {
        commit('SET_LOADING', false)
      }
    },

    async fetchDetail({ commit }: Ctx, id: string) {
      commit('SET_DETAIL_LOADING', true)
      try {
        const detail = await apiRequest<TaskDetailPayload>(`/api/tasks/${id}`)
        commit('SET_DETAIL', detail)
      } finally {
        commit('SET_DETAIL_LOADING', false)
      }
    },

    async create(
      _ctx: Ctx,
      payload: {
        deviceId: string
        title: string
        type: string
        goal: string
        params?: Record<string, unknown>
        policy?: { allowScreenshot?: boolean; allowRoot?: boolean; timeoutMs?: number }
      }
    ) {
      return await apiRequest<{ task: TaskSummary; delivered: boolean; commandId: string }>(
        '/api/tasks',
        { method: 'POST', body: payload }
      )
    },

    async control(
      _ctx: Ctx,
      payload: { id: string; action: 'pause' | 'resume' | 'stop' }
    ) {
      return await apiRequest<{ ok: boolean; commandId: string; delivered: boolean; note?: string }>(
        `/api/tasks/${payload.id}/${payload.action}`,
        { method: 'POST' }
      )
    },

    async retry(_ctx: Ctx, id: string) {
      return await apiRequest<{ task: TaskSummary; delivered: boolean; commandId: string }>(
        `/api/tasks/${id}/retry`,
        { method: 'POST' }
      )
    }
  }
}
