import { useEffect, useState } from 'react'
import { api, dateTime, errorMessage } from '../../api'
import { CopyButton, ErrorBox } from '../../components/Ui'
import { groupBuyText as T } from '../../texts/groupBuy'
import type { GroupBuy } from '../../types'

interface Line { orderId: number; orderNo: number; displayName: string; nickname: string | null; depositorName: string | null; itemsText: string; paidAt: string | null; pickedUp: boolean }
interface Announcement { text: string; lines: Line[] }

/**
 * 입금 확인 문구 자동 생성 + 복사 (판매자가 밴드·카톡에 붙여넣음)
 * 입금 확인된 사람 닉네임 목록: 밴드 @태그는 붙여넣기로 안 되므로 한 명씩 태그하면서 체크한다.
 */
export default function AnnounceTab({ groupBuy }: { groupBuy: GroupBuy }) {
  const [data, setData] = useState<Announcement | null>(null)
  const [since, setSince] = useState('')
  const [error, setError] = useState<string | null>(null)
  const storageKey = `dc_tagged_${groupBuy.id}`
  const [tagged, setTagged] = useState<Record<number, boolean>>(() => {
    try {
      return JSON.parse(localStorage.getItem(storageKey) ?? '{}')
    } catch {
      return {}
    }
  })

  useEffect(() => {
    const q = since ? `?since=${encodeURIComponent(since + ':00')}` : ''
    api.get<Announcement>(`/api/seller/group-buys/${groupBuy.id}/announcement${q}`).then(setData).catch((e) => setError(errorMessage(e)))
  }, [groupBuy.id, since])

  function toggle(id: number) {
    const next = { ...tagged, [id]: !tagged[id] }
    setTagged(next)
    try {
      localStorage.setItem(storageKey, JSON.stringify(next))
    } catch {
      // 저장 못 해도 화면은 동작
    }
  }

  return (
    <div>
      <ErrorBox message={error} />
      <div className="card">
        <div className="row between">
          <h3>입금 확인 문구</h3>
          {data && <CopyButton text={data.text} label="문구 복사" />}
        </div>
        <label className="field small"><span>이 시각 이후 확정분만 (비우면 전체)</span>
          <input type="datetime-local" value={since} onChange={(e) => setSince(e.target.value)} style={{ maxWidth: 260 }} />
        </label>
        <textarea readOnly value={data?.text ?? ''} style={{ minHeight: 180 }} />
      </div>
      <div className="card">
        <h3>입금 확인된 사람 ({data?.lines.length ?? 0}명)</h3>
        <p className="muted small">{T.tagHint}</p>
        {data?.lines.map((l) => (
          <label key={l.orderId} className="check" style={{ padding: '6px 0', borderBottom: '1px solid var(--line)' }}>
            <input type="checkbox" checked={Boolean(tagged[l.orderId])} onChange={() => toggle(l.orderId)} />
            <span className="grow" style={{ textDecoration: tagged[l.orderId] ? 'line-through' : undefined }}>
              <b>{l.displayName}</b> <span className="muted small">{l.itemsText} · 확정 {dateTime(l.paidAt)}</span>
            </span>
            <CopyButton text={l.displayName} label="이름 복사" />
          </label>
        ))}
      </div>
    </div>
  )
}
