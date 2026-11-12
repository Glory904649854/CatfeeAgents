export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',

  // 管理控制台不需要 SEO / SSR，SPA 模式可以让 Naive UI 和 WebSocket 生命周期更简单。
  // Nitro 仍然负责同端口的 API、SSE 与 Agent WebSocket。
  ssr: false,

  devtools: { enabled: false },

  modules: ['@unocss/nuxt'],

  css: ['~/assets/css/main.css'],

  app: {
    head: {
      title: 'Catfee 控制台',
      htmlAttrs: { lang: 'zh-CN' },
      meta: [
        { charset: 'utf-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1, viewport-fit=cover' },
        { name: 'theme-color', content: '#F7F8FA' },
        { name: 'description', content: 'Catfee Agents 设备与任务管理控制台' }
      ],
      link: [{ rel: 'icon', type: 'image/png', href: '/brand/logo.png' }]
    }
  },

  devServer: {
    port: 9777
  },

  runtimeConfig: {
    dbPath: './data/catfee.db',
    adminUser: 'admin',
    adminPassword: 'change-me',
    publicUrl: 'http://localhost:9777',
    sessionDays: 30
  },

  nitro: {
    experimental: {
      websocket: true
    },
    externals: {
      external: ['better-sqlite3']
    }
  },

  typescript: {
    strict: true,
    typeCheck: false
  },

  vite: {
    optimizeDeps: {
      include: ['naive-ui', 'vuex']
    }
  }
})
