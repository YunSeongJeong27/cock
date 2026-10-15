import { Fragment, useMemo, useState, type ReactNode } from 'react'
import { api, dateTime, errorMessage, shortTime, won } from '../../api'
import { CopyButton, ErrorBox, StatusBadge, useIsMobile } from '../../components/Ui'
import type { GroupBuy, SellerOrder } from '../../types'
import OrderEditForm, { changeReply } from './OrderEditForm'

interface Props { groupBuy: GroupBuy; orders: SellerOrder[]; reload: () => void }

type SortKey = 'orderNo' | 'nickname' | 'amount' | 'status' | 'orderedAt' | 'updatedAt'

const SORT_VALUE: Record<SortKey, (o: SellerOrder) => string | number> = {
  orderNo: (o) => o.orderNo,
  nickname: (o) => o.nickname || o.depositorName || '',
  amount: (o) => o.expectedAmount,
  status: (o) => o.statusLabel,
  orderedAt: (o) => o.orderedAt,
  updatedAt: (o) => o.updatedAt,
}

/** 상품별 수량 (같은 상품 줄이 여러 개여도 합산) */
const qtyOf = (o: SellerOrder, productId: number) =>
  o.items.filter((i) => i.productId === productId).reduce((sum, i) => sum + i.quantity, 0)

/**
 * 주문 목록. 링크 주문과 댓글 주문을 모두 보여 주고, 입금 대조 전후 모두 같은 표에서 상태가 바뀐다.
 * PC 는 엑셀처럼 한 줄 한 주문인 그리드(정렬·머리줄 고정·합계줄), 폰은 주문마다 카드.
 */
