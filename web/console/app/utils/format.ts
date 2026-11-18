const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
})

const fullFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
})

const numberFormatter = new Intl.NumberFormat('zh-CN')

export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return dateTimeFormatter.format(date)
}

export function formatFullDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return fullFormatter.format(date)
}

export function formatRelative(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'

  const diff = Date.now() - date.getTime()
  if (diff < 0) return formatDateTime(value)

  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return `${Math.max(seconds, 1)} 秒前`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return formatDateTime(value)
}

export function formatDuration(from?: string | null, to?: string | null): string {
  if (!from) return '—'
  const start = new Date(from).getTime()
  const end = to ? new Date(to).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return '—'

  const seconds = Math.floor((end - start) / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分`
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
}

export function formatNumber(value: number): string {
  return numberFormatter.format(value)
}

export function formatDurationMs(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  const totalSeconds = Math.round(ms / 1000)
  if (totalSeconds < 60) {
    return ms < 10_000 ? `${(ms / 1000).toFixed(1)} 秒` : `${totalSeconds} 秒`
  }
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes < 60) return `${minutes} 分 ${seconds} 秒`
  const hours = Math.floor(minutes / 60)
  return `${hours} 小时 ${minutes % 60} 分`
}

export function formatCompactNumber(value?: number | null): string {
  if (value == null || !Number.isFinite(value)) return '0'
  if (value < 1000) return String(Math.round(value))
  if (value < 1_000_000) {
    const k = value / 1000
    return `${k < 10 ? k.toFixed(1) : Math.round(k)}K`
  }
  return `${(value / 1_000_000).toFixed(1)}M`
}

export function formatSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '—'
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时`
  return `${Math.floor(seconds / 86400)} 天`
}

export function shortId(value?: string | null): string {
  if (!value) return '—'
  if (value.length <= 16) return value
  return `${value.slice(0, 6)}…${value.slice(-4)}`
}
