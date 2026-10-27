import { useEffect, useState } from 'react'
import { api } from '../api'
import { depositText } from '../texts/deposit'

export interface CaptureStatus {
  visionAvailable: boolean
  usage: { usedToday: number; usedThisMonth: number; dailyLimit: number; remainingToday: number; customLimit: boolean }
}

/** 오늘 남은 캡처(비전 AI) 장수. refreshKey 가 바뀌면 다시 읽는다. */
export function useCaptureStatus(refreshKey: unknown) {
  const [status, setStatus] = useState<CaptureStatus | null>(null)
  useEffect(() => {
    api.get<CaptureStatus>('/api/seller/capture-usage').then(setStatus).catch(() => setStatus(null))
  }, [refreshKey])
  return status
}

export function CaptureUsageLine({ status }: { status: CaptureStatus | null }) {
  if (!status) return null
  if (!status.visionAvailable) {
    return <div className="small muted">{depositText.visionOff}</div>
  }
  const u = status.usage
  return (
    <div className="small" style={{ color: u.remainingToday === 0 ? 'var(--danger)' : 'var(--muted)' }}>
      오늘 남은 캡처 {u.remainingToday}장 / 하루 {u.dailyLimit}장{u.remainingToday === 0 ? ` · ${depositText.captureLimitReached}` : ''}
    </div>
  )
}
