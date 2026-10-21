import { useMemo, useState } from 'react'
import { api, errorMessage, itemsText, refundPending, won } from '../../api'
import { ErrorBox } from '../../components/Ui'
import { sellerOrderText as T } from '../../texts/sellerOrders'
import type { GroupBuy, SellerOrder } from '../../types'

interface Props { groupBuy: GroupBuy; orders: SellerOrder[]; reload: () => void }

/** 수령 체크 (정리본 9장): 선입금인데 미입금이면 경고, 현장결제는 수령할 때 결제 완료 처리 */
export default function PickupTab({ groupBuy, orders, reload }: Props) {
  const [query, setQuery] = useState('')
  const [hideDone, setHideDone] = useState(false)
  const [point, setPoint] = useState<string>('all')
  const [error, setError] = useState<string | null>(null)

  const shown = useMemo(() => orders.filter((o) => o.active)
    .filter((o) => !hideDone || !o.pickedUp)
    .filter((o) => point === 'all' || String(o.pickupPointId) === point)
    .filter((o) => {
      const q = query.trim()
      return !q || [o.depositorName, o.nickname, o.phone, o.unitAddress, String(o.orderNo)].some((v) => v?.includes(q))
    }), [orders, query, hideDone, point])

  async function toggle(o: SellerOrder) {
    if (!o.pickedUp && o.paymentMethod === 'PREPAID' && !o.paid
      && !confirm(T.confirmPickupUnpaid(o.nickname || o.depositorName))) return
    setError(null)
    try {
      await api.post(`/api/seller/group-buys/${groupBuy.id}/orders/${o.id}/picked-up`, { pickedUp: !o.pickedUp })
      reload()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <div>
      <div className="row mb">
        <input type="text" placeholder="이름·닉네임·전화 뒷자리·동호수 검색" value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1, minWidth: 180 }} />
        {groupBuy.pickupPoints.length > 1 && (
          <select value={point} onChange={(e) => setPoint(e.target.value)} style={{ width: 150 }}>
            <option value="all">모든 지점</option>
            {groupBuy.pickupPoints.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        )}
        <label className="check"><input type="checkbox" checked={hideDone} onChange={(e) => setHideDone(e.target.checked)} /> 수령 완료 숨기기</label>
      </div>
      <ErrorBox message={error} />
      {shown.map((o) => (
        <div key={o.id} className="card row between" style={{ padding: 12, borderColor: o.pickedUp ? 'var(--ok)' : undefined }}>
          <div className="grow">
            <b>{o.orderNo}. {o.nickname || o.depositorName}</b> <span className="muted small">{o.phone ? o.phone.slice(-4) : ''} {o.unitAddress ?? ''}</span>
            <div className="small">{itemsText(o.items)} · {won(o.expectedAmount)}</div>
            <div className="small">
              {o.paymentMethod === 'ONSITE'
                ? <span style={{ color: 'var(--primary)' }}>현장결제 예정{o.onsitePaid ? ' → 결제 완료' : ''}</span>
                : o.paid ? <span style={{ color: 'var(--ok)' }}>입금 확인됨</span> : <span style={{ color: 'var(--danger)', fontWeight: 700 }}>⚠ 미입금</span>}
              {o.receiveMethod === 'DELIVERY' && <span className="muted"> · 배송</span>}
              {o.pickupPointName && <span className="muted"> · {o.pickupPointName}</span>}
            </div>
          </div>
          {refundPending(o) ? (
            // 환불 신청이 기다리는 주문은 주문 목록에서 [취소하고 환불]·[취소 안 함]을 먼저 (2026-10-19)
            <span className="small" style={{ color: 'var(--warn)', minWidth: 96, textAlign: 'right' }}>환불 신청 중<br />주문 목록에서 먼저 처리</span>
          ) : (
            <button className={`btn ${o.pickedUp ? 'ok' : ''}`} onClick={() => toggle(o)} style={{ minWidth: 96 }}>
              {o.pickedUp ? '수령 ✓' : '수령 체크'}
            </button>
          )}
        </div>
      ))}
      {shown.length === 0 && <p className="muted">표시할 주문이 없습니다.</p>}
    </div>
  )
}
