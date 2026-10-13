import { useState, type ReactNode } from 'react'
import { copyText } from '../api'

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
