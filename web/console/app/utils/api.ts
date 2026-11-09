type ApiMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface ApiRequestOptions {
  method?: ApiMethod
  body?: unknown
  query?: Record<string, unknown>
  headers?: Record<string, string>
}

/**
 * 轻量 HTTP 封装：隔离 Nuxt 的生成式路由类型推断，
 * 同时为所有控制台请求保留明确的返回类型。
 */
export function apiRequest<T = unknown>(url: string, options: ApiRequestOptions = {}): Promise<T> {
  const request = $fetch as unknown as (
    url: string,
    options?: ApiRequestOptions
  ) => Promise<T>
  return request(url, options)
}
