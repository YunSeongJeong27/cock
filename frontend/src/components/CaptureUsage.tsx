import { useEffect, useState } from 'react'
import { api } from '../api'

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
    return <div className="small muted">스크린샷 글자 읽기(비전 AI)가 아직 꺼져 있습니다. 읽은 결과 표에 직접 입력할 수 있습니다.</div>
  }
  const u = status.usage
  return (
    <div className="small" style={{ color: u.remainingToday === 0 ? 'var(--danger)' : 'var(--muted)' }}>
      오늘 남은 캡처 {u.remainingToday}장 / 하루 {u.dailyLimit}장{u.remainingToday === 0 ? ' — 오늘 한도 초과, 직접 입력해 주세요' : ''}
    </div>
  )
}
