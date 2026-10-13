import { Fragment, useMemo, useState } from 'react'
import { api, dateTime, errorMessage, won } from '../../api'
import { ErrorBox, StatusBadge } from '../../components/Ui'
import type { GroupBuy, SellerOrder } from '../../types'

interface Props { groupBuy: GroupBuy; orders: SellerOrder[]; reload: () => void }

export default function OrdersTab({ groupBuy, orders, reload }: Props) {
  const [filter, setFilter] = useState('all')
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)

  const shown = useMemo(() => orders.filter((o) => {
    if (filter === 'active' && !o.active) return false
    if (filter === 'unpaid' && !(o.active && o.paymentMethod === 'PREPAID' && !o.paid)) return false
    if (filter === 'refund' && !(o.refundRequired && !o.refundedAt)) return false
    if (filter === 'cancelled' && o.active) return false
    const q = query.trim()
    if (q && ![o.depositorName, o.nickname, o.phone, o.recipientName, String(o.orderNo)].some((v) => v?.includes(q))) return false
    return true
  }), [orders, filter, query])

  async function post(path: string, body?: unknown, confirmText?: string) {
    if (confirmText && !confirm(confirmText)) return
    setError(null)
    try {
      await api.post(`/api/seller/group-buys/${groupBuy.id}/orders/${path}`, body)
      reload()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function manualPaid(o: SellerOrder) {
    const name = prompt('은행앱에서 확인한 입금자명', o.depositorName ?? '')
    if (!name) return
    const amount = prompt('입금액', String(o.expectedAmount))
    if (!amount) return
    post(`${o.id}/manual-paid`, { depositorName: name, amount: Number(amount.replace(/,/g, '')) })
  }

  return (
    <div>
      <div className="row mb">
        <select value={filter} onChange={(e) => setFilter(e.target.value)} style={{ width: 170 }}>
          <option value="all">전체 ({orders.length})</option>
          <option value="active">주문 중 (취소 제외)</option>
          <option value="unpaid">미입금 (선입금)</option>
          <option value="refund">환불 필요</option>
          <option value="cancelled">취소됨</option>
        </select>
        <input type="text" placeholder="이름·닉네임·전화·주문번호 검색" value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1, minWidth: 180 }} />
      </div>
      <ErrorBox message={error} />
      <div className="table-wrap">
        <table className="stack">
          <thead>
            <tr><th>#</th><th>고객</th><th>상품</th><th className="right">금액</th><th>결제</th><th>상태</th><th></th></tr>
          </thead>
          <tbody>
            {shown.map((o) => (
              <Fragment key={o.id}>
                <tr className={o.active ? '' : 'dim'}>
                  <td className="hide-mobile">{o.orderNo}</td>
                  <td className="stack-title">
                    <span className="show-mobile muted">{o.orderNo}번 · </span>
                    <b>{o.nickname || o.depositorName}</b>
                    {o.nickname && o.depositorName && <div className="small muted">입금자 {o.depositorName}</div>}
                    {o.phone && <div className="small muted">{o.phone}</div>}
                    {o.duplicateContact && <div className="small" style={{ color: 'var(--warn)' }}>⚠ 같은 연락처 주문 여러 건</div>}
                    {o.source === 'COMMENT' && <span className="chip">댓글</span>}
                  </td>
                  <td className="small" data-label="상품">{o.items.map((i) => `${i.productName} ${i.quantity}`).join(', ')}</td>
                  <td className="right" data-label="금액">{won(o.expectedAmount)}</td>
                  <td className="small" data-label="결제">
                    {o.paymentMethod === 'PREPAID' ? '선입금' : '현장결제'}
                    <div className="muted">{o.receiveMethod === 'DELIVERY' ? '배송' : `픽업${o.pickupPointName ? ' · ' + o.pickupPointName : ''}`}</div>
                  </td>
                  <td data-label="상태">
                    <StatusBadge status={o.status} label={o.statusLabel} />
                    {o.refundRequired && !o.refundedAt && <div><span className="badge badge-CANCELLED">환불 필요</span></div>}
                    {o.refundedAt && <div className="small muted">환불 완료</div>}
                  </td>
                  <td className="stack-action"><button className="btn small" onClick={() => setOpen(open === o.id ? null : o.id)}>{open === o.id ? '닫기' : '상세'}</button></td>
                </tr>
                {open === o.id && (
                  <tr className="stack-detail">
                    <td colSpan={7} style={{ background: '#fafafa' }}>
                      <div className="small">
                        주문 {dateTime(o.orderedAt)}
                        {o.unitAddress && <> · 동·호수 {o.unitAddress}</>}
                        {o.receiveMethod === 'DELIVERY' && <div>배송: {o.recipientName} / {o.recipientPhone} / ({o.zipcode}) {o.address} {o.addressDetail}</div>}
                        {o.shippingFee > 0 && <div>상품 {won(o.itemsAmount)} + 배송비 {won(o.shippingFee)}</div>}
                        {o.depositReportedAt && <div>고객 입금 신고: {dateTime(o.depositReportedAt)}</div>}
                        {o.payments.map((p, i) => (
                          <div key={i} className={p.cancelledAt ? 'muted' : undefined}>
                            입금 확정{p.manual ? '(수동)' : ''}: {dateTime(p.confirmedAt)} · {p.depositorName} {won(p.amount)}
                            {p.cancelledAt && <> → {dateTime(p.cancelledAt)} 확정 취소</>}
                          </div>
                        ))}
                        {o.cancelledAt && <div>취소: {dateTime(o.cancelledAt)}</div>}
                      </div>
                      <div className="row mt">
                        {o.active && o.paymentMethod === 'PREPAID' && !o.paid && <button className="btn small" onClick={() => manualPaid(o)}>수동 입금 확정</button>}
                        {o.paid && <button className="btn small" onClick={() => post(`${o.id}/unconfirm-paid`, undefined, '입금 확정을 취소할까요?')}>입금 확정 취소</button>}
                        {o.refundRequired && !o.refundedAt && <button className="btn small ok" onClick={() => post(`${o.id}/refunded`)}>환불 완료</button>}
                        {o.active && <button className="btn small danger" onClick={() => post(`${o.id}/cancel`, undefined, `${o.orderNo}번 주문을 취소할까요?${o.paid ? ' (입금 확정된 주문이라 환불 필요로 표시됩니다)' : ''}`)}>주문 취소</button>}
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
            {shown.length === 0 && <tr><td colSpan={7} className="muted">주문이 없습니다.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  )
}
