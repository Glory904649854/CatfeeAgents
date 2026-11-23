import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetUno,
  transformerDirectives
} from 'unocss'

/**
 * 设计令牌来自 APK 的 Compose 主题（MainActivity.kt）：
 * Bg #F7F8FA / Ink #111827 / Muted #6B7280 / Line #E5E7EB / Accent #E11D2E
 * 保持黑白红品牌色与扁平风格，不使用大面积阴影和渐变。
 */
export default defineConfig({
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons({
      scale: 1.15,
      warn: false,
      extraProperties: {
        display: 'inline-block',
        'vertical-align': 'middle'
      }
    })
  ],

  transformers: [transformerDirectives()],

  theme: {
    colors: {
      canvas: '#F7F8FA',
      surface: '#FFFFFF',
      ink: '#111827',
      muted: '#6B7280',
      faint: '#9CA3AF',
      line: '#E5E7EB',
      subtle: '#F1F3F5',
      soft: '#FAFBFC',
      brand: '#E11D2E',
      success: '#16A34A',
      warning: '#D97706',
      danger: '#DC2626',
      violet: '#7C3AED',
      slate: '#64748B',
      sidebar: '#0B1220'
    },
    fontFamily: {
      sans: '"Noto Sans SC", Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
      mono: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
    }
  },

  shortcuts: [
    ['flat-card', 'bg-surface border border-line rounded-[14px]'],
    ['flat-panel', 'bg-surface border border-line rounded-[14px] p-5'],
    ['nav-item', 'relative flex items-center gap-3 h-[38px] px-3 rounded-[8px] text-[13px] text-white/60 transition-colors hover:bg-white/5 hover:text-white'],
    ['nav-item-active', 'bg-white/[0.08] text-white font-500'],
    ['page-title', 'text-[28px] leading-9 font-700 tracking-tight text-ink'],
    ['page-sub', 'text-[12px] text-muted mt-1'],
    ['chip', 'inline-flex items-center gap-1 rounded-[6px] bg-subtle px-2 py-1 font-mono text-[10.5px] text-muted'],
    ['icon-btn', 'inline-flex h-9 w-9 items-center justify-center rounded-full border border-line bg-surface text-muted transition hover:(bg-subtle text-ink)'],
    ['metric-value', 'text-[34px] leading-none font-700 tracking-tight text-ink tabular-nums'],
    ['label-xs', 'text-[11px] font-500 text-muted'],
    ['field-label', 'text-[12px] font-500 text-muted'],
    ['link-quiet', 'text-muted transition hover:(text-ink underline)'],
    ['btn-primary', 'inline-flex h-10 items-center justify-center gap-1.5 rounded-[10px] bg-ink px-4 text-[13px] font-500 text-white transition hover:bg-ink/90 disabled:(bg-subtle text-faint)'],
    ['btn-secondary', 'inline-flex h-10 items-center justify-center gap-1.5 rounded-[10px] border border-line bg-white px-4 text-[13px] font-500 text-ink transition hover:(bg-subtle)'],
    ['btn-brand', 'inline-flex h-10 items-center justify-center gap-1.5 rounded-[10px] bg-brand px-4 text-[13px] font-500 text-white transition hover:bg-brand/90'],
    ['section-title', 'text-[15px] font-600 text-ink'],
    ['mono-meta', 'font-mono text-[11px] text-faint']
  ],

  safelist: [
    'i-lucide-activity',
    'i-lucide-circle-check',
    'i-lucide-circle-alert',
    'i-lucide-clock',
    'i-lucide-cpu',
    'i-lucide-wifi',
    'i-lucide-wifi-off',
    'i-lucide-key-round',
    'i-lucide-shield-check'
  ]
})
