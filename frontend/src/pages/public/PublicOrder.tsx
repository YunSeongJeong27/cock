import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useParams } from 'react-router-dom'
import { ApiError, api, dateTime, errorMessage, won } from '../../api'
import { CopyButton, ErrorBox, StatusBadge } from '../../components/Ui'
import type { PaymentMethod, ReceiveMethod, ShippingFeeType } from '../../types'

interface PublicProduct { id: number; name: string; unitPrice: number; remaining: number | null; perOrderLimit: number | null; soldOut: boolean }
interface Account { bankName: string; accountNumber: string; accountHolder: string }
interface MyOrder {
  orderNo: number
  items: { productName: string; quantity: number; unitPrice: number; amount: number }[]
  itemsAmount: number
  shippingFee: number
  expectedAmount: number
  receiveMethod: ReceiveMethod
  pickupPointName: string | null
  paymentMethod: PaymentMethod
  depositorName: string
  status: string
  statusLabel: string
  canReportDeposit: boolean
  cancellable: boolean
  refundRequired: boolean
  orderedAt: string
}
interface Prefill {
  phone: string; depositorName: string; nickname: string | null; unitAddress: string | null; receiveMethod: ReceiveMethod
  pickupPointId: number | null; paymentMethod: PaymentMethod; recipientName: string | null; recipientPhone: string | null
  zipcode: string | null; address: string | null; addressDetail: string | null
  /** true: 입금자명 + 뒷4자리로만 확인한 기기라 휴대폰·동호수·배송지는 비어 있음 */
  limited: boolean
}
interface View {
  title: string; description: string | null; sellerName: string; acceptingOrders: boolean; closedReason: string | null; closeAt: string | null
  products: PublicProduct[]; pickupPoints: { id: number; name: string; info: string | null }[]
  pickupEnabled: boolean; pickupPrepayEnabled: boolean; pickupOnsiteEnabled: boolean; deliveryEnabled: boolean
  shippingFeeType: ShippingFeeType; shippingFee: number; nicknameFieldEnabled: boolean; unitAddressFieldEnabled: boolean
  kakaoInquiryUrl: string | null; account: Account | null; myOrders: MyOrder[]; prefill: Prefill | null
}
interface Placed { orderNo: number; expectedAmount: number; paymentMethod: PaymentMethod; depositorName: string; account: Account | null; myOrders: MyOrder[] }

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
    s.onerror = () => reject(new Error('주소 검색을 불러오지 못했습니다. 주소를 직접 입력해 주세요.'))
    document.head.appendChild(s)
  })
}

const emptyForm = {
  phone: '', depositorName: '', nickname: '', unitAddress: '', receiveMethod: '' as ReceiveMethod | '', pickupPointId: '',
  paymentMethod: '' as PaymentMethod | '', recipientName: '', recipientPhone: '', zipcode: '', address: '', addressDetail: '',
}

