import type { Module } from 'vuex'
import type { RootState } from '../index'

export interface UiState {
  drawerOpen: boolean
}

export const ui: Module<UiState, RootState> = {
  namespaced: true,

  state: (): UiState => ({
    drawerOpen: false
  }),

  mutations: {
    SET_DRAWER(state, value: boolean) {
      state.drawerOpen = value
    }
  }
}
