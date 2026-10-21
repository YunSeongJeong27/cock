// 백엔드 API 호출. 로그인 토큰은 httpOnly 쿠키라 JS 에서 다루지 않는다 (credentials: 'include').
import { commonText } from './texts/common'
import type { SellerOrder } from './types'

/** 서버 오류 응답: message = detail (화면에 보여줄 문구), code = 서버 ErrorCode 이름 */
export class ApiError extends Error {
  status: number
  body: any
  code: string | null
  constructor(status: number, message: string, body: any) {
    super(message)
    this.status = status
    this.body = body
    this.code = body && typeof body === 'object' && typeof body.code === 'string' ? body.code : null
  }
}

// 쓰기 요청에 항상 붙이는 헤더. 서버(CsrfHeaderFilter)는 이 헤더가 없는 /api 쓰기 요청을 거부한다 (CSRF 방어).
const CSRF_HEADER = { 'X-Requested-With': 'depositcock' }

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { ...CSRF_HEADER }
  const init: RequestInit = { method, credentials: 'include', headers }
  if (body instanceof FormData) {
    init.body = body
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const res = await fetch(url, init)
  // 판매자·관리자 화면에서 로그인이 만료되면 로그인 화면으로 보낸다 (/api/me 는 로그인 확인용이라 제외)
  if (res.status === 401 && (url.startsWith('/api/seller') || url.startsWith('/api/admin'))
      && !window.location.pathname.startsWith('/login')) {
    window.location.href = '/login?expired=1'
  }
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
      (res.status === 401 ? commonText.loginRequired : commonText.requestFailed(res.status))
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

/** 이름 비교용: 공백을 모두 지운다 ("김 지영" = "김지영", 서버 Texts.squash 와 같음) */
export function squash(v: string | null | undefined): string {
  return (v ?? '').replace(/\s+/g, '')
}

/** 판매자 확인을 기다리는 고객 환불 신청 (주문 목록·수령 체크가 같이 씀) */
export function refundPending(o: SellerOrder): boolean {
  return o.active && o.refundRequestedAt !== null && o.refundRejectedAt === null
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

/**
 * 목록 화면용 휴대폰 가림: "010-1234-5678" → "010-****-5678".
 * 개인정보의 안전성 확보조치 기준 "출력(화면표시) 시 용도에 따라 출력 항목 최소화" 때문에, 목록에서는 가리고
 * 연락이 필요한 주문 상세에서만 전체를 보인다 (2026-10-15). 검색은 전체 번호로도 된다.
 */
export function maskPhone(phone: string | null | undefined): string {
  if (!phone) return ''
  const d = phone.replace(/\D/g, '')
  if (d.length < 8) return phone
  return `${d.slice(0, 3)}-****-${d.slice(-4)}`
}

/** 표용 짧은 시각: "10/15 16:42" */
export function shortTime(s: string | null | undefined): string {
  if (!s) return '-'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`
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