export default function OrdersTab({ groupBuy, orders, reload }: Props) {
  const mobile = useIsMobile()
  const [filter, setFilter] = useState('all')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'orderNo', desc: false })
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [editing, setEditing] = useState<number | null>(null)
  /** 변경 저장 직후 댓글에 남길 답글 (판매자가 고쳐서 복사) */
  const [reply, setReply] = useState<{ orderId: number; text: string } | null>(null)

  const shown = useMemo(() => {
    const list = orders.filter((o) => {
      if (filter === 'active' && !o.active) return false
      if (filter === 'unpaid' && !(o.active && o.paymentMethod === 'PREPAID' && !o.paid)) return false
      if (filter === 'refund' && !(o.refundRequired && !o.refundedAt)) return false
      if (filter === 'cancelled' && o.active) return false
      if (filter === 'samenick' && o.sameNicknameOrderNos.length === 0) return false
      const q = query.trim()
      if (q && ![o.depositorName, o.nickname, o.phone, o.recipientName, String(o.orderNo)].some((v) => v?.includes(q))
        && !(/^[\d-]+$/.test(q) && o.phone?.replace(/\D/g, '').includes(q.replace(/\D/g, '')))) return false
      return true
    })
    const value = SORT_VALUE[sort.key]
    return [...list].sort((a, b) => {
      const x = value(a)
      const y = value(b)
      const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), 'ko')
      return (sort.desc ? -c : c) || a.orderNo - b.orderNo
    })
  }, [orders, filter, query, sort])

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

  function toggle(id: number) {
    setOpen(open === id ? null : id)
    setEditing(null)
    setReply(null)
  }

  function startEdit(o: SellerOrder) {
    setReply(null)
    if (o.paid) {
      setError('입금 확정된 주문입니다. 바꾸려면 먼저 입금 확정을 취소해 주세요.')
      return
    }
    setError(null)
    setEditing(o.id)
  }

  async function manualPaid(o: SellerOrder) {
    const name = prompt('은행앱에서 확인한 입금자명', o.depositorName ?? '')
    if (!name) return
    const amount = prompt('입금액', String(o.expectedAmount))
    if (!amount) return
    post(`${o.id}/manual-paid`, { depositorName: name, amount: Number(amount.replace(/,/g, '')) })
  }

  const sameNick = orders.filter((o) => o.sameNicknameOrderNos.length > 0).length

  /** 상세: 시각·배송지·입금 이력·변경 이력 + 버튼 (그리드·카드 공통) */
  function detail(o: SellerOrder): ReactNode {
    return (
      <>
        <div className="small">
          주문 {dateTime(o.orderedAt)} · 최종 변경 {dateTime(o.updatedAt)}
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
          {o.changes.map((c, i) => (
            <div key={i} className="change-log">판매자 변경 {dateTime(c.changedAt)} · {c.summary}</div>
          ))}
          {o.cancelledAt && <div>취소: {dateTime(o.cancelledAt)}</div>}
        </div>
        {reply?.orderId === o.id && (
          <div className="edit-box mt">
            <div className="small"><b>변경했습니다.</b> 댓글 답글 예시 (고쳐서 복사해 쓰세요)</div>
            <textarea className="mt reply-text" value={reply.text} onChange={(e) => setReply({ orderId: o.id, text: e.target.value })} />
            <div className="row mt"><CopyButton text={reply.text} label="답글 복사" /><button className="btn small" onClick={() => setReply(null)}>닫기</button></div>
          </div>
        )}
        {editing === o.id ? (
          <OrderEditForm groupBuy={groupBuy} order={o} onClose={() => setEditing(null)}
            onSaved={(updated) => { setEditing(null); setReply({ orderId: o.id, text: changeReply(updated) }); reload() }} />
        ) : (
          <div className="row mt">
            {o.active && !o.pickedUp && <button className="btn small" onClick={() => startEdit(o)}>주문 변경</button>}
            {o.active && o.paymentMethod === 'PREPAID' && !o.paid && <button className="btn small" onClick={() => manualPaid(o)}>수동 입금 확정</button>}
            {o.paid && <button className="btn small" onClick={() => post(`${o.id}/unconfirm-paid`, undefined, '입금 확정을 취소할까요?')}>입금 확정 취소</button>}
            {o.refundRequired && !o.refundedAt && <button className="btn small ok" onClick={() => post(`${o.id}/refunded`)}>환불 완료</button>}
            {o.active && <button className="btn small danger" onClick={() => post(`${o.id}/cancel`, undefined, `${o.orderNo}번 주문을 취소할까요?${o.paid ? ' (입금 확정된 주문이라 환불 필요로 표시됩니다)' : ''}`)}>주문 취소</button>}
          </div>
        )}
      </>
    )
  }

  const warnings = (o: SellerOrder) => (
    <>
      {o.duplicateContact && <span className="sub warn">⚠ 같은 연락처 주문 여러 건</span>}
      {o.sameNicknameOrderNos.length > 0 && <span className="sub warn">⚠ 닉네임 같은 {o.source === 'COMMENT' ? '링크' : '댓글'} 주문 {o.sameNicknameOrderNos.join(', ')}번</span>}
    </>
  )

  function deposit(o: SellerOrder): ReactNode {
    if (o.paymentMethod === 'ONSITE') return <span className="muted">현장결제</span>
    if (o.paid) return <>✓ {shortTime(o.paidAt)}<span className="sub">{o.paidDepositorName} {won(o.paidAmount)}</span></>
    if (o.depositReportedAt) return <>고객 신고<span className="sub">{shortTime(o.depositReportedAt)}</span></>
    return <span className="muted">미입금</span>
  }

  function sortTh(k: SortKey, children: ReactNode, num = false, extra = '') {
    const on = sort.key === k
    return (
      <th key={k} className={`sortable${num ? ' num' : ''}${extra ? ' ' + extra : ''}`} onClick={() => setSort({ key: k, desc: on ? !sort.desc : false })}>
        {children}{on ? (sort.desc ? ' ▼' : ' ▲') : ''}
      </th>
    )
  }

  const products = groupBuy.products
  const columns = 12 + products.length
  const active = shown.filter((o) => o.active)

  const grid = (
    <div className="grid-wrap">
      <table className="grid">
        <thead>
          <tr>
            {sortTh('orderNo', '#', false, 'pin-1')}
            {sortTh('nickname', '닉네임', false, 'pin-2')}
            <th>경로</th>
            <th>입금자명</th>
            <th>휴대폰</th>
            {products.map((p) => <th key={p.id} className="num" title={p.name}>{p.name.length > 10 ? p.name.slice(0, 10) + '…' : p.name}</th>)}
            {sortTh('amount', '금액', true)}
            <th>결제·수령</th>
            <th>입금</th>
            {sortTh('status', '상태')}
            {sortTh('orderedAt', '주문 시각')}
            {sortTh('updatedAt', '최종 변경')}
            <th></th>
          </tr>
        </thead>
        <tbody>
          {shown.map((o) => (
            <Fragment key={o.id}>
              <tr className={`grid-row${o.active ? '' : ' dim'}${open === o.id ? ' open' : ''}`}>
                <td className="pin-1">{o.orderNo}</td>
                <td className="pin-2"><b>{o.nickname || '-'}</b>{o.changes.length > 0 && <span className="chip">변경됨</span>}{warnings(o)}</td>
                <td>{o.source === 'COMMENT' ? <span className="chip">댓글</span> : <span className="muted">링크</span>}</td>
                <td>{o.depositorName || '-'}</td>
                <td>{o.phone || <span className="muted">-</span>}</td>
                {products.map((p) => <td key={p.id} className="num">{qtyOf(o, p.id) || ''}</td>)}
                <td className="num">{won(o.expectedAmount)}</td>
                <td>{o.paymentMethod === 'PREPAID' ? '선입금' : '현장결제'}<span className="sub">{o.receiveMethod === 'DELIVERY' ? '배송' : `픽업${o.pickupPointName ? ' · ' + o.pickupPointName : ''}`}</span></td>
                <td>{deposit(o)}</td>
                <td>
                  <StatusBadge status={o.status} label={o.statusLabel} />
                  {o.refundRequired && !o.refundedAt && <span className="sub"><span className="badge badge-CANCELLED">환불 필요</span></span>}
                  {o.refundedAt && <span className="sub">환불 완료</span>}
                </td>
                <td>{shortTime(o.orderedAt)}</td>
                <td>{shortTime(o.updatedAt)}</td>
                <td><button className="btn small" onClick={() => toggle(o.id)}>{open === o.id ? '닫기' : '상세'}</button></td>
              </tr>
              {open === o.id && <tr className="detail"><td colSpan={columns}><div className="detail-inner">{detail(o)}</div></td></tr>}
            </Fragment>
          ))}
          {shown.length === 0 && <tr><td colSpan={columns} className="muted">주문이 없습니다.</td></tr>}
        </tbody>
        {active.length > 0 && (
          <tfoot>
            <tr>
              <td colSpan={5}>합계 {active.length}건 <span className="muted">(보이는 주문 중 취소 제외)</span></td>
              {products.map((p) => <td key={p.id} className="num">{active.reduce((s, o) => s + qtyOf(o, p.id), 0)}</td>)}
              <td className="num">{won(active.reduce((s, o) => s + o.expectedAmount, 0))}</td>
              <td colSpan={6}></td>
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  )

  const cards = (
    <div className="table-wrap">
      <table className="stack">
        <tbody>
          {shown.map((o) => (
            <Fragment key={o.id}>
              <tr className={o.active ? '' : 'dim'}>
                <td className="stack-title">
                  <span className="muted">{o.orderNo}번 · </span>
                  <b>{o.nickname || o.depositorName}</b>
                  {o.nickname && o.depositorName && <div className="small muted">입금자 {o.depositorName}</div>}
                  {o.phone && <div className="small muted">{o.phone}</div>}
                  {warnings(o)}
                  {o.source === 'COMMENT' && <span className="chip">댓글</span>}
                  {o.changes.length > 0 && <span className="chip">변경됨</span>}
                </td>
                <td className="small" data-label="상품">{o.items.map((i) => `${i.productName} ${i.quantity}`).join(', ')}</td>
                <td data-label="금액">{won(o.expectedAmount)}</td>
                <td className="small" data-label="결제">
                  {o.paymentMethod === 'PREPAID' ? '선입금' : '현장결제'} · {o.receiveMethod === 'DELIVERY' ? '배송' : `픽업${o.pickupPointName ? ' · ' + o.pickupPointName : ''}`}
                </td>
                <td className="small" data-label="입금">{deposit(o)}</td>
                <td data-label="상태">
                  <StatusBadge status={o.status} label={o.statusLabel} />
                  {o.refundRequired && !o.refundedAt && <> <span className="badge badge-CANCELLED">환불 필요</span></>}
                  {o.refundedAt && <span className="small muted"> 환불 완료</span>}
                </td>
                <td className="small" data-label="시각">주문 {dateTime(o.orderedAt)}<br />변경 {dateTime(o.updatedAt)}</td>
                <td className="stack-action"><button className="btn small" onClick={() => toggle(o.id)}>{open === o.id ? '닫기' : '상세'}</button></td>
              </tr>
              {open === o.id && <tr className="stack-detail"><td style={{ background: '#fafafa' }}>{detail(o)}</td></tr>}
            </Fragment>
          ))}
          {shown.length === 0 && <tr><td className="muted">주문이 없습니다.</td></tr>}
        </tbody>
      </table>
    </div>
  )

  return (
    <div>
      {sameNick > 0 && filter !== 'samenick' && (
        <div className="warn-box row between mb">
          <span className="small">⚠ 링크 주문과 댓글 주문의 닉네임이 같은 주문이 {sameNick}건 있습니다. 같은 사람이 두 번 주문한 것이면 하나를 취소하거나 바꿔 주세요.</span>
          <button className="btn small" onClick={() => setFilter('samenick')}>모아 보기</button>
        </div>
      )}
      <div className="row mb">
        <select value={filter} onChange={(e) => setFilter(e.target.value)} style={{ width: 170 }}>
          <option value="all">전체 ({orders.length})</option>
          <option value="active">주문 중 (취소 제외)</option>
          <option value="unpaid">미입금 (선입금)</option>
          <option value="refund">환불 필요</option>
          <option value="cancelled">취소됨</option>
          {(sameNick > 0 || filter === 'samenick') && <option value="samenick">링크·댓글 닉네임 같음 ({sameNick})</option>}
        </select>
        {mobile && (
          <select value={`${sort.key}:${sort.desc ? 'desc' : 'asc'}`} style={{ width: 150 }} aria-label="정렬"
            onChange={(e) => { const [key, dir] = e.target.value.split(':'); setSort({ key: key as SortKey, desc: dir === 'desc' }) }}>
            <option value="orderNo:asc">주문번호순</option>
            <option value="orderedAt:desc">최근 주문순</option>
            <option value="updatedAt:desc">최근 변경순</option>
            <option value="nickname:asc">닉네임순</option>
            <option value="amount:desc">금액 큰 순</option>
          </select>
        )}
        <input type="text" placeholder="닉네임·입금자명·전화 뒷자리·주문번호 검색" value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1, minWidth: 180 }} />
      </div>
      <ErrorBox message={error} />
      {mobile ? cards : grid}
    </div>
  )
}
