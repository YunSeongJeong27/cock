import { useMemo, useState, type FormEvent } from 'react'
import { api, errorMessage, won } from '../../api'
import { ErrorBox } from '../../components/Ui'
import { sellerOrderText as T } from '../../texts/sellerOrders'
import type { GroupBuy, PaymentMethod, ReceiveMethod, SellerOrder } from '../../types'

interface Props {
  groupBuy: GroupBuy
  order: SellerOrder
  onSaved: (updated: SellerOrder) => void
  onClose: () => void
}

/** 고객이 댓글·채팅으로 요청한 변경을 판매자가 대신 반영한다. 휴대폰 번호는 못 바꾼다 (바꾸려면 취소 후 다시 주문) */
export default function OrderEditForm({ groupBuy, order, onSaved, onClose }: Props) {
  const [qty, setQty] = useState<Record<number, string>>(() =>
    Object.fromEntries(order.items.map((i) => [i.productId, String(i.quantity)])))
  const [form, setForm] = useState({
    receiveMethod: order.receiveMethod as ReceiveMethod,
    pickupPointId: order.pickupPointId ? String(order.pickupPointId) : '',
    paymentMethod: order.paymentMethod as PaymentMethod,
    depositorName: order.depositorName ?? '',
    nickname: order.nickname ?? '',
    unitAddress: order.unitAddress ?? '',
    recipientName: order.recipientName ?? '',
    recipientPhone: order.recipientPhone ?? '',
    zipcode: order.zipcode ?? '',
    address: order.address ?? '',
    addressDetail: order.addressDetail ?? '',
  })
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (k: keyof typeof form, v: string) => setForm((f) => ({ ...f, [k]: v }))

  // 서버(SellerOrderService.edit)와 같은 규칙: 공구에서 켠 방법 + 이 주문의 지금 방법 중에서 고르고, 하나뿐이면 그것.
  // 공구에서 그 방법을 꺼도(예: 픽업 끄고 배송만) 이미 받은 주문은 같은 방법 그대로 고칠 수 있다 (2026-10-19).
  // 전에는 공구 설정만 보고 꺼진 방법을 바꿔 보내서, 픽업 주문을 고치려면 배송지를 넣어야 했음 (2026-10-20 점검)
  const receiveOptions = (['PICKUP', 'DELIVERY'] as ReceiveMethod[]).filter((m) => m === order.receiveMethod
    || (m === 'PICKUP' ? groupBuy.pickupEnabled : groupBuy.deliveryEnabled))
  const bothReceive = receiveOptions.length > 1
  const receive: ReceiveMethod = bothReceive ? form.receiveMethod : receiveOptions[0]
  // 결제 방식도 수령 방법을 그대로 두면 지금 방식을 그대로 둘 수 있다 (서버도 둘 다 그대로면 공구 설정을 보지 않음)
  const paymentOptions = (['PREPAID', 'ONSITE'] as PaymentMethod[]).filter((m) => (receive === order.receiveMethod && m === order.paymentMethod)
    || (m === 'PREPAID' ? groupBuy.pickupPrepayEnabled : groupBuy.pickupOnsiteEnabled))
  const bothPayment = paymentOptions.length > 1
  const payment: PaymentMethod = receive === 'DELIVERY' ? 'PREPAID'
    : paymentOptions.includes(form.paymentMethod) && bothPayment ? form.paymentMethod : paymentOptions[0] ?? 'ONSITE'

  // 원래 있던 상품은 주문 당시 단가, 새로 넣는 상품은 지금 단가 (서버와 같음)
  const rows = useMemo(() => groupBuy.products.map((p) => {
    const item = order.items.find((i) => i.productId === p.id)
    return { product: p, ordered: item?.quantity ?? 0, unitPrice: item ? item.unitPrice : p.unitPrice }
  }), [groupBuy.products, order.items])

  const itemsAmount = rows.reduce((sum, r) => sum + r.unitPrice * (Number(qty[r.product.id]) || 0), 0)
  const shippingFee = receive === 'DELIVERY' && groupBuy.shippingFeeType === 'FIXED' ? groupBuy.shippingFee : 0
  const total = itemsAmount + shippingFee

  const forced: string[] = []
  if (!bothReceive && receive !== order.receiveMethod) forced.push(`수령 방법이 ${receive === 'DELIVERY' ? '배송' : '픽업'}`)
  if (!bothPayment && payment !== order.paymentMethod) forced.push(`결제 방식이 ${payment === 'PREPAID' ? '선입금' : '현장결제'}`)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setError(null)
    setBusy(true)
    try {
      const updated = await api.put<SellerOrder>(`/api/seller/group-buys/${groupBuy.id}/orders/${order.id}`, {
        items: rows.map((r) => ({ productId: r.product.id, quantity: Number(qty[r.product.id]) || 0 })).filter((i) => i.quantity > 0),
        receiveMethod: receive,
        pickupPointId: receive === 'PICKUP' && form.pickupPointId ? Number(form.pickupPointId) : null,
        paymentMethod: payment,
        depositorName: form.depositorName || null,
        nickname: form.nickname || null,
        unitAddress: form.unitAddress || null,
        recipientName: receive === 'DELIVERY' ? form.recipientName : null,
        recipientPhone: receive === 'DELIVERY' ? form.recipientPhone : null,
        zipcode: receive === 'DELIVERY' ? form.zipcode || null : null,
        address: receive === 'DELIVERY' ? form.address : null,
        addressDetail: receive === 'DELIVERY' ? form.addressDetail || null : null,
      })
      onSaved(updated)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="edit-box mt">
      <h3>{order.orderNo}번 주문 변경</h3>
      <div className="edit-items">
        {rows.map((r) => (
          <div key={r.product.id} className="edit-item">
            <div className="grow">
              <b>{r.product.name}</b> <span className="small muted">{won(r.unitPrice)}</span>
              <div className="small muted">
                {r.ordered > 0 ? `지금 ${r.ordered}개` : '주문 안 함'}
                {r.product.remaining != null && (r.product.remaining < 0
                  ? <span style={{ color: 'var(--danger)' }}> · 재고 {-r.product.remaining}개 초과</span>
                  : <> · 남은 수량 {r.product.remaining}개</>)}
              </div>
            </div>
            <input type="number" min={0} max={9999} inputMode="numeric" value={qty[r.product.id] ?? ''} placeholder="0"
              onChange={(e) => setQty((q) => ({ ...q, [r.product.id]: e.target.value }))} aria-label={`${r.product.name} 수량`} />
          </div>
        ))}
      </div>

      <div className="edit-fields mt">
        {bothReceive && (
          <label className="field"><span>수령</span>
            <select value={form.receiveMethod} onChange={(e) => set('receiveMethod', e.target.value)}>
              <option value="PICKUP">픽업</option>
              <option value="DELIVERY">배송</option>
            </select>
          </label>
        )}
        {receive === 'PICKUP' && groupBuy.pickupPoints.length > 1 && (
          <label className="field"><span>픽업 지점</span>
            <select value={form.pickupPointId} onChange={(e) => set('pickupPointId', e.target.value)}>
              <option value="">선택</option>
              {groupBuy.pickupPoints.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </label>
        )}
        {receive === 'PICKUP' && bothPayment && (
          <label className="field"><span>결제</span>
            <select value={form.paymentMethod} onChange={(e) => set('paymentMethod', e.target.value)}>
              <option value="PREPAID">선입금</option>
              <option value="ONSITE">현장결제</option>
            </select>
          </label>
        )}
        <label className="field"><span>입금자명{order.source === 'COMMENT' && ' (모르면 비워 두기)'}</span>
          <input type="text" value={form.depositorName} onChange={(e) => set('depositorName', e.target.value)} />
        </label>
        <label className="field"><span>닉네임</span>
          <input type="text" value={form.nickname} onChange={(e) => set('nickname', e.target.value)} />
        </label>
        {(groupBuy.unitAddressFieldEnabled || order.unitAddress) && (
          <label className="field"><span>동·호수</span>
            <input type="text" value={form.unitAddress} onChange={(e) => set('unitAddress', e.target.value)} />
          </label>
        )}
        {receive === 'DELIVERY' && (
          <>
            <label className="field"><span>받는 분</span>
              <input type="text" value={form.recipientName} onChange={(e) => set('recipientName', e.target.value)} />
            </label>
            <label className="field"><span>받는 분 연락처</span>
              <input type="tel" value={form.recipientPhone} onChange={(e) => set('recipientPhone', e.target.value)} />
            </label>
            <label className="field"><span>우편번호</span>
              <input type="text" value={form.zipcode} onChange={(e) => set('zipcode', e.target.value)} />
            </label>
            <label className="field wide-field"><span>주소</span>
              <input type="text" value={form.address} onChange={(e) => set('address', e.target.value)} />
            </label>
            <label className="field wide-field"><span>상세주소</span>
              <input type="text" value={form.addressDetail} onChange={(e) => set('addressDetail', e.target.value)} />
            </label>
          </>
        )}
      </div>
      {forced.length > 0 && (
        <p className="small" style={{ color: 'var(--warn)' }}>{T.forcedBySettings(forced)}</p>
      )}

      <div className="mt">
        <b>변경 후 금액 {won(total)}</b>
        {shippingFee > 0 && <span className="small muted"> (상품 {won(itemsAmount)} + 배송비 {won(shippingFee)})</span>}
        {total !== order.expectedAmount && <span className="small muted"> · 지금 {won(order.expectedAmount)}</span>}
      </div>
      <p className="small muted">
        {T.editNote}
        {order.source === 'COMMENT' && T.editNoteComment}
      </p>
      <ErrorBox message={error} />
      <div className="row">
        <button className="btn primary small" disabled={busy}>{busy ? '저장 중…' : '변경 저장'}</button>
        <button type="button" className="btn small" onClick={onClose} disabled={busy}>닫기</button>
      </div>
    </form>
  )
}

/** 판매자가 댓글에 남길 답글 예시 (판매자가 고쳐서 쓴다) */
export function changeReply(o: SellerOrder): string {
  const who = o.nickname || o.depositorName || '고객'
  const items = o.items.map((i) => `${i.productName} ${i.quantity}개`).join(', ')
  const head = `${who}님, 주문을 ${items}(으)로 변경했어요.`
  // 현장결제는 픽업만 가능 (배송은 선입금만)
  if (o.paymentMethod === 'ONSITE') return `${head} 픽업하실 때 ${won(o.expectedAmount)} 결제해 주세요.`
  return `${head} 변경된 금액 ${won(o.expectedAmount)} 입금해 주시면 확정됩니다.`
}
