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
      slate: '#64748B'
    },
    fontFamily: {
      sans: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans SC", Inter, sans-serif',
      mono: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace'
    }
  },

  shortcuts: [
    ['flat-card', 'bg-surface border border-line rounded-[16px]'],
    ['flat-panel', 'bg-surface border border-line rounded-[14px] p-4'],
    ['nav-item', 'flex items-center gap-3 px-3 py-2.5 rounded-[12px] text-[13.5px] text-muted transition-colors hover:bg-subtle hover:text-ink'],
    ['nav-item-active', 'bg-subtle text-ink font-600'],
    ['page-title', 'text-[22px] leading-7 font-700 tracking-tight text-ink'],
    ['page-sub', 'text-[12.5px] text-muted mt-1'],
    ['chip', 'inline-flex items-center gap-1.5 rounded-[7px] bg-subtle px-2 py-1 text-[11.5px] text-muted'],
    ['icon-btn', 'inline-flex h-9 w-9 items-center justify-center rounded-[11px] border border-line bg-surface text-muted transition hover:(bg-subtle text-ink)'],
    ['metric-value', 'text-[26px] font-700 tracking-tight text-ink tabular-nums'],
    ['label-xs', 'text-[10.5px] font-600 uppercase tracking-[0.14em] text-faint'],
    ['field-label', 'text-[11.5px] font-600 text-muted'],
    ['link-quiet', 'text-muted transition hover:(text-ink underline)']
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
