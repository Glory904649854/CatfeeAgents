import type { GlobalThemeOverrides } from 'naive-ui'

/**
 * 与 APK Compose 主题对齐的 Naive UI 覆盖。
 * 主色沿用 APK 的 Ink（黑色），品牌红只作为强调色。
 */
export const naiveThemeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#111827',
    primaryColorHover: '#1F2937',
    primaryColorPressed: '#0B1220',
    primaryColorSuppl: '#111827',
    infoColor: '#6B7280',
    infoColorHover: '#4B5563',
    infoColorPressed: '#374151',
    successColor: '#16A34A',
    warningColor: '#D97706',
    errorColor: '#DC2626',
    textColorBase: '#111827',
    textColor1: '#111827',
    textColor2: '#374151',
    textColor3: '#6B7280',
    borderColor: '#E5E7EB',
    dividerColor: '#E5E7EB',
    bodyColor: '#F7F8FA',
    cardColor: '#FFFFFF',
    modalColor: '#FFFFFF',
    popoverColor: '#FFFFFF',
    tableColor: '#FFFFFF',
    inputColor: '#FFFFFF',
    actionColor: '#FAFBFC',
    hoverColor: '#F1F3F5',
    borderRadius: '12px',
    borderRadiusSmall: '8px',
    fontSize: '14px',
    fontSizeSmall: '12.5px',
    fontWeightStrong: '600',
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Noto Sans SC", Inter, sans-serif',
    fontFamilyMono: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace'
  },
  Button: {
    borderRadiusTiny: '8px',
    borderRadiusSmall: '10px',
    borderRadiusMedium: '12px',
    borderRadiusLarge: '14px',
    fontWeight: '500'
  },
  Card: {
    borderRadius: '16px',
    paddingMedium: '20px',
    color: '#FFFFFF',
    borderColor: '#E5E7EB'
  },
  Input: {
    borderRadius: '12px',
    color: '#FFFFFF',
    border: '1px solid #DCE1E8',
    borderHover: '1px solid #C3CAD3',
    borderFocus: '1px solid #111827',
    boxShadowFocus: '0 0 0 2px rgba(17, 24, 39, 0.06)'
  },
  InternalSelection: {
    borderRadius: '12px',
    border: '1px solid #DCE1E8',
    borderHover: '1px solid #C3CAD3',
    borderActive: '1px solid #111827',
    borderFocus: '1px solid #111827',
    boxShadowActive: '0 0 0 2px rgba(17, 24, 39, 0.06)',
    boxShadowFocus: '0 0 0 2px rgba(17, 24, 39, 0.06)'
  },
  Tag: {
    borderRadius: '8px'
  },
  Tabs: {
    tabFontWeightActive: '600',
    tabTextColorActiveLine: '#111827',
    tabTextColorHoverLine: '#111827',
    barColor: '#111827'
  },
  DataTable: {
    borderRadius: '14px',
    thColor: '#FAFBFC',
    thTextColor: '#6B7280',
    tdColor: '#FFFFFF',
    borderColor: '#E5E7EB'
  },
  Dialog: {
    borderRadius: '18px'
  },
  Drawer: {
    color: '#FFFFFF'
  },
  Menu: {
    itemColorActive: '#F1F3F5',
    itemColorActiveHover: '#F1F3F5',
    itemTextColorActive: '#111827',
    itemTextColorActiveHover: '#111827',
    borderRadius: '12px'
  },
  Message: {
    borderRadius: '12px'
  }
}
