import { createStore } from 'vuex'
import { auth, type AuthState } from './modules/auth'
import { devices, type DevicesState } from './modules/devices'
import { tasks, type TasksState } from './modules/tasks'
import { events, type EventsState } from './modules/events'
import { ui, type UiState } from './modules/ui'

export interface RootState {
  auth: AuthState
  devices: DevicesState
  tasks: TasksState
  events: EventsState
  ui: UiState
}

export function createAppStore() {
  return createStore<RootState>({
    modules: {
      auth,
      devices,
      tasks,
      events,
      ui
    },
    strict: import.meta.dev
  })
}

export type AppStore = ReturnType<typeof createAppStore>
