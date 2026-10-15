import { useCallback, useEffect, useState } from 'react'
import { api, errorMessage, won } from '../../api'
import { ErrorBox } from '../../components/Ui'
import type { GroupBuy } from '../../types'
import { CaptureUsageLine, useCaptureStatus } from '../../components/CaptureUsage'

interface CommentView {
  id: number
  seq: number
  authorName: string
  content: string
  duplicateSuspected: boolean
  manualQuantity: number | null
  type: 'ORDER' | 'CANCEL' | 'NOT_ORDER' | 'REVIEW'
  quantity: number | null
  memo: string
  reviewReason: string | null
  excludedAsAdmin: boolean
}
/**
 * linkOrderNos: 닉네임이 같은 링크 주문 번호 (댓글엔 휴대폰 번호가 없어 닉네임으로만 비교. 경고만)
 * applyState: 이미 주문으로 반영됐는지. 다시 반영해도 이미 반영된 손님은 그대로 두고 NEW 만 추가한다
 */
interface CustomerTotal {
  customerKey: string; authorName: string; quantity: number; amount: number; memo: string; needsReview: boolean; linkOrderNos: number[]
  applyState: 'NEW' | 'SAME' | 'DIFFERENT' | 'CANCELLED'; orderNo: number | null; orderQuantity: number | null
}
/** 반영된 댓글 주문 중 지금 집계에 없는 것 (댓글로 취소했거나 댓글을 지움) */
interface StaleOrder { orderNo: number; nickname: string; quantity: number }
interface CommentsResponse { comments: CommentView[]; customers: CustomerTotal[]; totalQuantity: number; reviewCount: number; appliedOrderCount: number; productId: number | null; staleOrders: StaleOrder[] }
interface Draft { author: string; content: string; duplicateSuspected: boolean }

const TYPE_LABEL = { ORDER: '주문', CANCEL: '취소', NOT_ORDER: '주문 아님', REVIEW: '확인 필요' } as const

/** 비교용: 띄어쓰기 무시 */
const squash = (v: string) => v.replace(/\s+/g, '')

/**
 * 캡처를 다시 올리면(처음부터 다시 찍은 경우 등) 이미 저장한 댓글이 또 들어온다 → 똑같은 댓글(작성자·내용)은 빼 둔다.
 * 같은 댓글이 저장된 개수만큼만 뺀다 (같은 손님이 같은 댓글을 두 번 단 경우 두 번째는 남김).
 */
function splitAlreadySaved(drafts: Draft[], saved: CommentView[]): { fresh: Draft[]; skipped: Draft[] } {
  const count = new Map<string, number>()
  for (const c of saved) {
    const k = squash(c.authorName) + '|' + squash(c.content)
    count.set(k, (count.get(k) ?? 0) + 1)
  }
  const fresh: Draft[] = []
  const skipped: Draft[] = []
  for (const d of drafts) {
    const k = squash(d.author) + '|' + squash(d.content)
    const n = count.get(k) ?? 0
    if (n > 0) {
      count.set(k, n - 1)
      skipped.push(d)
    } else {
      fresh.push(d)
    }
  }
  return { fresh, skipped }
}

/**
 * 2단계: 밴드 댓글 스크린샷·텍스트 → 검수 → 주문 집계 (정리본 8장, M6)
 * 집계 결과를 "주문으로 반영"하면 입금 대조·수령 체크·엑셀에서 똑같이 쓸 수 있다.
 */
