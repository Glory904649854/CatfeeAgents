import { useStore } from 'vuex'
import type { RootState } from '~/store'

const PUBLIC_PATHS = new Set(['/login'])

export default defineNuxtRouteMiddleware(async (to) => {
  const store = useStore<RootState>()

  await useSession().ensure()

  const authenticated = Boolean(store.state.auth.user)

  if (PUBLIC_PATHS.has(to.path)) {
    if (authenticated) return navigateTo('/')
    return
  }

  if (!authenticated) {
    return navigateTo({
      path: '/login',
      query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : undefined
    })
  }
})
