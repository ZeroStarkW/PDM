export class ApiError extends Error {
  /** fields：RFC 7807 扩展字段（字段名 → 校验消息），校验类 400 时后端随 ProblemDetail 返回。 */
  constructor(message: string, public status: number, public fields: Record<string, string> = {}) { super(message) }
}

function cookie(name: string): string | undefined {
  const item = document.cookie.split('; ').find((part) => part.startsWith(`${name}=`))
  return item ? decodeURIComponent(item.slice(name.length + 1)) : undefined
}

type ProblemBody = { detail?: unknown; message?: unknown; fields?: unknown }

function textOf(value: unknown) { return typeof value === 'string' && value.trim() ? value.trim() : '' }

/** RFC 7807 扩展字段 fields（字段 → 错误消息）转为可读片段；兼容字符串、字符串数组与含 message 的对象数组。 */
function fieldMessages(fields: unknown): string[] {
  if (!fields || typeof fields !== 'object') return []
  const lines: string[] = []
  for (const [name, raw] of Object.entries(fields)) {
    const messages = (Array.isArray(raw) ? raw : [raw])
      .map((item) => typeof item === 'string' ? item : item && typeof item === 'object' && 'message' in item && typeof item.message === 'string' ? item.message : '')
      .filter(Boolean)
    if (messages.length) lines.push(`${name}：${[...new Set(messages)].join('；')}`)
  }
  return lines
}

/** 后端未给出 ProblemDetail 时的兜底文案（如网关/反向代理直接返回的错误页）。 */
function fallbackMessage(status: number) {
  const known = ({
    401: '登录已过期，请重新登录',
    403: '当前账号没有执行此操作的权限',
    404: '请求的资源不存在',
  } as Record<number, string>)[status]
  return known ?? (status >= 500 ? `服务暂时不可用 (${status})` : `请求失败 (${status})`)
}

function isAbort(error: unknown) {
  return typeof error === 'object' && error !== null && 'name' in error && error.name === 'AbortError'
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (init.method && !['GET', 'HEAD', 'OPTIONS'].includes(init.method.toUpperCase())) {
    const csrf = cookie('XSRF-TOKEN')
    if (csrf) headers.set('X-XSRF-TOKEN', csrf)
  }
  let response: Response
  try {
    response = await fetch(path.startsWith('/api') ? path : `/api/v1${path}`, {
      ...init, headers, credentials: 'same-origin',
    })
  } catch (error) {
    // 主动中止原样抛出（调用方按 signal.aborted 判断）；其余均为网络层失败，换成可读文案而不是 "Failed to fetch"
    if (isAbort(error)) throw error
    throw new ApiError('网络连接失败，请检查网络或服务是否可用后重试', 0)
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null) as ProblemBody | null
    const detail = textOf(body?.detail) || textOf(body?.message) || fallbackMessage(response.status)
    const fields = fieldMessages(body?.fields)
    // 校验失败时把逐字段消息并入提示，避免只剩一句“请求参数校验失败”
    const message = fields.length && !fields.every((line) => detail.includes(line)) ? `${detail}（${fields.join('；')}）` : detail
    const fieldMap = Object.fromEntries(fields.map((line) => { const [name, ...rest] = line.split('：'); return [name, rest.join('：')] }))
    throw new ApiError(message, response.status, fieldMap)
  }
  if (response.status === 204) return undefined as T
  // 2xx 但响应体不是 JSON（如会话失效被重定向到登录页）时给出可读错误，而不是抛出 SyntaxError
  return response.json().catch(() => { throw new ApiError('服务返回了无法解析的数据，请刷新后重试', response.status) }) as Promise<T>
}

export async function ensureCsrf(): Promise<void> {
  await api<{ token: string }>('/auth/csrf')
}
