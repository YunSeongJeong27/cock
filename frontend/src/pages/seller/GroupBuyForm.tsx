import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, errorMessage } from '../../api'
import { useAuth } from '../../auth'
import { ErrorBox } from '../../components/Ui'
import { groupBuyText as T } from '../../texts/groupBuy'
import type { GroupBuy, OrderChannel, ShippingFeeType } from '../../types'

interface ProductInput { id?: number; name: string; unitPrice: string; stockLimit: string; perOrderLimit: string; stockAlertThreshold: string; blockWhenSoldOut: boolean; sold?: number }
interface PointInput { id?: number; name: string; info: string }

interface FormState {
  title: string
  description: string
  closeAt: string
  bankName: string
  accountNumber: string
  accountHolder: string
  pickupEnabled: boolean
  pickupPrepayEnabled: boolean
  pickupOnsiteEnabled: boolean
  deliveryEnabled: boolean
  shippingFeeType: ShippingFeeType
  shippingFee: string
  /** 새 공구는 비어 있음 → 판매자가 꼭 고른다 */
  orderChannel: OrderChannel | ''
  unitAddressFieldEnabled: boolean
  kakaoInquiryUrl: string
  adminNames: string
  products: ProductInput[]
  pickupPoints: PointInput[]
}

const empty: FormState = {
  title: '', description: '', closeAt: '', bankName: '', accountNumber: '', accountHolder: '',
  pickupEnabled: true, pickupPrepayEnabled: false, pickupOnsiteEnabled: true, deliveryEnabled: false,
  shippingFeeType: 'NONE', shippingFee: '', orderChannel: '', unitAddressFieldEnabled: false,
  kakaoInquiryUrl: '', adminNames: '', products: [{ name: '', unitPrice: '', stockLimit: '', perOrderLimit: '', stockAlertThreshold: '', blockWhenSoldOut: false }], pickupPoints: [],
}

function toForm(g: GroupBuy): FormState {
  return {
    title: g.title, description: g.description ?? '', closeAt: g.closeAt ? g.closeAt.substring(0, 16) : '',
    bankName: g.bankName ?? '', accountNumber: g.accountNumber ?? '', accountHolder: g.accountHolder ?? '',
    pickupEnabled: g.pickupEnabled, pickupPrepayEnabled: g.pickupPrepayEnabled, pickupOnsiteEnabled: g.pickupOnsiteEnabled,
    deliveryEnabled: g.deliveryEnabled, shippingFeeType: g.shippingFeeType, shippingFee: g.shippingFee ? String(g.shippingFee) : '',
    orderChannel: g.orderChannel, unitAddressFieldEnabled: g.unitAddressFieldEnabled,
    kakaoInquiryUrl: g.kakaoInquiryUrl ?? '', adminNames: g.adminNames ?? '',
    products: g.products.map((p) => ({ id: p.id, name: p.name, unitPrice: String(p.unitPrice), stockLimit: p.stockLimit ? String(p.stockLimit) : '', perOrderLimit: p.perOrderLimit ? String(p.perOrderLimit) : '', stockAlertThreshold: p.stockAlertThreshold != null ? String(p.stockAlertThreshold) : '', blockWhenSoldOut: p.blockWhenSoldOut, sold: p.sold })),
    pickupPoints: g.pickupPoints.map((p) => ({ id: p.id, name: p.name, info: p.info ?? '' })),
  }
}

const num = (s: string) => (s.trim() === '' ? null : Number(s.replace(/,/g, '')))

