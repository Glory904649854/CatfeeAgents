import { useStore } from 'vuex'
import type { User } from '~~/shared/types'
import type { RootState } from '~/store'

let inflight: Promise<void> | null = null

export function useSession() {
  const store = useStore<RootState>()

  async function ensure(): Promise<void> {
    if (store.state.auth.initialized) return
    if (!inflight) {
      inflight = (async () => {
        try {
          const { user } = await apiRequest<{ user: User }>('/api/auth/me')
          store.commit('auth/SET_USER', user)
        } catch {
          store.commit('auth/SET_USER', null)
        } finally {
          store.commit('auth/SET_INITIALIZED', true)
          inflight = null
        }
      })()
    }
    await inflight
  }

  async function logout(): Promise<void> {
    await store.dispatch('auth/logout')
  }

  return { ensure, logout }
}
