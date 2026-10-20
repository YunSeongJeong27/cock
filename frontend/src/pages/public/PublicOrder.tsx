import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, api, dateTime, errorMessage, won } from '../../api'
import { CopyButton, ErrorBox, Rich, StatusBadge } from '../../components/Ui'
import { orderText as T } from '../../texts/order'
import type { PaymentMethod, ReceiveMethod, ShippingFeeType } from '../../types'

/**
 * remaining: 1개 이상일 때만 값이 있음. blockWhenSoldOut 이면 남은 수량까지만 주문 가능하고 soldOut 이면 품절,
 * 아니면 0 이하에서 수량 표시만 숨기고 주문은 받음
 */
interface PublicProduct { id: number; name: string; unitPrice: number; remaining: number | null; perOrderLimit: number | null; blockWhenSoldOut: boolean; soldOut: boolean }
interface Account { bankName: string; accountNumber: string; accountHolder: string }
interface MyOrder {
  orderNo: number
  items: { productId: number; productName: string; quantity: number; unitPrice: number; amount: number }[]
  itemsAmount: number
  shippingFee: number
  expectedAmount: number
  receiveMethod: ReceiveMethod
  pickupPointName: string | null
  paymentMethod: PaymentMethod
  depositorName: string
  status: string
  statusLabel: string
  /** 선입금 주문이 주문 확정 전 (입금 계좌를 보여줌) */
  awaitingConfirm: boolean
  /** 바로 취소 가능 (주문 확정 전, 수령 전, 주문 받는 중) */
  cancellable: boolean
  /** 환불 신청 가능 (선입금, 수령 전, 신청한 적 없음. 마감 뒤에도 됨) */
  refundRequestable: boolean
  /** REQUESTED: 판매자 확인 중 / REJECTED: 반려 */
  refundState: 'REQUESTED' | 'REJECTED' | null
  refundRequired: boolean
  /** 판매자가 환불 완료를 누른 시각 */
  refundedAt: string | null
  orderedAt: string
  /** 판매자가 주문을 마지막으로 바꾼 시각 */
  changedAt: string | null
  /** 고객이 수정한 마지막 시각 */
  customerChangedAt: string | null
  /** 고객이 수정할 수 있는 상태 (주문 확정 전, 수령 전, 주문 받는 중) */
  replaceable: boolean
  /** [주문 수정]을 누르면 주문서에 채울 값 (수정할 수 있는 주문만) */
  editValues: Prefill | null
}
interface Prefill {
  phone: string; depositorName: string; nickname: string | null; unitAddress: string | null; receiveMethod: ReceiveMethod
  pickupPointId: number | null; paymentMethod: PaymentMethod; recipientName: string | null; recipientPhone: string | null
  zipcode: string | null; address: string | null; addressDetail: string | null
  /** true: 이 기기에서 넣은 주문이 아니라(내 주문 찾기) 휴대폰·동호수·배송지는 비어 있음 */
  limited: boolean
}
interface View {
  title: string; description: string | null; sellerName: string; acceptingOrders: boolean; closedReason: string | null; closeAt: string | null
  products: PublicProduct[]; pickupPoints: { id: number; name: string; info: string | null }[]
  pickupEnabled: boolean; pickupPrepayEnabled: boolean; pickupOnsiteEnabled: boolean; deliveryEnabled: boolean
  shippingFeeType: ShippingFeeType; shippingFee: number; nicknameRequired: boolean; unitAddressFieldEnabled: boolean
  kakaoInquiryUrl: string | null; account: Account | null; myOrders: MyOrder[]; prefill: Prefill | null
  /** 판매자가 주문을 잠시 멈춤: 주문·수정·취소·환불 신청이 막힘 (내 주문 보기는 됨) */
  paused: boolean; pauseMessage: string | null; pauseLinkUrl: string | null
}

/**
 * 주문 확정 전은 바로 취소 또는 (입금했으면) 환불 신청, 확정 후는 환불 신청만 (판매자가 확인 후 취소·환불).
 * 주문 확정 = 판매자가 대조해서 입금 확정한 상태 (2026-10-19 사용자 결정. 고객 "입금했어요" 신고는 없앰)
 */