export default function GroupBuyForm() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const { me } = useAuth()
  const [form, setForm] = useState<FormState>(empty)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const editing = Boolean(id)
  // 댓글 주문 기능이 닫힌 판매자는 "주문 받는 방법"을 고르지 않고 주문 링크만 쓴다 (관리자가 연다, 2026-10-15).
  // 이미 "링크 + 댓글"인 공구를 고칠 때는 선택을 보여 준다 (그대로 두거나 링크만으로 바꿀 수 있게)
  const showChannel = Boolean(me?.commentFeatureEnabled) || form.orderChannel === 'LINK_AND_COMMENT'

  useEffect(() => {
    if (id) {
      api.get<GroupBuy>(`/api/seller/group-buys/${id}`).then((g) => setForm(toForm(g))).catch((e) => setError(errorMessage(e)))
    } else if (me) {
      setForm((f) => ({ ...f, bankName: me.defaultBankName ?? '', accountNumber: me.defaultAccountNumber ?? '', accountHolder: me.defaultAccountHolder ?? '' }))
    }
  }, [id, me])

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((f) => ({ ...f, [key]: value }))
  const usesPrepay = form.deliveryEnabled || (form.pickupEnabled && form.pickupPrepayEnabled)

  async function submit(e: FormEvent, openAfter: boolean) {
    e.preventDefault()
    if (showChannel && !form.orderChannel) {
      setError(T.chooseChannelFirst)
      window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    setBusy(true)
    setError(null)
    const body = {
      ...form,
      orderChannel: showChannel ? form.orderChannel : 'LINK_ONLY',
      closeAt: form.closeAt ? form.closeAt + ':00' : null,
      shippingFee: num(form.shippingFee) ?? 0,
      products: form.products.filter((p) => p.name.trim()).map((p) => ({
        id: p.id ?? null, name: p.name, unitPrice: num(p.unitPrice) ?? 0, stockLimit: num(p.stockLimit), perOrderLimit: num(p.perOrderLimit),
        stockAlertThreshold: num(p.stockLimit) == null ? null : num(p.stockAlertThreshold),
        blockWhenSoldOut: num(p.stockLimit) != null && p.blockWhenSoldOut,
      })),
      pickupPoints: form.pickupPoints.filter((p) => p.name.trim()).map((p) => ({ id: p.id ?? null, name: p.name, info: p.info })),
    }
    let saved: GroupBuy
    try {
      saved = editing
        ? await api.put<GroupBuy>(`/api/seller/group-buys/${id}`, body)
        : await api.post<GroupBuy>('/api/seller/group-buys', body)
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
      return
    }
    if (openAfter && saved.status !== 'OPEN') {
      try {
        await api.post(`/api/seller/group-buys/${saved.id}/open`)
      } catch (err) {
        // 저장은 됐는데 열기만 실패: 폼에 남으면 다시 저장할 때 공구가 하나 더 생기므로 상세 화면으로 보낸다
        alert(T.savedButOpenFailed(errorMessage(err)))
      }
    }
    setBusy(false)
    navigate(`/seller/group-buys/${saved.id}`)
  }

  return (
    <form onSubmit={(e) => submit(e, false)}>
      {params.get('welcome') && (
        <div className="banner info mb" style={{ borderRadius: 8 }}>{T.welcome}</div>
      )}
      <h1>{editing ? '공구 수정' : '주문서 링크 만들기'}</h1>

      {showChannel && (
        <div className="card">
          <h3>주문 받는 방법 *</h3>
          <div className="segmented">
            {([['LINK_ONLY', '주문 링크만'], ['LINK_AND_COMMENT', '주문 링크 + 댓글']] as const).map(([v, label]) => (
              <label key={v} className={form.orderChannel === v ? 'on' : ''}>
                <input type="radio" name="orderChannel" checked={form.orderChannel === v} onChange={() => set('orderChannel', v)} /> {label}
              </label>
            ))}
          </div>
          <p className="muted small">
            {form.orderChannel === 'LINK_ONLY' && T.channelLinkOnly}
            {form.orderChannel === 'LINK_AND_COMMENT' && T.channelLinkAndComment}
            {!form.orderChannel && T.channelNotChosen}
          </p>
        </div>
      )}

      <div className="card">
        <h3>기본 정보</h3>
        <label className="field"><span>공구 제목 *</span>
          <input type="text" value={form.title} onChange={(e) => set('title', e.target.value)} required placeholder="예: 동해기정떡 2차 (10/15 입고)" />
        </label>
        <label className="field"><span>설명</span>
          <textarea value={form.description} onChange={(e) => set('description', e.target.value)} placeholder="픽업 날짜·시간, 상품 설명 등" />
        </label>
        <label className="field"><span>주문 마감 일시</span>
          <input type="datetime-local" value={form.closeAt} onChange={(e) => set('closeAt', e.target.value)} />
          <span className="muted small" style={{ fontWeight: 400 }}>{T.closeAtHint}</span>
        </label>
      </div>

      <div className="card">
        <h3>상품</h3>
        {form.orderChannel === 'LINK_AND_COMMENT' && form.products.filter((p) => p.name.trim()).length > 1 && (
          <p className="small warn-box">{T.multiProductNoComment}</p>
        )}
        {form.products.map((p, i) => (
          <div key={i} className="row mb" style={{ alignItems: 'flex-end' }}>
            <label className="field grow" style={{ minWidth: 160, marginBottom: 0 }}><span>상품명</span>
              <input type="text" value={p.name} onChange={(e) => set('products', form.products.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} placeholder="예: 1box(30조각)" />
            </label>
            <label className="field" style={{ width: 110, marginBottom: 0 }}><span>단가</span>
              <input type="number" min={0} value={p.unitPrice} onChange={(e) => set('products', form.products.map((x, j) => (j === i ? { ...x, unitPrice: e.target.value } : x)))} />
            </label>
            <label className="field" style={{ width: 100, marginBottom: 0 }}><span>준비 수량</span>
              <input type="number" min={1} value={p.stockLimit} placeholder="무제한" onChange={(e) => set('products', form.products.map((x, j) => (j === i ? { ...x, stockLimit: e.target.value } : x)))} />
            </label>
            {/* 숫자 칸은 오른쪽에 올리고 내리는 화살표 자리가 있어 placeholder("준비 수량 먼저"·"제한없음")가 들어가게 넓힌다 */}
            <label className="field" style={{ width: 156, marginBottom: 0 }} title={T.stockAlertTitle}><span>알림 기준</span>
              <input type="number" min={0} value={p.stockAlertThreshold} placeholder={p.stockLimit ? '알림 없음' : '준비 수량 먼저'} disabled={!p.stockLimit}
                onChange={(e) => set('products', form.products.map((x, j) => (j === i ? { ...x, stockAlertThreshold: e.target.value } : x)))} />
            </label>
            <label className="field" style={{ width: 116, marginBottom: 0 }}><span>1인 최대</span>
              <input type="number" min={1} value={p.perOrderLimit} placeholder="제한없음" onChange={(e) => set('products', form.products.map((x, j) => (j === i ? { ...x, perOrderLimit: e.target.value } : x)))} />
            </label>
            <label className="check small" style={{ whiteSpace: 'nowrap', margin: 0 }} title={T.blockWhenSoldOutTitle}>
              <input type="checkbox" checked={p.blockWhenSoldOut && Boolean(p.stockLimit)} disabled={!p.stockLimit}
                onChange={(e) => set('products', form.products.map((x, j) => (j === i ? { ...x, blockWhenSoldOut: e.target.checked } : x)))} /> 다 팔리면 품절
            </label>
            <button type="button" className="btn small danger" onClick={() => set('products', form.products.filter((_, j) => j !== i))}>삭제</button>
            {p.sold !== undefined && p.sold > 0 && <span className="muted small">주문 {p.sold}개{p.stockLimit && Number(p.stockLimit) < p.sold ? ` · ${p.sold - Number(p.stockLimit)}개 초과` : ''}</span>}
          </div>
        ))}
        <button type="button" className="btn small" onClick={() => set('products', [...form.products, { name: '', unitPrice: '', stockLimit: '', perOrderLimit: '', stockAlertThreshold: '', blockWhenSoldOut: false }])}>+ 상품 추가</button>
        <p className="muted small">{T.stockHelp}</p>
      </div>

      <div className="card">
        <h3>수령 방법</h3>
        <label className="check"><input type="checkbox" checked={form.pickupEnabled} onChange={(e) => set('pickupEnabled', e.target.checked)} /> 픽업</label>
        {form.pickupEnabled && (
          <div style={{ marginLeft: 26 }}>
            <div className="muted small">픽업 결제 방식을 하나 이상 고르세요</div>
            <label className="check"><input type="checkbox" checked={form.pickupOnsiteEnabled} onChange={(e) => set('pickupOnsiteEnabled', e.target.checked)} /> 현장결제 — 입금 대조 없이 수령할 때 결제 완료 처리</label>
            <label className="check"><input type="checkbox" checked={form.pickupPrepayEnabled} onChange={(e) => set('pickupPrepayEnabled', e.target.checked)} /> 선입금 — 계좌를 안내하고 입금 대조로 확인</label>
            <div className="muted small mt">픽업 지점 — 2개 이상이면 고객이 고릅니다</div>
            {form.pickupPoints.map((p, i) => (
              <div key={i} className="row mb">
                {/* 폰처럼 좁으면 지점 이름이 한 줄을 다 쓰고 안내·삭제가 다음 줄로 (이름 칸이 몇 글자 폭으로 줄던 문제) */}
                <input type="text" placeholder="지점 이름" value={p.name} onChange={(e) => set('pickupPoints', form.pickupPoints.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} style={{ flex: '1 1 160px' }} />
                <input type="text" placeholder="주소·시간 안내" value={p.info} onChange={(e) => set('pickupPoints', form.pickupPoints.map((x, j) => (j === i ? { ...x, info: e.target.value } : x)))} style={{ flex: '2 1 180px', width: 'auto', minWidth: 0 }} />
                <button type="button" className="btn small danger" onClick={() => set('pickupPoints', form.pickupPoints.filter((_, j) => j !== i))}>삭제</button>
              </div>
            ))}
            <button type="button" className="btn small" onClick={() => set('pickupPoints', [...form.pickupPoints, { name: '', info: '' }])}>+ 픽업 지점 추가</button>
          </div>
        )}
        <label className="check mt"><input type="checkbox" checked={form.deliveryEnabled} onChange={(e) => set('deliveryEnabled', e.target.checked)} /> 배송 — 선입금만 받고 받는 분·주소는 주문서에서 받음</label>
        {form.deliveryEnabled && (
          <div style={{ marginLeft: 26 }} className="row">
            <select value={form.shippingFeeType} onChange={(e) => set('shippingFeeType', e.target.value as ShippingFeeType)} style={{ width: 160 }}>
              <option value="NONE">배송비 없음</option>
              <option value="FIXED">고정 배송비</option>
            </select>
            {form.shippingFeeType === 'FIXED' && (
              <input type="number" min={0} placeholder="배송비" value={form.shippingFee} onChange={(e) => set('shippingFee', e.target.value)} style={{ width: 140 }} />
            )}
          </div>
        )}
      </div>

      <div className="card">
        <h3>입금 계좌{usesPrepay ? ' *' : ''}</h3>
        {!usesPrepay && <p className="muted small">{T.accountOnlyForPrepay}</p>}
        <div className="row">
          <input type="text" placeholder="은행" value={form.bankName} onChange={(e) => set('bankName', e.target.value)} style={{ flex: 1, minWidth: 100 }} />
          <input type="text" placeholder="계좌번호" value={form.accountNumber} onChange={(e) => set('accountNumber', e.target.value)} style={{ flex: 2, minWidth: 160 }} />
          <input type="text" placeholder="예금주" value={form.accountHolder} onChange={(e) => set('accountHolder', e.target.value)} style={{ flex: 1, minWidth: 100 }} />
        </div>
        <p className="muted small">{T.defaultAccountHint}</p>
      </div>

      <div className="card">
        <h3>고객 입력 항목·기타</h3>
        <label className="check"><input type="checkbox" checked={form.unitAddressFieldEnabled} onChange={(e) => set('unitAddressFieldEnabled', e.target.checked)} /> 동·호수 받기</label>
        <label className="field mt"><span>카톡 문의 링크</span>
          <input type="text" value={form.kakaoInquiryUrl} onChange={(e) => set('kakaoInquiryUrl', e.target.value)} placeholder="https://pf.kakao.com/..." />
        </label>
        <label className="field"><span>관리자 닉네임</span>
          <input type="text" value={form.adminNames} onChange={(e) => set('adminNames', e.target.value)} placeholder="예: 공구장, 달콤" />
          <span className="muted small" style={{ fontWeight: 400 }}>{T.adminNamesHint}</span>
        </label>
      </div>

      <ErrorBox message={error} />
      <div className="row sticky-bottom">
        <button className="btn" disabled={busy}>저장</button>
        <button type="button" className="btn primary" disabled={busy} onClick={(e) => submit(e as unknown as FormEvent, true)}>저장하고 주문 받기 시작</button>
        <button type="button" className="btn" onClick={() => navigate(-1)}>취소</button>
      </div>
    </form>
  )
}
