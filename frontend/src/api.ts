// 백엔드 API 호출. 로그인 토큰은 httpOnly 쿠키라 JS 에서 다루지 않는다 (credentials: 'include').

export class ApiError extends Error {
  status: number
  body: any
  constructor(status: number, message: string, body: any) {
    super(message)
    this.status = status
    this.body = body
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, credentials: 'include', headers: {} }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const res = await fetch(url, init)
  const text = await res.text()
  let data: any = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = text
    }
  }
  if (!res.ok) {
    const message =
      (data && typeof data === 'object' && (data.detail || data.message)) ||
      (res.status === 401 ? '로그인이 필요합니다.' : `요청에 실패했습니다. (${res.status})`)
    throw new ApiError(res.status, message, data)
  }
  return data as T
}

export const api = {
  get: <T>(url: string) => request<T>('GET', url),
  post: <T>(url: string, body?: unknown) => request<T>('POST', url, body),
  put: <T>(url: string, body?: unknown) => request<T>('PUT', url, body),
  del: <T>(url: string) => request<T>('DELETE', url),
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return String(e)
}

export function won(n: number | null | undefined): string {
  if (n === null || n === undefined) return '-'
  return n.toLocaleString('ko-KR') + '원'
}

export function dateTime(s: string | null | undefined): string {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    return ok
  }
}
