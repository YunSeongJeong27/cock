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
interface CustomerTotal { customerKey: string; authorName: string; quantity: number; amount: number; memo: string; needsReview: boolean }
interface CommentsResponse { comments: CommentView[]; customers: CustomerTotal[]; totalQuantity: number; reviewCount: number; appliedOrderCount: number; productId: number | null }
interface Draft { author: string; content: string; duplicateSuspected: boolean }

const TYPE_LABEL = { ORDER: '주문', CANCEL: '취소', NOT_ORDER: '주문 아님', REVIEW: '확인 필요' } as const

/**
 * 2단계: 밴드 댓글 스크린샷·텍스트 → 검수 → 주문 집계 (정리본 8장, M6)
 * 집계 결과를 "주문으로 반영"하면 입금 대조·수령 체크·엑셀에서 똑같이 쓸 수 있다.
 */
export default function CommentsTab({ groupBuy, reload }: { groupBuy: GroupBuy; reload: () => void }) {
  const [data, setData] = useState<CommentsResponse | null>(null)
  const [drafts, setDrafts] = useState<Draft[] | null>(null)
  const [text, setText] = useState('')
  const [files, setFiles] = useState<FileList | null>(null)
  const [productId, setProductId] = useState<string>(String(groupBuy.products[0]?.id ?? ''))
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
    setDrafts(r.comments.length ? r.comments : [{ author: '', content: '', duplicateSuspected: false }])
    if (r.limitExceeded) setError(r.notice)
    else if (r.notice) setNotice(r.notice)
  })

  const parseText = () => run(() => api.post<{ comments: Draft[] }>(`${base}/parse-text`, { text }), (r) => setDrafts(r.comments))

  const saveDrafts = (source: 'SCREENSHOT' | 'TEXT') => run(() => api.post(`${base}`, { source, comments: drafts }), () => {
    setDrafts(null)
    setText('')
    setFiles(null)
    load()
  })

  const updateComment = (c: CommentView, patch: Partial<{ authorName: string; content: string; manualQuantity: number | null }>) =>
    run(() => api.put(`${base}/${c.id}`, { authorName: c.authorName, content: c.content, manualQuantity: c.manualQuantity, ...patch }), load)

  const apply = () => {
    if (!confirm('집계 결과를 주문으로 반영합니다. (이전에 반영한 댓글 주문은 새 결과로 바뀝니다) 계속할까요?')) return
    run(() => api.post<{ createdCount: number }>(`${base}/apply`, { productId: Number(productId) }), (r) => {
      setNotice(`댓글 주문 ${r.createdCount}건을 만들었습니다. 주문 목록·입금 대조·수령 체크에서 확인하세요.`)
      load()
      reload()
    })
  }

  const product = groupBuy.products.find((p) => String(p.id) === productId)

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
            <button className="btn" onClick={() => setDrafts(null)}>취소</button>
          </div>
        </div>
      )}

      {data && data.comments.length > 0 && (
        <>
          <div className="card">
            <div className="row between">
              <h3>고객별 집계</h3>
              <div className="row">
                {groupBuy.products.length > 1 && (
                  <select value={productId} onChange={(e) => setProductId(e.target.value)} style={{ width: 180 }}>
                    {groupBuy.products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                  </select>
                )}
                <button className="btn primary" disabled={busy || !productId} onClick={apply}>주문으로 반영</button>
              </div>
            </div>
            <p className="muted small">
              {product ? `${product.name} ${won(product.unitPrice)} 기준` : ''} · 고객 {data.customers.length}명 · {data.totalQuantity}개
              {data.reviewCount > 0 && <b style={{ color: 'var(--warn)' }}> · 확인 필요 {data.reviewCount}개 (수량을 정하기 전까지 합계에서 빠짐)</b>}
              {data.appliedOrderCount > 0 && ` · 반영된 댓글 주문 ${data.appliedOrderCount}건`}
            </p>
            {groupBuy.products.length > 1 && <p className="small warn-box">상품이 여러 개인 공구의 댓글("간장1 양념1")을 상품별로 나누는 규칙은 아직 정해지지 않았습니다. 지금은 고른 상품 하나로 집계합니다.</p>}
            <div className="table-wrap">
              <table>
                <thead><tr><th>고객(닉네임)</th><th className="right">수량</th><th className="right">금액</th><th>메모</th></tr></thead>
                <tbody>
                  {data.customers.map((c) => (
                    <tr key={c.customerKey}><td>{c.authorName}{c.needsReview && <span className="chip">확인 필요 있음</span>}</td><td className="right">{c.quantity}</td><td className="right">{won(c.amount)}</td><td className="small">{c.memo}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
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
