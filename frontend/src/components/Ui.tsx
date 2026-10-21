import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { copyText } from '../api'

/**
 * 문구 파일(src/texts)의 문장을 그린다. **굵게** 와 [글자](/경로) 링크(새 탭)만 바꾼다.
 * 링크는 우리 사이트 경로(/로 시작)만 받는다: 고객이 적은 값이 문장에 들어가도 바깥 주소 링크가 되지 않게
 */
export function Rich({ text, strongStyle }: { text: string; strongStyle?: CSSProperties }) {
  return (
    <>
      {text.split(/(\*\*[^*]+\*\*|\[[^\]]+\]\(\/[a-z][^)\s]*\))/).map((part, i) => {
        if (part.startsWith('**') && part.endsWith('**') && part.length > 4) return <b key={i} style={strongStyle}>{part.slice(2, -2)}</b>
        const link = /^\[([^\]]+)\]\((\/[a-z][^)\s]*)\)$/.exec(part)
        if (link) return <a key={i} href={link[2]} target="_blank" rel="noreferrer">{link[1]}</a>
        return part
      })}
    </>
  )
}

export function ErrorBox({ message }: { message: string | null | undefined }) {
  if (!message) return null
  return <div className="error-box" style={{ whiteSpace: 'pre-line' }}>{message}</div>
}

export function CopyButton({ text, label = '복사' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false)
  return (
    <button
      type="button"
      className="btn small"
      onClick={async () => {
        if (await copyText(text)) {
          setDone(true)
          setTimeout(() => setDone(false), 1500)
        }
      }}
    >
      {done ? '복사됨 ✓' : label}
    </button>
  )
}

export function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: 'ok' | 'warn' | 'danger' }) {
  return (
    <div className={`stat ${tone ?? ''}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}

export function StatusBadge({ status, label }: { status: string; label: string }) {
  return <span className={`badge badge-${status}`}>{label}</span>
}

/** 폰 폭(640px 이하)인지. index.css 의 폰 카드 전환 기준과 같다 */
export function useIsMobile(): boolean {
  const query = '(max-width: 640px)'
  const [mobile, setMobile] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches)
  useEffect(() => {
    const m = window.matchMedia(query)
    const onChange = () => setMobile(m.matches)
    m.addEventListener('change', onChange)
    return () => m.removeEventListener('change', onChange)
  }, [])
  return mobile
}