export default function CommentsTab({ groupBuy, reload }: { groupBuy: GroupBuy; reload: () => void }) {
  const [data, setData] = useState<CommentsResponse | null>(null)
  const [drafts, setDrafts] = useState<Draft[] | null>(null)
  /** 이미 저장된 댓글과 똑같아서 검수 목록에서 뺀 것 */
  const [skipped, setSkipped] = useState<Draft[]>([])
  const [text, setText] = useState('')
  const [files, setFiles] = useState<FileList | null>(null)
  // 댓글 주문은 상품이 1개인 공구에서만 받으므로 그 상품으로 집계한다
  const productId = String(groupBuy.products[0]?.id ?? '')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const base = `/api/seller/group-buys/${groupBuy.id}/comments`
  const [usageKey, setUsageKey] = useState(0)
  const capture = useCaptureStatus(usageKey)

  const load = useCallback(() => {
    api.get<CommentsResponse>(`${base}?productId=${productId}`).then(setData).catch((e) => setError(errorMessage(e)))
  }, [base, productId])
  useEffect(() => {
    load()
  }, [load])

  async function run<T>(fn: () => Promise<T>, after?: (t: T) => void) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const r = await fn()
      after?.(r)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const extract = () => run(async () => {
    const fd = new FormData()
    Array.from(files!).forEach((f) => fd.append('files', f))
    try {
      return await api.post<{ comments: Draft[]; notice: string | null; limitExceeded: boolean }>(`${base}/extract`, fd)
    } finally {
      // 실패해도 서버는 AI 호출 전에 장수를 차감하므로 남은 장수를 항상 다시 읽는다
      setUsageKey((k) => k + 1)
    }
  }, (r) => {
    showDrafts(r.comments)
    if (r.limitExceeded) setError(r.notice)
    else if (r.notice) setNotice(r.notice)
  })

  function showDrafts(read: Draft[]) {
    const { fresh, skipped: same } = splitAlreadySaved(read, data?.comments ?? [])
    setSkipped(same)
    setDrafts(fresh.length || same.length ? fresh : [{ author: '', content: '', duplicateSuspected: false }])
  }

  const parseText = () => run(() => api.post<{ comments: Draft[] }>(`${base}/parse-text`, { text }), (r) => showDrafts(r.comments))

  const saveDrafts = (source: 'SCREENSHOT' | 'TEXT') => run(() => api.post(`${base}`, { source, comments: drafts }), () => {
    setDrafts(null)
    setSkipped([])
    setText('')
    setFiles(null)
    load()
  })

  const updateComment = (c: CommentView, patch: Partial<{ authorName: string; content: string; manualQuantity: number | null }>) =>
    run(() => api.put(`${base}/${c.id}`, { authorName: c.authorName, content: c.content, manualQuantity: c.manualQuantity, ...patch }), load)

  const newCustomers = data?.customers.filter((c) => c.applyState === 'NEW' && c.quantity > 0) ?? []
  const toCheck = (data?.customers.filter((c) => c.applyState === 'DIFFERENT').length ?? 0) + (data?.staleOrders.length ?? 0)

  const apply = () => {
    const same = newCustomers.filter((c) => c.linkOrderNos.length > 0).length
    const warn = same > 0 ? `\n\n⚠ 그중 링크 주문과 닉네임이 같은 손님이 ${same}명 있습니다. 같은 사람이면 주문이 두 번 잡히니, 반영 후 주문 목록에서 확인해 주세요.` : ''
    if (!confirm(`새 손님 ${newCustomers.length}명을 주문으로 추가합니다. 이미 반영된 손님의 주문은 그대로 둡니다. 계속할까요?${warn}`)) return
    run(() => api.post<{ createdCount: number; reviewCount: number }>(`${base}/apply`, { productId: Number(productId) }), (r) => {
      setNotice(`새 댓글 주문 ${r.createdCount}건을 만들었습니다.`
        + (r.reviewCount > 0 ? ` 이미 반영된 주문과 댓글이 다른 ${r.reviewCount}건은 표의 ⚠ 표시를 보고 주문 목록에서 직접 바꾸거나 취소해 주세요.` : ''))
      load()
      reload()
    })
  }

  function applyLabel(c: CustomerTotal) {
    switch (c.applyState) {
      case 'NEW': return c.quantity > 0 ? <span className="chip">새로 추가</span> : <span className="small muted">수량을 정하면 추가</span>
      case 'SAME': return <span className="small muted">주문 {c.orderNo}번 ✓</span>
      case 'DIFFERENT': return <span className="small" style={{ color: 'var(--warn)' }}>⚠ 주문 {c.orderNo}번은 {c.orderQuantity}개 (댓글 합계와 다름, 직접 확인)</span>
      case 'CANCELLED': return <span className="small muted">주문 {c.orderNo}번 취소됨 (다시 안 만듦)</span>
    }
  }

  const product = groupBuy.products.find((p) => String(p.id) === productId)

  // 상품이 여러 개인 공구는 댓글을 상품별로 나눠 세는 규칙이 없어 댓글 올리기·반영을 막는다 (2026-10-15 사용자 결정, 규칙은 추후)
  if (groupBuy.products.length > 1) {
    return (
      <div className="card">
        <h3>댓글 집계</h3>
        <p className="warn-box">
          상품이 여러 개인 공구는 아직 댓글 주문을 받을 수 없습니다. 댓글을 상품별로 나눠 세는 기능을 준비 중입니다.
          <b> 이 공구는 주문 링크로 주문을 받아 주세요.</b>
        </p>
        {data && data.appliedOrderCount > 0 && <p className="small muted">이전에 반영한 댓글 주문 {data.appliedOrderCount}건은 주문 목록에 그대로 있습니다.</p>}
      </div>
    )
  }

  return (
    <div>
      <div className="card">
        <h3>댓글 올리기</h3>
        <p className="muted small">밴드 댓글 화면 캡처(여러 장) 또는 댓글 텍스트를 붙여넣으세요. 공지·광고도 일단 읽고, 관리자 닉네임(공구 설정)과 주문 아닌 댓글은 집계에서 뺍니다. 캡처는 외부 AI로 전송되고 읽은 직후 삭제됩니다.</p>
        <CaptureUsageLine status={capture} />
        <div className="row">
          <input type="file" accept="image/*" multiple onChange={(e) => setFiles(e.target.files)} />
          <button className="btn" disabled={!files || busy} onClick={extract}>캡처 글자 읽기</button>
        </div>
        <label className="field mt"><span>또는 텍스트 붙여넣기 (한 줄에 "닉네임 | 댓글" 또는 "닉네임: 댓글" 또는 탭으로 구분)</span>
          <textarea value={text} onChange={(e) => setText(e.target.value)} placeholder={'꼭지맘 217-1505 | 1\n205-1205 | 1 7시이후 픽업\n망고맘 | 2박스요'} />
        </label>
        <button className="btn" disabled={!text.trim() || busy} onClick={parseText}>텍스트 읽기</button>
      </div>

      <ErrorBox message={error} />
      {notice && <div className="success-box">{notice}</div>}

      {drafts && (
        <div className="card">
          <h3>검수 ({drafts.length}개)</h3>
          {skipped.length > 0 && (
            <div className="warn-box row between">
              <span className="small">이미 저장된 댓글과 똑같은 {skipped.length}개는 뺐습니다. (같은 손님이 같은 댓글을 또 단 거라면 다시 넣으세요)</span>
              <button className="btn small" onClick={() => { setDrafts([...drafts, ...skipped]); setSkipped([]) }}>다시 넣기</button>
            </div>
          )}
          <p className="muted small">작성자·내용을 고치거나 지울 수 있습니다. 노란 줄은 캡처가 겹쳐서 생긴 중복으로 의심되는 댓글입니다. 읽지 못한 글자([?])는 고친 뒤 저장할 수 있습니다.</p>
          <div className="table-wrap">
            <table className="stack">
              <thead><tr><th>작성자</th><th>내용</th><th></th></tr></thead>
              <tbody>
                {drafts.map((d, i) => (
                  <tr key={i} style={{ background: d.duplicateSuspected ? 'var(--warn-weak)' : undefined }}>
                    <td data-label="작성자"><input type="text" value={d.author} style={{ borderColor: d.author.includes('[?]') ? 'var(--danger)' : undefined }} onChange={(e) => setDrafts(drafts.map((x, j) => (j === i ? { ...x, author: e.target.value } : x)))} /></td>
                    <td data-label="내용"><input type="text" value={d.content} style={{ borderColor: d.content.includes('[?]') ? 'var(--danger)' : undefined }} onChange={(e) => setDrafts(drafts.map((x, j) => (j === i ? { ...x, content: e.target.value } : x)))} /></td>
                    <td className="stack-action"><button className="btn small danger" onClick={() => setDrafts(drafts.filter((_, j) => j !== i))}>삭제</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="row mt">
            <button className="btn small" onClick={() => setDrafts([...drafts, { author: '', content: '', duplicateSuspected: false }])}>+ 줄 추가</button>
            <button className="btn primary" disabled={busy || drafts.some((d) => d.author.includes('[?]') || d.content.includes('[?]'))} onClick={() => saveDrafts(files ? 'SCREENSHOT' : 'TEXT')}>검수 완료, 댓글 저장</button>
            <button className="btn" onClick={() => { setDrafts(null); setSkipped([]) }}>취소</button>
          </div>
        </div>
      )}

      {data && data.comments.length > 0 && (
        <>
          <div className="card">
            <div className="row between">
              <h3>고객별 집계</h3>
              <div className="row">
                <button className="btn primary" disabled={busy || !productId || newCustomers.length === 0} onClick={apply}>
                  {newCustomers.length > 0 ? `새 손님 ${newCustomers.length}명 주문으로 반영` : '새로 반영할 손님 없음'}
                </button>
              </div>
            </div>
            <p className="muted small">
              {product ? `${product.name} ${won(product.unitPrice)} 기준` : ''} · 고객 {data.customers.length}명 · {data.totalQuantity}개
              {data.reviewCount > 0 && <b style={{ color: 'var(--warn)' }}> · 확인 필요 {data.reviewCount}개 (수량을 정하기 전까지 합계에서 빠짐)</b>}
              {data.appliedOrderCount > 0 && ` · 반영된 댓글 주문 ${data.appliedOrderCount}건`}
              {toCheck > 0 && <b style={{ color: 'var(--warn)' }}> · 반영된 주문과 다른 손님 {toCheck}명</b>}
            </p>
            <p className="muted small">캡처를 여러 번 올려도 됩니다. 반영하면 처음 보는 손님만 주문으로 추가하고, 이미 반영된 손님의 주문은 바꾸지 않습니다.</p>
            <div className="table-wrap">
              <table className="stack">
                <thead><tr><th>고객(닉네임)</th><th className="right">수량</th><th className="right">금액</th><th>메모</th><th>주문 반영</th></tr></thead>
                <tbody>
                  {data.customers.map((c) => (
                    <tr key={c.customerKey}>
                      <td className="stack-title"><b>{c.authorName}</b>{c.needsReview && <span className="chip">확인 필요 있음</span>}
                        {c.linkOrderNos.length > 0 && <div className="small" style={{ color: 'var(--warn)' }}>⚠ 링크 주문 {c.linkOrderNos.join(', ')}번과 닉네임 같음 (같은 사람이면 중복)</div>}</td>
                      <td className="right" data-label="수량">{c.quantity}</td>
                      <td className="right" data-label="금액">{won(c.amount)}</td>
                      <td className="small" data-label="메모">{c.memo}</td>
                      <td data-label="주문 반영">{applyLabel(c)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {data.staleOrders.length > 0 && (
              <div className="warn-box">
                <div className="small"><b>집계에 없는 댓글 주문</b> (댓글로 취소했거나 댓글을 지운 손님). 자동으로 취소하지 않으니 주문 목록에서 확인해 주세요.</div>
                {data.staleOrders.map((o) => <div key={o.orderNo} className="small">· 주문 {o.orderNo}번 {o.nickname} {o.quantity}개</div>)}
              </div>
            )}
          </div>
          <div className="card">
            <div className="row between">
              <h3>댓글 ({data.comments.length})</h3>
              <button className="btn small danger" onClick={() => confirm('저장한 댓글을 모두 지울까요?') && run(() => api.del(base), load)}>모두 지우기</button>
            </div>
            <div className="table-wrap">
              <table className="stack">
                <thead><tr><th>#</th><th>작성자</th><th>내용</th><th>해석</th><th>수량 직접 입력</th><th></th></tr></thead>
                <tbody>
                  {data.comments.map((c) => (
                    <tr key={c.id} className={c.excludedAsAdmin || c.type === 'NOT_ORDER' ? 'dim' : ''} style={{ background: c.type === 'REVIEW' && c.manualQuantity === null ? 'var(--warn-weak)' : undefined }}>
                      <td className="hide-mobile">{c.seq}</td>
                      <td className="stack-title"><span className="show-mobile muted">{c.seq}. </span>{c.authorName}{c.excludedAsAdmin && <div className="small">관리자 제외</div>}</td>
                      <td data-label="내용">{c.content}{c.duplicateSuspected && <div className="small" style={{ color: 'var(--warn)' }}>중복 의심</div>}</td>
                      <td className="small" data-label="해석">
                        {TYPE_LABEL[c.type]}{c.quantity !== null && ` ${c.quantity}개`}
                        {c.reviewReason && <div className="muted">{c.reviewReason}</div>}
                        {c.memo && <div className="muted">메모: {c.memo}</div>}
                      </td>
                      <td data-label="수량 직접 입력">
                        <input type="number" min={0} defaultValue={c.manualQuantity ?? ''} style={{ width: 80 }}
                          onBlur={(e) => {
                            const v = e.target.value === '' ? null : Number(e.target.value)
                            if (v !== c.manualQuantity) updateComment(c, { manualQuantity: v })
                          }} />
                      </td>
                      <td className="stack-action"><button className="btn small danger" onClick={() => run(() => api.del(`${base}/${c.id}`), load)}>삭제</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