type CancelMode = 'cancel' | 'refund'
const emptyCancel = {
  mode: 'cancel' as CancelMode, orderNo: '', phone: '', depositorName: '', bankName: '', accountNumber: '',
  open: false, notice: '', error: '', choices: [] as MyOrder[],
}
/** 방금 넣거나 바꾼 주문의 최종 내용 (이번에 적은 값만 들어 있음) */
interface FinalOrder {
  order: MyOrder; nickname: string | null; unitAddress: string | null; recipientName: string | null; recipientPhone: string | null
  zipcode: string | null; address: string | null; addressDetail: string | null
}
/** changed: 새 주문이 아니라 같은 번호의 기존 주문(orderNo)을 바꿈 */
interface Placed {
  orderNo: number; expectedAmount: number; paymentMethod: PaymentMethod; depositorName: string; account: Account | null; myOrders: MyOrder[]
  changed: boolean; finalOrder: FinalOrder
}
/** replaceOrderNo·replaceOrder: 확인하면 이 주문이 지금 적은 내용으로 바뀜 (없으면 새 주문) */
interface Warnings { list: string[]; replaceOrderNo: number | null; replaceOrder: MyOrder | null }

declare global {
  interface Window { daum?: any }
}

/** 카카오(다음) 우편번호 서비스. 키 불필요, 상업적 이용 무료 (postcode.map.kakao.com/guide) */
function loadPostcode(): Promise<void> {
  if (window.daum?.Postcode) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'
    s.onload = () => resolve()
    s.onerror = () => reject(new Error(T.postcodeLoadFailed))
    document.head.appendChild(s)
  })
}

/**
 * 주문 내용 (2026-10-18 사용자 요청): 바뀐 뒤 완료 카드의 "최종 주문", 경고의 "기존 주문 확인".
 * f(이번에 적은 값)가 있을 때만 배송지·닉네임·동호수도 보여준다
 */
function OrderSummary({ o, title, f }: { o: MyOrder; title: ReactNode; f?: FinalOrder }) {
  return (
    <div className="receipt mt">
      <div className="small">{title}</div>
      {o.items.map((i) => (
        <div key={i.productId} className="row between receipt-line">
          <span>{i.productName} <b>{i.quantity}개</b> <span className="small muted" style={{ whiteSpace: 'nowrap' }}>× {won(i.unitPrice)}</span></span>
          <span>{won(i.amount)}</span>
        </div>
      ))}
      {o.shippingFee > 0 && <div className="row between receipt-line"><span>배송비</span><span>{won(o.shippingFee)}</span></div>}
      <div className="row between receipt-total"><b>총 금액</b><b>{won(o.expectedAmount)}</b></div>
      <div className="small mt">
        <div>수령: {o.receiveMethod === 'DELIVERY' ? '배송' : `픽업${o.pickupPointName ? ' · ' + o.pickupPointName : ''}`}</div>
        {o.receiveMethod === 'DELIVERY' && f && (
          <div>배송지: {f.recipientName} · {f.recipientPhone} · {f.zipcode ? `(${f.zipcode}) ` : ''}{f.address}{f.addressDetail ? ' ' + f.addressDetail : ''}</div>
        )}
        <div>결제: {o.paymentMethod === 'PREPAID' ? `선입금 (입금자명 ${o.depositorName})` : `현장결제 (이름 ${o.depositorName})`}</div>
        {f?.nickname && <div>닉네임: {f.nickname}</div>}
        {f?.unitAddress && <div>동·호수: {f.unitAddress}</div>}
      </div>
    </div>
  )
}

const emptyForm = {
  phone: '', depositorName: '', nickname: '', unitAddress: '', receiveMethod: '' as ReceiveMethod | '', pickupPointId: '',
  paymentMethod: '' as PaymentMethod | '', recipientName: '', recipientPhone: '', zipcode: '', address: '', addressDetail: '',
}

function formFrom(p: Prefill): typeof emptyForm {
  return {
    phone: p.phone ?? '', depositorName: p.depositorName ?? '', nickname: p.nickname ?? '', unitAddress: p.unitAddress ?? '',
    receiveMethod: p.receiveMethod ?? '', pickupPointId: p.pickupPointId ? String(p.pickupPointId) : '', paymentMethod: p.paymentMethod ?? '',
    recipientName: p.recipientName ?? '', recipientPhone: p.recipientPhone ?? '', zipcode: p.zipcode ?? '', address: p.address ?? '',
    addressDetail: p.addressDetail ?? '',
  }
}

