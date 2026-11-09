import type { ActionContext, Module } from 'vuex'
import type { User } from '~~/shared/types'
import type { RootState } from '../index'

export interface AuthState {
  user: User | null
  initialized: boolean
  loading: boolean
}

type Ctx = ActionContext<AuthState, RootState>

export const auth: Module<AuthState, RootState> = {
  namespaced: true,

  state: (): AuthState => ({
    user: null,
    initialized: false,
    loading: false
  }),

  getters: {
    isAuthenticated: (state: AuthState) => Boolean(state.user),
    currentUser: (state: AuthState) => state.user
  },

  mutations: {
    SET_USER(state, user: User | null) {
      state.user = user
    },
    SET_INITIALIZED(state, value: boolean) {
      state.initialized = value
    },
    SET_LOADING(state, value: boolean) {
      state.loading = value
    }
  },

  actions: {
    async login({ commit }: Ctx, payload: { username: string; password: string }) {
      commit('SET_LOADING', true)
      try {
        const { user } = await apiRequest<{ user: User }>('/api/auth/login', {
          method: 'POST',
          body: payload
        })
        commit('SET_USER', user)
        commit('SET_INITIALIZED', true)
        return user
      } finally {
        commit('SET_LOADING', false)
      }
    },

    async logout({ commit }: Ctx) {
      try {
        await apiRequest('/api/auth/logout', { method: 'POST' })
      } finally {
        commit('SET_USER', null)
      }
    },

    async fetchMe({ commit }: Ctx) {
      try {
        const { user } = await apiRequest<{ user: User }>('/api/auth/me')
        commit('SET_USER', user)
      } catch {
        commit('SET_USER', null)
      } finally {
        commit('SET_INITIALIZED', true)
      }
    }
  }
}