export default function PublicOrder() {
  const { token } = useParams()
  const base = `/api/public/g/${token}`
  const [view, setView] = useState<View | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [qty, setQty] = useState<Record<number, number>>({})
  const [form, setForm] = useState(emptyForm)
  const [warnings, setWarnings] = useState<string[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [placed, setPlaced] = useState<Placed | null>(null)
  const [busy, setBusy] = useState(false)
  const [identify, setIdentify] = useState({ open: false, depositorName: '', phone: '', error: '' })
  const [cancel, setCancel] = useState<{ orderNo: string; phone: string; depositorName: string; open: boolean; error: string; choices: MyOrder[] }>(
    { orderNo: '', phone: '', depositorName: '', open: false, error: '', choices: [] })

  const applyView = useCallback((v: View) => {
    setView(v)
    if (v.prefill) {
      const p = v.prefill
      setForm((f) => ({
        ...f, phone: p.phone ?? '', depositorName: p.depositorName ?? '', nickname: p.nickname ?? '', unitAddress: p.unitAddress ?? '',
        receiveMethod: p.receiveMethod ?? '', pickupPointId: p.pickupPointId ? String(p.pickupPointId) : '', paymentMethod: p.paymentMethod ?? '',
        recipientName: p.recipientName ?? '', recipientPhone: p.recipientPhone ?? '', zipcode: p.zipcode ?? '', address: p.address ?? '',
        addressDetail: p.addressDetail ?? '',
      }))
    }
  }, [])

  useEffect(() => {
    api.get<View>(base).then(applyView).catch((e) => setLoadError(errorMessage(e)))
  }, [base, applyView])

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
        address: form.address || null, addressDetail: form.addressDetail || null, acknowledgeWarnings: acknowledge,
      })
      setPlaced(res)
      setWarnings(null)
      setQty({})
      setView((v) => (v ? { ...v, myOrders: res.myOrders } : v))
      api.get<View>(base).then(applyView).catch(() => undefined)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && err.body?.warnings) {
        setWarnings(err.body.warnings)
      } else {
        setError(errorMessage(err))
      }
    } finally {
      setBusy(false)
    }
  }

  async function reportDeposit(orderNo: number) {
    if (!confirm('입금하셨나요? 판매자가 입금내역을 확인한 뒤 "입금 확인됨"으로 바뀝니다.')) return
    try {
      setView(await api.post<View>(`${base}/orders/${orderNo}/report-deposit`))
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function doIdentify(e: FormEvent) {
    e.preventDefault()
    try {
      applyView(await api.post<View>(`${base}/identify`, { depositorName: identify.depositorName, phone: identify.phone }))
      setIdentify({ open: false, depositorName: '', phone: '', error: '' })
    } catch (err) {
      setIdentify({ ...identify, error: errorMessage(err) })
    }
  }

  async function doCancel(e: FormEvent | null, orderNo?: number) {
    e?.preventDefault()
    try {
      // 인터뷰 결과 4번: 휴대폰 번호 + 입금자명으로 취소. 주문이 여러 건이면 서버가 목록을 주고 고르게 한다.
      const v = await api.post<View>(`${base}/cancel`, {
        orderNo: orderNo ?? (cancel.orderNo ? Number(cancel.orderNo) : null), phone: cancel.phone, depositorName: cancel.depositorName,
      })
      applyView(v)
      setCancel({ orderNo: '', phone: '', depositorName: '', open: false, error: '', choices: [] })
      setPlaced(null)
      alert('주문을 취소했습니다.')
    } catch (err) {
      if (err instanceof ApiError && err.status === 409 && Array.isArray(err.body?.orders)) {
        setCancel({ ...cancel, error: '', choices: err.body.orders })
      } else {
        setCancel({ ...cancel, error: errorMessage(err), choices: [] })
      }
    }
  }

  function openCancel(orderNo?: number) {
    setCancel({ orderNo: orderNo ? String(orderNo) : '', phone: form.phone, depositorName: form.depositorName, open: true, error: '', choices: [] })
    setTimeout(() => document.getElementById('cancel-form')?.scrollIntoView({ behavior: 'smooth' }), 50)
  }

  /** 수량 상한: 남은 재고와 1인 최대 수량 중 작은 값 (1인 최대는 이미 주문한 수량을 서버가 합쳐서 다시 확인) */
  function maxQty(p: PublicProduct): number {
    const limits = [p.remaining, p.perOrderLimit].filter((n): n is number => n !== null && n !== undefined)
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
          {view.acceptingOrders
            ? <span style={{ color: 'var(--ok)', fontWeight: 700 }}>주문 받는 중{view.closeAt ? ` · ${dateTime(view.closeAt)} 마감` : ''}</span>
            : <span style={{ color: 'var(--danger)', fontWeight: 700 }}>{view.closedReason}</span>}
        </div>
        {view.kakaoInquiryUrl && <a className="btn small mt" href={view.kakaoInquiryUrl} target="_blank" rel="noreferrer">💬 카톡 문의</a>}
      </div>

      {placed && (
        <div className="card" style={{ borderColor: 'var(--ok)' }}>
          <h2>주문 완료 🎉</h2>
          <div>주문번호 <b>{placed.orderNo}</b> · 총 <b>{won(placed.expectedAmount)}</b></div>
          {placed.account ? (
            <div className="mt">
              <div>아래 계좌로 <b>{won(placed.expectedAmount)}</b>을 보내주세요.</div>
              <div className="row mt"><b>{placed.account.bankName} {placed.account.accountNumber}</b> ({placed.account.accountHolder}) <CopyButton text={placed.account.accountNumber} label="계좌 복사" /></div>
              <div className="warn-box">받는 분 통장 표시(입금자명)를 꼭 <b>{placed.depositorName}</b>(으)로 해주세요. 입금 확인이 빨라집니다.</div>
              <div className="row mt">
                <span className="small grow">입금하셨나요? 입금 후 눌러주세요. (판매자가 입금내역을 확인하면 "입금 확인됨"으로 바뀝니다)</span>
                <button className="btn small primary" onClick={() => reportDeposit(placed.orderNo)}>입금했어요</button>
              </div>
            </div>
          ) : <div className="mt">현장에서 결제해 주세요.</div>}
          <p className="small muted">이 화면 위쪽 "내 주문"에서 언제든 상태를 확인할 수 있습니다. 이 링크를 다시 열면 됩니다.</p>
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
              {o.refundRequired && <div className="small" style={{ color: 'var(--danger)' }}>취소됨 · 판매자가 환불할 예정입니다.</div>}
              {o.canReportDeposit && view.account && (
                <div className="small mt">입금 계좌: {view.account.bankName} {view.account.accountNumber} ({view.account.accountHolder}) <CopyButton text={view.account.accountNumber} label="복사" /></div>
              )}
              <div className="row mt">
                {o.canReportDeposit && <button className="btn small primary" onClick={() => reportDeposit(o.orderNo)}>입금했어요</button>}
                {o.cancellable && <button className="btn small danger" onClick={() => openCancel(o.orderNo)}>주문 취소</button>}
              </div>
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
                <input type="text" placeholder="입금자명" value={identify.depositorName} onChange={(e) => setIdentify({ ...identify, depositorName: e.target.value })} required style={{ flex: 1 }} />
                <input type="tel" placeholder="휴대폰 뒷 4자리" value={identify.phone} onChange={(e) => setIdentify({ ...identify, phone: e.target.value })} required style={{ flex: 1 }} />
                <button className="btn primary">찾기</button>
              </div>
              <ErrorBox message={identify.error} />
            </form>
          )}
        </div>
      )}

      {view.acceptingOrders && (
        <form className="card" onSubmit={(e) => submit(e)}>
          <h3>{view.myOrders.length > 0 ? '추가 주문하기' : '주문서'}</h3>
          {view.prefill?.limited && <div className="small muted mb">개인정보 보호를 위해 휴대폰 번호·주소는 다시 입력해 주세요.</div>}
          {view.products.map((p) => (
            <div key={p.id} className="product-row">
              <div>
                <div><b>{p.name}</b></div>
                <div className="small">{won(p.unitPrice)}{p.remaining !== null && <span className="muted"> · 남은 수량 {p.remaining}</span>}{p.perOrderLimit && <span className="muted"> · 1인 최대 {p.perOrderLimit}</span>}</div>
              </div>
              {p.soldOut ? <span className="badge badge-CANCELLED">품절</span> : (
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
                    <input type="radio" name="payment" checked={form.paymentMethod === m} onChange={() => set('paymentMethod', m)} /> {m === 'PREPAID' ? '계좌이체 (선입금)' : '현장결제'}
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
            <label className="field"><span>휴대폰 번호 *</span><input type="tel" value={form.phone} onChange={(e) => set('phone', e.target.value)} required placeholder="010-0000-0000" /></label>
            <label className="field"><span>입금자명 *</span><input type="text" value={form.depositorName} onChange={(e) => set('depositorName', e.target.value)} required placeholder="통장에 찍힐 이름" />
              <span className="muted small" style={{ fontWeight: 400 }}>{payment === 'ONSITE' ? '주문 확인용 이름입니다.' : '계좌이체할 때 받는 분 통장 표시에 이 이름을 적어주세요.'}</span>
            </label>
            {view.nicknameFieldEnabled && <label className="field"><span>닉네임 (밴드·카톡) *</span><input type="text" value={form.nickname} onChange={(e) => set('nickname', e.target.value)} required /></label>}
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
              {warnings.map((w) => <div key={w}>⚠ {w}</div>)}
              <div className="row mt">
                <button type="button" className="btn small primary" disabled={busy} onClick={() => submit(null, true)}>확인했어요, 주문하기</button>
                <button type="button" className="btn small" onClick={() => setWarnings(null)}>다시 입력</button>
              </div>
            </div>
          )}
          {!warnings && <button className="btn primary block" disabled={busy || itemsAmount === 0}>주문하기</button>}
          <p className="muted small mt">주문하면 <a href="/privacy" target="_blank">개인정보처리방침</a>에 따라 주문 처리에 필요한 정보(연락처·입금자명·배송지)를 판매자에게 전달하는 데 동의하는 것으로 봅니다. 주문 후 이 링크에서 내 주문 상태를 볼 수 있도록 이 기기에 쿠키를 저장합니다.</p>
        </form>
      )}

      <div className="card" id="cancel-form">
        {!view.acceptingOrders ? (
          <div className="small muted">주문이 마감되어 여기서는 취소할 수 없습니다. 취소가 필요하면 판매자에게 문의해 주세요.</div>
        ) : !cancel.open ? (
          <div className="row between"><span className="small">주문을 취소하시려면</span><button className="btn small danger" onClick={() => openCancel()}>주문 취소</button></div>
        ) : (
          <form onSubmit={(e) => doCancel(e)}>
            <h3>주문 취소</h3>
            <p className="small muted">주문할 때 적은 휴대폰 번호와 입금자명을 입력하세요. 주문이 여러 건이면 취소할 주문을 고르게 됩니다. 입금 확인된 주문은 취소 후 판매자가 환불합니다.</p>
            <label className="field"><span>휴대폰 번호</span><input type="tel" value={cancel.phone} onChange={(e) => setCancel({ ...cancel, phone: e.target.value, choices: [] })} required /></label>
            <label className="field"><span>입금자명</span><input type="text" value={cancel.depositorName} onChange={(e) => setCancel({ ...cancel, depositorName: e.target.value, choices: [] })} required /></label>
            {cancel.orderNo && cancel.choices.length === 0 && <div className="small muted mb">{cancel.orderNo}번 주문을 취소합니다.</div>}
            <ErrorBox message={cancel.error} />
            {cancel.choices.length > 0 ? (
              <div>
                <div className="small mb"><b>취소할 주문을 골라주세요.</b></div>
                {cancel.choices.map((o) => (
                  <div key={o.orderNo} className="row between" style={{ borderTop: '1px solid var(--line)', padding: '8px 0' }}>
                    <span className="small">{o.orderNo}번 · {o.items.map((i) => `${i.productName} ${i.quantity}개`).join(', ')} · {won(o.expectedAmount)} · {o.statusLabel}</span>
                    <button type="button" className="btn small danger" onClick={() => doCancel(null, o.orderNo)}>이 주문 취소</button>
                  </div>
                ))}
              </div>
            ) : (
              <div className="row"><button className="btn danger">취소하기</button><button type="button" className="btn" onClick={() => setCancel({ ...cancel, open: false })}>닫기</button></div>
            )}
          </form>
        )}
      </div>
      <footer className="footer">📌 입금콕 · <a href="/privacy">개인정보처리방침</a></footer>
    </div>
  )
}