export default function PublicOrder() {
  const { token } = useParams()
  const base = `/api/public/g/${token}`
  const [view, setView] = useState<View | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [qty, setQty] = useState<Record<number, number>>({})
  const [form, setForm] = useState(emptyForm)
  const [warnings, setWarnings] = useState<Warnings | null>(null)
  const [showExisting, setShowExisting] = useState(false)
  /** "내 주문"의 [주문 수정]으로 고친 주문 (주문서가 "N번 주문 수정"이 됨) */
  const [editing, setEditing] = useState<MyOrder | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [placed, setPlaced] = useState<Placed | null>(null)
  const [busy, setBusy] = useState(false)
  const [identify, setIdentify] = useState({ open: false, phone: '', error: '' })
  const [cancel, setCancel] = useState(emptyCancel)

  const applyView = useCallback((v: View) => {
    setView(v)
    if (v.prefill) setForm(formFrom(v.prefill))
  }, [])

  useEffect(() => {
    api.get<View>(base).then(applyView).catch((e) => setLoadError(errorMessage(e)))
  }, [base, applyView])

  // 경고에는 "무엇이 어떻게 바뀌는지"가 들어 있어서, 입력을 고치면 다시 주문하기를 눌러 새로 받는다
  useEffect(() => setWarnings(null), [qty, form])

  const receive: ReceiveMethod | '' = view
    ? view.pickupEnabled && view.deliveryEnabled ? form.receiveMethod : view.pickupEnabled ? 'PICKUP' : 'DELIVERY'
    : ''
  const payment: PaymentMethod | '' = !view ? '' : receive === 'DELIVERY' ? 'PREPAID'
    : view.pickupPrepayEnabled && view.pickupOnsiteEnabled ? form.paymentMethod : view.pickupPrepayEnabled ? 'PREPAID' : 'ONSITE'

  const itemsAmount = useMemo(() => view?.products.reduce((s, p) => s + p.unitPrice * (qty[p.id] ?? 0), 0) ?? 0, [view, qty])
  const shipping = view && receive === 'DELIVERY' && view.shippingFeeType === 'FIXED' ? view.shippingFee : 0

  async function submit(e: FormEvent | null, acknowledge = false) {
    e?.preventDefault()
    if (!view) return
    setBusy(true)
    setError(null)
    try {
      const res = await api.post<Placed>(`${base}/orders`, {
        items: view.products.map((p) => ({ productId: p.id, quantity: qty[p.id] ?? 0 })).filter((i) => i.quantity > 0),
        phone: form.phone, depositorName: form.depositorName, nickname: form.nickname || null, unitAddress: form.unitAddress || null,
        receiveMethod: receive || null, pickupPointId: form.pickupPointId ? Number(form.pickupPointId) : null, paymentMethod: payment || null,
        recipientName: form.recipientName || null, recipientPhone: form.recipientPhone || null, zipcode: form.zipcode || null,
        address: form.address || null, addressDetail: form.addressDetail || null,
        // [주문 수정]은 고객이 주문을 직접 고른 것이라 경고 없이 그 주문을 수정 (서버가 수정할 수 없으면 다시 경고)
        acknowledgeWarnings: acknowledge || editing !== null,
        replaceOrderNo: acknowledge ? warnings?.replaceOrderNo ?? null : editing?.orderNo ?? null,
      })
      setPlaced(res)
      setEditing(null)
      setWarnings(null)
      setQty({})
      setView((v) => (v ? { ...v, myOrders: res.myOrders } : v))
      api.get<View>(base).then(applyView).catch(() => undefined)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && err.body?.warnings) {
        setWarnings({ list: err.body.warnings, replaceOrderNo: err.body.replaceOrderNo ?? null, replaceOrder: err.body.replaceOrder ?? null })
        setShowExisting(false)
      } else {
        setError(errorMessage(err))
      }
    } finally {
      setBusy(false)
    }
  }

  async function doIdentify(e: FormEvent) {
    e.preventDefault()
    try {
      applyView(await api.post<View>(`${base}/identify`, { phone: identify.phone }))
      setIdentify({ open: false, phone: '', error: '' })
    } catch (err) {
      setIdentify({ ...identify, error: errorMessage(err) })
    }
  }

  /**
   * 바로 취소(/cancel) 또는 환불 신청(/refund-request). 인터뷰 결과 4번: 휴대폰 번호 + 입금자명으로 확인.
   * 주문이 여러 건이면 서버가 목록을 주고 고르게 한다. 확정된 주문을 바로 취소하려 하면 서버가 환불 신청으로 돌린다.
   */
  async function doCancel(e: FormEvent | null, orderNo?: number) {
    e?.preventDefault()
    if (busy) return // 두 번 눌러도 한 번만 보낸다
    setBusy(true)
    const refund = cancel.mode === 'refund'
    try {
      const target = { orderNo: orderNo ?? (cancel.orderNo ? Number(cancel.orderNo) : null), phone: cancel.phone, depositorName: cancel.depositorName }
      const v = refund
        ? await api.post<View>(`${base}/refund-request`, {
          ...target, bankName: cancel.bankName, accountNumber: cancel.accountNumber,
        })
        : await api.post<View>(`${base}/cancel`, target)
      applyView(v)
      setCancel(emptyCancel)
      setPlaced(null)
      alert(refund ? T.refundRequestedAlert : T.cancelledAlert)
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && Array.isArray(err.body?.orders)) {
        setCancel({ ...cancel, error: '', choices: err.body.orders })
      } else if (err instanceof ApiError && err.status === 409 && err.body?.refundRequired) {
        setCancel({ ...cancel, mode: 'refund', notice: errorMessage(err), error: '', choices: [] })
      } else {
        setCancel({ ...cancel, error: errorMessage(err), choices: [] })
      }
    } finally {
      setBusy(false)
    }
  }

  function startEdit(o: MyOrder) {
    if (!o.editValues) return
    setEditing(o)
    setPlaced(null)
    setError(null)
    setQty(Object.fromEntries(o.items.map((i) => [i.productId, i.quantity])))
    setForm(formFrom(o.editValues))
    setTimeout(() => document.getElementById('order-form')?.scrollIntoView({ behavior: 'smooth' }), 50)
  }

  function stopEdit() {
    setEditing(null)
    setQty({})
    setForm(view?.prefill ? formFrom(view.prefill) : emptyForm)
  }

  function openCancel(orderNo?: number, mode: CancelMode = 'cancel') {
    setCancel({
      ...emptyCancel, mode, open: true, orderNo: orderNo ? String(orderNo) : '', phone: form.phone, depositorName: form.depositorName,
    })
    setTimeout(() => document.getElementById('cancel-form')?.scrollIntoView({ behavior: 'smooth' }), 50)
  }

  /** 확정된 주문(판매자가 대조해서 입금 확정): 수정·바로 취소 대신 환불 신청 */
  const confirmedOrder = (o: MyOrder) => o.refundRequestable && o.status === 'PAID'

  /** 주문 변경 안내: 주문 확정 전에는 [주문 수정], 확정 후에는 환불 신청 후 다시 주문 (2026-10-19 사용자 결정) */
  const changeNote = (
    <p className="small mt" style={{ marginBottom: 0 }}><Rich text={T.changeNote} strongStyle={{ fontWeight: 800 }} /></p>
  )

  /**
   * 같은 번호로 다시 주문하면 바뀔 주문(이 기기에서 보이는 것 중 가장 최근, 서버와 같은 기준)의 상품별 수량.
   * 바꾸기는 늘어난 만큼만 재고를 쓰므로, 품절인 상품도 이미 주문한 수량까지는 고를 수 있다
   */
  const replaceTarget = view?.myOrders.find((o) => o.replaceable) ?? null
  const mine = useMemo(() => {
    const target = editing ?? view?.myOrders.find((o) => o.replaceable)
    return Object.fromEntries((target?.items ?? []).map((i) => [i.productId, i.quantity])) as Record<number, number>
  }, [view, editing])

  /**
   * 수량 상한: 1인 최대 수량, 그리고 판매자가 "다 팔리면 품절"을 켠 상품만 남은 수량 (기본은 재고로 막지 않음, 2026-10-16).
   * 1인 최대·남은 수량은 서버가 다시 확인 (기존 주문을 바꾸는 경우 그 주문 수량을 빼고 셈)
   */
  function maxQty(p: PublicProduct): number {
    const stock = p.blockWhenSoldOut ? (p.remaining ?? 0) + (mine[p.id] ?? 0) : null
    const limits = [p.perOrderLimit, stock].filter((n): n is number => n !== null && n !== undefined)
    return limits.length ? Math.min(...limits) : 999
  }

  function setQtyFor(p: PublicProduct, value: number) {
    setQty({ ...qty, [p.id]: Math.max(0, Math.min(maxQty(p), Number.isFinite(value) ? value : 0)) })
  }

  async function searchAddress() {
    try {
      await loadPostcode()
      new window.daum.Postcode({
        oncomplete: (data: any) => setForm((f) => ({ ...f, zipcode: data.zonecode, address: data.roadAddress || data.jibunAddress })),
      }).open()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  if (loadError) return <div className="container narrow"><h2>📌 입금콕</h2><ErrorBox message={loadError} /></div>
  if (!view) return <div className="page-loading">불러오는 중…</div>
  const set = (k: keyof typeof emptyForm, v: string) => setForm((f) => ({ ...f, [k]: v }))

  return (
    <div className="container narrow">
      <div className="card">
        <div className="muted small">{view.sellerName}</div>
        <h1>{view.title}</h1>
        {view.description && <p className="pre">{view.description}</p>}
        <div className="small">
          {view.paused ? <span style={{ color: 'var(--warn)', fontWeight: 700 }}>⏸ 주문 잠시 멈춤</span>
            : view.acceptingOrders
              ? <span style={{ color: 'var(--ok)', fontWeight: 700 }}>주문 받는 중{view.closeAt ? ` · ${dateTime(view.closeAt)} 마감` : ''}</span>
              : <span style={{ color: 'var(--danger)', fontWeight: 700 }}>{view.closedReason}</span>}
        </div>
        {view.kakaoInquiryUrl && <a className="btn small mt" href={view.kakaoInquiryUrl} target="_blank" rel="noreferrer">💬 카톡 문의</a>}
      </div>

      {/* 판매자가 주문을 잠시 멈춤 (입금 대조 중 등). 문구·링크는 판매자가 정함 */}
      {view.paused && (
        <div className="card" style={{ borderColor: 'var(--warn)', background: 'var(--warn-weak)' }}>
          <div className="pre"><b>⏸ {view.pauseMessage}</b></div>
          {(view.pauseLinkUrl || view.kakaoInquiryUrl) && (
            <div className="row mt">
              {view.pauseLinkUrl && <a className="btn small" href={view.pauseLinkUrl} target="_blank" rel="noreferrer">🔗 링크 열기</a>}
              {view.kakaoInquiryUrl && <a className="btn small" href={view.kakaoInquiryUrl} target="_blank" rel="noreferrer">💬 카톡 문의</a>}
            </div>
          )}
          <div className="small muted mt">{T.pausedNote}</div>
        </div>
      )}

      {placed && (
        <div className="card" style={{ borderColor: 'var(--ok)' }}>
          <h2>{placed.changed ? '주문을 수정했어요 ✅' : '주문 완료 🎉'}</h2>
          {placed.changed
            ? <OrderSummary o={placed.finalOrder.order} f={placed.finalOrder}
                title={<><b>최종 주문 · {placed.orderNo}번</b> <span className="muted">(이 내용으로 수정되었습니다)</span></>} />
            : <div>주문번호 <b>{placed.orderNo}</b> · 총 <b>{won(placed.expectedAmount)}</b></div>}
          {placed.account ? (
            <div className="mt">
              <div><Rich text={T.sendTo(won(placed.expectedAmount))} /></div>
              <div className="row mt"><b>{placed.account.bankName} {placed.account.accountNumber}</b> ({placed.account.accountHolder}) <CopyButton text={placed.account.accountNumber} label="계좌 복사" /></div>
              <div className="warn-box"><Rich text={T.depositorReminder(placed.depositorName)} /></div>
              <div className="small muted mt">{T.confirmAfterDeposit}</div>
            </div>
          ) : <div className="mt">{T.payOnsite}</div>}
          <p className="small muted">{T.checkLater}</p>
        </div>
      )}

      {view.myOrders.length > 0 && (
        <div className="card">
          <h3>내 주문</h3>
          {view.myOrders.map((o) => (
            <div key={o.orderNo} style={{ borderTop: '1px solid var(--line)', padding: '10px 0' }}>
              <div className="row between">
                <b>{o.orderNo}번 · {won(o.expectedAmount)}</b>
                <StatusBadge status={o.status} label={o.statusLabel + (o.status === 'PAID' ? ' ✅' : '')} />
              </div>
              <div className="small">{o.items.map((i) => `${i.productName} ${i.quantity}개`).join(', ')}</div>
              <div className="small muted">{o.receiveMethod === 'DELIVERY' ? '배송' : `픽업${o.pickupPointName ? ' · ' + o.pickupPointName : ''}`} · {o.paymentMethod === 'PREPAID' ? `선입금 (입금자명 ${o.depositorName})` : '현장결제'} · {dateTime(o.orderedAt)}</div>
              {o.changedAt && <div className="small" style={{ color: 'var(--warn)' }}>{T.changedBySeller(dateTime(o.changedAt))}</div>}
              {o.customerChangedAt && <div className="small muted">주문 수정됨 ({dateTime(o.customerChangedAt)})</div>}
              {o.refundRequired && <div className="small" style={{ color: 'var(--danger)' }}>{T.refundPlanned}</div>}
              {o.refundedAt && <div className="small muted">취소됨 · 환불 완료 ({dateTime(o.refundedAt)})</div>}
              {o.refundState === 'REQUESTED' && <div className="small" style={{ color: 'var(--warn)' }}>{T.refundRequested}</div>}
              {o.refundState === 'REJECTED' && <div className="small" style={{ color: 'var(--danger)' }}>{T.refundRejected}</div>}
              {o.awaitingConfirm && view.account && (
                <div className="small mt">입금 계좌: {view.account.bankName} {view.account.accountNumber} ({view.account.accountHolder}) <CopyButton text={view.account.accountNumber} label="복사" /></div>
              )}
              <div className="row mt">
                {!view.paused && o.replaceable && o.editValues && <button className="btn small" onClick={() => startEdit(o)}>주문 수정</button>}
                {!view.paused && o.cancellable && <button className="btn small danger" onClick={() => openCancel(o.orderNo)}>주문 취소</button>}
                {!view.paused && confirmedOrder(o) && <button className="btn small danger" onClick={() => openCancel(o.orderNo, 'refund')}>환불 신청</button>}
              </div>
              {o.replaceable && o.paymentMethod === 'PREPAID' && <div className="small muted mt">{T.editableUntilConfirm}</div>}
              {confirmedOrder(o) && <div className="small muted mt">{T.confirmedCannotEdit}</div>}
            </div>
          ))}
        </div>
      )}

      {view.myOrders.length === 0 && (
        <div className="card">
          {!identify.open ? (
            <div className="row between"><span className="small">이미 주문하셨나요?</span><button className="btn small" onClick={() => setIdentify({ ...identify, open: true })}>내 주문 찾기</button></div>
          ) : (
            <form onSubmit={doIdentify}>
              <h3>내 주문 찾기</h3>
              <div className="row">
                <input type="tel" placeholder="휴대폰 번호 (010-0000-0000)" value={identify.phone} onChange={(e) => setIdentify({ ...identify, phone: e.target.value })} required style={{ flex: 1, minWidth: 160 }} />
                <button className="btn primary">찾기</button>
              </div>
              <p className="small muted">{T.identifyHint}</p>
              <ErrorBox message={identify.error} />
              {identify.error && (
                <p className="small muted">
                  {T.identifyNotFound}
                  {view.kakaoInquiryUrl && <> <a href={view.kakaoInquiryUrl} target="_blank" rel="noreferrer">💬 카톡 문의</a></>}
                </p>
              )}
            </form>
          )}
        </div>
      )}

      {view.acceptingOrders && !view.paused && (
        <form className="card" id="order-form" onSubmit={(e) => submit(e)}>
          <h3>{editing ? `${editing.orderNo}번 주문 수정` : replaceTarget ? '주문서' : view.myOrders.length > 0 ? '추가 주문하기' : '주문서'}</h3>
          {editing && editing.paymentMethod === 'PREPAID' && <div className="small mb"><b>주문 확정 전이에요.</b></div>}
          {/* 수정은 휴대폰 번호 + 입금자명이 기존 주문과 같아야 한다 (2026-10-19). 그래서 둘은 고칠 수 없다 */}
          {editing && <div className="small muted mb">{T.editLockedFields}</div>}
          {!editing && replaceTarget && (
            <div className="small mb"><Rich text={T.useEditButton(replaceTarget.paymentMethod === 'PREPAID')} /></div>
          )}
          {(editing ? editing.editValues?.limited : view.prefill?.limited) && <div className="small muted mb">{T.otherDevicePrefill}</div>}
          {view.products.map((p) => (
            <div key={p.id} className="product-row">
              <div>
                <div><b>{p.name}</b></div>
                <div className="small">{won(p.unitPrice)}{p.remaining !== null && <span className="muted"> · 남은 수량 {p.remaining}</span>}{p.perOrderLimit && <span className="muted"> · 1인 최대 {p.perOrderLimit}</span>}</div>
              </div>
              {p.soldOut && !mine[p.id] ? <span className="badge badge-CANCELLED">품절</span> : (
                <div className="qty">
                  <button type="button" onClick={() => setQtyFor(p, (qty[p.id] ?? 0) - 1)}>−</button>
                  <input type="number" min={0} max={maxQty(p)} value={qty[p.id] ?? 0} onChange={(e) => setQtyFor(p, Number(e.target.value))} />
                  <button type="button" disabled={(qty[p.id] ?? 0) >= maxQty(p)} onClick={() => setQtyFor(p, (qty[p.id] ?? 0) + 1)}>+</button>
                </div>
              )}
            </div>
          ))}

          {view.pickupEnabled && view.deliveryEnabled && (
            <div className="mt">
              <div className="small"><b>수령 방법 *</b></div>
              <div className="segmented">
                {(['PICKUP', 'DELIVERY'] as const).map((m) => (
                  <label key={m} className={form.receiveMethod === m ? 'on' : ''}>
                    <input type="radio" name="receive" checked={form.receiveMethod === m} onChange={() => set('receiveMethod', m)} /> {m === 'PICKUP' ? '픽업' : '배송'}
                  </label>
                ))}
              </div>
            </div>
          )}

          {receive === 'PICKUP' && view.pickupPoints.length > 1 && (
            <label className="field mt"><span>픽업 지점 *</span>
              <select value={form.pickupPointId} onChange={(e) => set('pickupPointId', e.target.value)} required>
                <option value="">선택하세요</option>
                {view.pickupPoints.map((p) => <option key={p.id} value={p.id}>{p.name}{p.info ? ` - ${p.info}` : ''}</option>)}
              </select>
            </label>
          )}
          {receive === 'PICKUP' && view.pickupPoints.length === 1 && (
            <div className="small muted mt">픽업: {view.pickupPoints[0].name}{view.pickupPoints[0].info ? ` - ${view.pickupPoints[0].info}` : ''}</div>
          )}

          {receive === 'PICKUP' && view.pickupPrepayEnabled && view.pickupOnsiteEnabled && (
            <div className="mt">
              <div className="small"><b>결제 방식 *</b></div>
              <div className="segmented">
                {(['PREPAID', 'ONSITE'] as const).map((m) => (
                  <label key={m} className={form.paymentMethod === m ? 'on' : ''}>
                    <input type="radio" name="payment" checked={form.paymentMethod === m} onChange={() => set('paymentMethod', m)} /> {m === 'PREPAID' ? '계좌이체' : '현장결제'}
                  </label>
                ))}
              </div>
            </div>
          )}

          {receive === 'DELIVERY' && (
            <div className="mt">
              <label className="field"><span>받는 분 *</span><input type="text" value={form.recipientName} onChange={(e) => set('recipientName', e.target.value)} required /></label>
              <label className="field"><span>받는 분 연락처 *</span><input type="tel" value={form.recipientPhone} onChange={(e) => set('recipientPhone', e.target.value)} required placeholder="010-0000-0000" /></label>
              <div className="field"><span style={{ fontWeight: 600, display: 'block', marginBottom: 4 }}>주소 *</span>
                <div className="row">
                  <input type="text" value={form.zipcode} readOnly placeholder="우편번호" style={{ width: 110 }} />
                  <button type="button" className="btn small" onClick={searchAddress}>주소 검색</button>
                </div>
                <input type="text" className="mt" value={form.address} onChange={(e) => set('address', e.target.value)} placeholder="주소" required />
                <input type="text" className="mt" value={form.addressDetail} onChange={(e) => set('addressDetail', e.target.value)} placeholder="상세주소" />
              </div>
            </div>
          )}

          <div className="mt">
            <label className="field"><span>휴대폰 번호 *</span><input type="tel" value={form.phone} onChange={(e) => set('phone', e.target.value)} required placeholder="010-0000-0000"
              readOnly={!!(editing && editing.editValues?.phone)} /></label>
            <label className="field"><span>입금자명 *</span><input type="text" value={form.depositorName} onChange={(e) => set('depositorName', e.target.value)} required placeholder="통장에 찍힐 이름"
              readOnly={!!(editing && editing.editValues?.depositorName)} />
              <span className="muted small" style={{ fontWeight: 400 }}>{payment === 'ONSITE' ? T.depositorHintOnsite : T.depositorHintTransfer}</span>
            </label>
            <label className="field"><span>{view.nicknameRequired ? '닉네임 (밴드·카톡) *' : '닉네임 (밴드·카톡, 선택)'}</span><input type="text" value={form.nickname} onChange={(e) => set('nickname', e.target.value)} required={view.nicknameRequired} /></label>
            {view.unitAddressFieldEnabled && <label className="field"><span>동·호수 *</span><input type="text" value={form.unitAddress} onChange={(e) => set('unitAddress', e.target.value)} required placeholder="예: 101동 1203호" /></label>}
          </div>

          <div className="card" style={{ background: '#f9fafb' }}>
            <div className="row between"><span>상품</span><span>{won(itemsAmount)}</span></div>
            {shipping > 0 && <div className="row between"><span>배송비</span><span>{won(shipping)}</span></div>}
            <div className="row between"><b>총 금액</b><b>{won(itemsAmount + shipping)}</b></div>
          </div>
          <ErrorBox message={error} />
          {warnings && (
            <div className="warn-box">
              {warnings.list.map((w) => <div key={w}>⚠ {w}</div>)}
              {warnings.replaceOrder?.paymentMethod === 'PREPAID' && <div className="small mt">{T.refundThenReorder}</div>}
              {warnings.replaceOrder && showExisting && (
                <OrderSummary o={warnings.replaceOrder}
                  title={<><b>기존 주문</b> <span className="muted">({warnings.replaceOrder.statusLabel})</span></>} />
              )}
              <div className="row mt">
                {warnings.replaceOrder && !showExisting && (
                  <button type="button" className="btn small" onClick={() => setShowExisting(true)}>기존 주문 확인</button>
                )}
                <button type="button" className="btn small primary" disabled={busy} onClick={() => submit(null, true)}>
                  {warnings.replaceOrder ? '수정하기' : '확인했어요, 주문하기'}
                </button>
                <button type="button" className="btn small" onClick={() => setWarnings(null)}>다시 입력</button>
              </div>
            </div>
          )}
          {!warnings && !editing && <button className="btn primary block" disabled={busy || itemsAmount === 0}>주문하기</button>}
          {!warnings && editing && (
            <div className="row">
              <button className="btn primary grow" disabled={busy || itemsAmount === 0}>수정하기</button>
              <button type="button" className="btn" onClick={stopEdit} disabled={busy}>수정 취소</button>
            </div>
          )}
          {/* 입금한 고객은 수정 대신 환불 후 재주문 (금액이 바뀌면 입금액과 어긋나므로, 2026-10-19 사용자 요청) */}
          {editing && editing.paymentMethod === 'PREPAID' && editing.refundRequestable && (
            <div className="row mt">
              <span className="small muted grow">{T.refundThenReorder}</span>
              <button type="button" className="btn small danger" disabled={busy}
                onClick={() => { openCancel(editing.orderNo, 'refund'); stopEdit() }}>환불 신청</button>
            </div>
          )}
          <p className="muted small mt"><Rich text={T.consent} /></p>
        </form>
      )}

      <div className="card" id="cancel-form">
        {view.paused ? (
          <div className="small muted">{T.pausedNoCancel}</div>
        ) : !cancel.open ? (
          <>
            <div className="row between">
              <b className="small">주문 취소·환불 신청</b>
              <div className="row">
                {view.acceptingOrders && <button className="btn small danger" onClick={() => openCancel()}>주문 취소</button>}
                {view.account && <button className="btn small" onClick={() => openCancel(undefined, 'refund')}>환불 신청</button>}
              </div>
            </div>
            <p className="small muted mt" style={{ marginBottom: 0 }}>
              {view.acceptingOrders ? T.cancelBeforeConfirm : T.cancelClosed}
              {view.account && T.refundIfPaid}
            </p>
            {view.acceptingOrders && changeNote}
          </>
        ) : (
          <form onSubmit={(e) => doCancel(e)}>
            {cancel.mode === 'cancel' ? (
              <>
                <h3>주문 취소</h3>
                <p className="small"><Rich text={T.cancelFormNote} /></p>
              </>
            ) : (
              <>
                <h3>환불 신청</h3>
                <p className="small">{T.refundFormNote}</p>
              </>
            )}
            {cancel.notice && <div className="warn-box">{cancel.notice}</div>}
            <label className="field"><span>휴대폰 번호 (주문할 때 적은 번호)</span><input type="tel" value={cancel.phone} onChange={(e) => setCancel({ ...cancel, phone: e.target.value, choices: [] })} required /></label>
            <label className="field"><span>입금자명 (주문할 때 적은 이름)</span><input type="text" value={cancel.depositorName} onChange={(e) => setCancel({ ...cancel, depositorName: e.target.value, choices: [] })} required /></label>
            {cancel.mode === 'refund' && (
              <>
                <label className="field"><span>환불받을 은행</span><input type="text" value={cancel.bankName} onChange={(e) => setCancel({ ...cancel, bankName: e.target.value })} required maxLength={30} placeholder="예: 국민은행" /></label>
                <label className="field"><span>계좌번호</span><input type="text" inputMode="numeric" value={cancel.accountNumber} onChange={(e) => setCancel({ ...cancel, accountNumber: e.target.value })} required maxLength={30} placeholder="숫자만 (- 있어도 됨)" /></label>
              </>
            )}
            {cancel.orderNo && cancel.choices.length === 0 && <div className="small muted mb">{cancel.orderNo}번 주문</div>}
            <ErrorBox message={cancel.error} />
            {cancel.choices.length > 0 ? (
              <div>
                <div className="small mb"><b>{T.chooseOrder(cancel.mode === 'refund')}</b></div>
                {cancel.choices.map((o) => (
                  <div key={o.orderNo} className="row between" style={{ borderTop: '1px solid var(--line)', padding: '8px 0' }}>
                    <span className="small">{o.orderNo}번 · {o.items.map((i) => `${i.productName} ${i.quantity}개`).join(', ')} · {won(o.expectedAmount)} · {o.statusLabel}</span>
                    <button type="button" className="btn small danger" disabled={busy} onClick={() => doCancel(null, o.orderNo)}>{cancel.mode === 'refund' ? '이 주문 환불 신청' : '이 주문 취소'}</button>
                  </div>
                ))}
              </div>
            ) : cancel.mode === 'cancel' ? (
              <div className="row">
                <button className="btn danger" disabled={busy}>바로 취소</button>
                <button type="button" className="btn" disabled={busy}
                  onClick={() => setCancel({ ...cancel, mode: 'refund', notice: '', error: '' })}>
                  환불 신청
                </button>
                <button type="button" className="btn" onClick={() => setCancel(emptyCancel)}>닫기</button>
              </div>
            ) : (
              <div className="row"><button className="btn danger" disabled={busy}>환불 신청</button><button type="button" className="btn" onClick={() => setCancel(emptyCancel)}>닫기</button></div>
            )}
          </form>
        )}
      </div>
      <footer className="footer">📌 입금콕 · <a href="/privacy">개인정보처리방침</a></footer>
    </div>
  )
}
