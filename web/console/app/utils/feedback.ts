import { createDiscreteApi } from 'naive-ui'
import type { DialogApi, MessageApi } from 'naive-ui'
import { naiveThemeOverrides } from './theme'

interface FeedbackApi {
  message: MessageApi
  dialog: DialogApi
}

let api: FeedbackApi | null = null

/** 脱离组件树的全局反馈 API，样式沿用控制台主题。 */
export function useFeedback(): FeedbackApi {
  if (!api) {
    api = createDiscreteApi(['message', 'dialog'], {
      configProviderProps: { themeOverrides: naiveThemeOverrides }
    })
  }
  return api
}
