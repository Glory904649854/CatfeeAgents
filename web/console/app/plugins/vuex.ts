import { createAppStore } from '~/store'

export default defineNuxtPlugin((nuxtApp) => {
  const store = createAppStore()
  nuxtApp.vueApp.use(store)
})
