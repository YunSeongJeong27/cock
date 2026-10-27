import { useCallback, useEffect, useState } from 'react'
import { api, dateTime, errorMessage, won } from '../../api'
import { ErrorBox, StatusBadge } from '../../components/Ui'
import { depositText as T } from '../../texts/deposit'
import type { ColumnMapping, GroupBuy, MatchResponse, MatchRow, OrderBrief, ParsedStatement, Preset, UploadSummary } from '../../types'
import ScreenshotDeposits from './ScreenshotDeposits'

interface Props { groupBuy: GroupBuy; reload: () => void }

const emptyMapping: ColumnMapping = { dateColumn: '', nameColumn: '', amountColumn: '', typeColumn: '' }

function orderLabel(o: OrderBrief) {
  return `${o.orderNo}번 ${o.nickname ? o.nickname + ' / ' : ''}${o.depositorName ?? ''} · 전화 뒷자리 ${o.phoneLast4 || '-'} · ${won(o.expectedAmount)}`
}

export default function DepositTab({ groupBuy, reload }: Props) {
  const [file, setFile] = useState<File | null>(null)
  const [presets, setPresets] = useState<Preset[]>([])
  const [presetId, setPresetId] = useState('')
  const [manual, setManual] = useState(false)
  const [mapping, setMapping] = useState<ColumnMapping>(emptyMapping)
  const [parsed, setParsed] = useState<ParsedStatement | null>(null)
  const [duplicates, setDuplicates] = useState(0)
  const [match, setMatch] = useState<MatchResponse | null>(null)
  const [uploads, setUploads] = useState<UploadSummary[]>([])
  const [choice, setChoice] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [inputKey, setInputKey] = useState(0)
  const [showCriteria, setShowCriteria] = useState(false)
  const [showHandled, setShowHandled] = useState(false)
  const base = `/api/seller/group-buys/${groupBuy.id}/deposits`

  const loadMatch = useCallback(async () => {
    try {
      const m = await api.get<MatchResponse>(`${base}/match`)
      setMatch(m)
      setUploads(m.uploads)
      const initial: Record<number, string> = {}
      m.rows.forEach((r) => {
        // 자동 매칭·추천은 그 주문, 거래내역 없이 확정한 주문의 입금으로 보이면 그 주문을 미리 골라 둔다
        if (r.order) initial[r.rowId] = String(r.order.orderId)
        else if (r.linkOrderId != null) initial[r.rowId] = String(r.linkOrderId)
      })
      setChoice(initial)
    } catch (e) {
      setError(errorMessage(e))
    }
  }, [base])

  useEffect(() => {
    loadMatch()
    api.get<Preset[]>('/api/seller/bank-columns/presets').then(setPresets).catch(() => undefined)
  }, [loadMatch])

  function formData() {
    const fd = new FormData()
    fd.append('file', file as File)
    if (presetId) fd.append('presetId', presetId)
    else if (manual) {
      Object.entries(mapping).forEach(([k, v]) => {
        if (v) fd.append(k, v)
      })
    }
    return fd
  }

  async function preview() {
    if (!file) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const res = await api.post<{ parsed: ParsedStatement; duplicateCount: number }>(`${base}/preview`, formData())
      setParsed(res.parsed)
      setDuplicates(res.duplicateCount)
      if (!res.parsed.headerRowNo) setManual(true)
      else if (!manual && !presetId) setMapping(res.parsed.mapping)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function upload() {
    if (!file) return
    setBusy(true)
    setError(null)
    try {
      const res = await api.post<{ savedCount: number; duplicateCount: number }>(`${base}/uploads`, formData())
      setNotice(T.uploaded(res.savedCount, res.duplicateCount))
      setParsed(null)
      setFile(null)
      setInputKey((k) => k + 1)
      await loadMatch()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function savePreset() {
    const name = prompt(T.presetNamePrompt)
    if (!name) return
    try {
      const p = await api.post<Preset>('/api/seller/bank-columns/presets', { name, ...mapping })
      setPresets((list) => [...list.filter((x) => x.id !== p.id), p])
      setNotice(T.presetSaved(name))
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function confirmItems(items: { rowId: number; orderId: number }[]) {
    if (items.length === 0 || busy) return
    setBusy(true) // 확정 버튼을 빠르게 두 번 눌러도 한 번만 보낸다
    setError(null)
    try {
      const res = await api.post<{ confirmedCount: number; errors: string[] }>(`${base}/confirm`, { items })
      setNotice(T.confirmed(res.confirmedCount))
      if (res.errors.length) setError(res.errors.join('\n'))
      await loadMatch()
      reload()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  /** "이미 처리 완료된 입금"으로 옮기기·되돌리기 (2026-10-26). 대조에서만 빼고 주문은 그대로 */
  async function setHandled(row: { rowId: number; depositorName: string; amount: number }, handled: boolean) {
    if (handled && !confirm(T.confirmHandled(row.depositorName, won(row.amount)))) return
    setError(null)
    try {
      if (handled) await api.post(`${base}/rows/${row.rowId}/handled`)
      else await api.del(`${base}/rows/${row.rowId}/handled`)
      await loadMatch()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function deleteUpload(id: number) {
    if (!confirm(T.confirmDeleteUpload)) return
    try {
      await api.del(`${base}/uploads/${id}`)
      loadMatch()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  // "자동 매칭 모두 확정"은 화면에서 지금 고른 주문으로 확정한다 (판매자가 비우면 빼고, 바꾸면 바꾼 주문으로).
  // 확인 필요(같은 고객의 주문·입금이 여러 건)는 빼고 하나씩 확정하게 한다 (2026-10-26)
  const matched = match?.rows.filter((r) => r.status === 'MATCHED' && !r.checkNote && choice[r.rowId]) ?? []

  return (
    <div>
      <div className="card">
        <h3>① 거래내역 엑셀·CSV·PDF 올리기</h3>
        <p className="muted small">{T.uploadHelp}</p>
        <input key={inputKey} type="file" accept=".xlsx,.xls,.csv,.pdf" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setParsed(null) }} />
        <div className="row mt">
          <select value={presetId} onChange={(e) => { setPresetId(e.target.value); setManual(false) }} style={{ width: 220 }}>
            <option value="">헤더(컬럼) 자동 인식</option>
            {presets.map((p) => <option key={p.id} value={p.id}>저장한 설정: {p.name}</option>)}
          </select>
          <label className="check"><input type="checkbox" checked={manual} onChange={(e) => { setManual(e.target.checked); setPresetId('') }} /> 헤더(컬럼)명 직접 지정</label>
        </div>
        {manual && (
          <div className="mt">
            <p className="small muted">{T.manualColumnsHelp}</p>
            <datalist id="column-options">{parsed?.columnOptions.map((c) => <option key={c} value={c} />)}</datalist>
            <div className="mapping-grid">
              <label className="field"><span>입금자명 헤더(컬럼) *</span><input type="text" list="column-options" value={mapping.nameColumn ?? ''} onChange={(e) => setMapping({ ...mapping, nameColumn: e.target.value })} placeholder="예: 기재내용" /></label>
              <label className="field"><span>금액 헤더(컬럼) *</span><input type="text" list="column-options" value={mapping.amountColumn ?? ''} onChange={(e) => setMapping({ ...mapping, amountColumn: e.target.value })} placeholder="예: 입금액" /></label>
              <label className="field"><span>날짜 헤더(컬럼)</span><input type="text" list="column-options" value={mapping.dateColumn ?? ''} onChange={(e) => setMapping({ ...mapping, dateColumn: e.target.value })} placeholder="예: 거래일시" /></label>
              <label className="field"><span>거래 유형 헤더(컬럼)</span><input type="text" list="column-options" value={mapping.typeColumn ?? ''} onChange={(e) => setMapping({ ...mapping, typeColumn: e.target.value })} placeholder="예: 구분" /></label>
            </div>
          </div>
        )}
        <div className="row mt">
          <button className="btn" disabled={!file || busy} onClick={preview}>미리보기</button>
          <button className="btn primary" disabled={!file || busy || (parsed !== null && !parsed.headerRowNo)} onClick={upload}>올리기</button>
        </div>

        {parsed && (
          <div className="mt">
            {parsed.headerRowNo > 0 ? (
              <>
                <div className="success-box">
                  {T.columnsFound(parsed.autoDetected, file?.name.toLowerCase().endsWith('.pdf') ? null : parsed.headerRowNo)}
                  <div className="mt">
                    <span className="chip">입금자명: {parsed.mapping.nameColumn}</span>
                    <span className="chip">금액: {parsed.mapping.amountColumn}</span>
                    <span className="chip">날짜: {parsed.mapping.dateColumn || '없음'}</span>
                    <span className="chip">유형: {parsed.mapping.typeColumn || '없음'}</span>
                  </div>
                  <div className="small mt">{T.previewCounts(parsed.deposits.length, parsed.skippedRows, duplicates)}</div>
                  <div className="small">{T.wrongColumnsHint}</div>
                </div>
                {manual && <button className="btn small" onClick={savePreset}>이 헤더(컬럼) 설정을 은행별로 저장</button>}
                <div className="table-wrap mt">
                  <table>
                    <thead><tr><th>행</th><th>입금자명</th><th className="right">금액</th><th>일시</th></tr></thead>
                    <tbody>
                      {parsed.deposits.slice(0, 20).map((d) => (
                        <tr key={d.rowNo}><td>{d.rowNo}</td><td>{d.name}</td><td className="right">{won(d.amount)}</td><td className="small">{d.dateText ?? '-'}</td></tr>
                      ))}
                    </tbody>
                  </table>
                  {parsed.deposits.length > 20 && <div className="muted small">… 외 {parsed.deposits.length - 20}건</div>}
                </div>
              </>
            ) : (
              <>
                <div className="warn-box">{parsed.message}</div>
                <div className="table-wrap">
                  <table className="preview-table">
                    <tbody>
                      {parsed.preview.map((row, i) => (
                        <tr key={i}><td className="muted">{i + 1}</td>{row.map((c, j) => <td key={j}>{c}</td>)}</tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      <ScreenshotDeposits groupBuy={groupBuy} onSaved={loadMatch} />

      <ErrorBox message={error} />
      {notice && <div className="success-box">{notice}</div>}

      {match && (
        <div className="card">
          <div className="row between">
            <h3>② 대조 결과</h3>
            <div className="row">
              <button className="btn small" onClick={() => setShowCriteria(true)}>대조결과 기준 설명</button>
              {matched.length > 0 && (
                <button className="btn ok" disabled={busy} onClick={() => confirmItems(matched.map((r) => ({ rowId: r.rowId, orderId: Number(choice[r.rowId]) })))}>
                  자동 매칭 {matched.length}건 모두 확정
                </button>
              )}
            </div>
          </div>
          <p className="muted small">{T.confirmHelp}</p>
          <div className="row small mb">
            <StatusBadge status="MATCHED" label={`자동 매칭 ${match.matchedCount}`} />
            <StatusBadge status="SUGGESTED" label={`추천 ${match.suggestedCount}`} />
            <StatusBadge status="AMBIGUOUS" label={`후보 여러 명 ${match.ambiguousCount}`} />
            <StatusBadge status="UNRELATED" label={`관련 없는 입금 ${match.unrelatedCount}`} />
          </div>
          {match.rows.length === 0 && <p className="muted">{T.noDeposits}</p>}
          {match.rows.map((r) => (
            <MatchItem key={r.rowId} row={r} openOrders={match.openOrders} manualOrders={match.manualOrders} value={choice[r.rowId] ?? ''} busy={busy}
              onHandled={() => setHandled(r, true)}
              onChange={(v) => setChoice({ ...choice, [r.rowId]: v })}
              onConfirm={() => choice[r.rowId] && confirmItems([{ rowId: r.rowId, orderId: Number(choice[r.rowId]) }])}
              onRefundCheck={(orderId) => confirmItems([{ rowId: r.rowId, orderId }])} />
          ))}
        </div>
      )}

      {match && match.unpaid.length > 0 && (
        <div className="card">
          <h3>③ 입금이 안 보이는 주문 ({match.unpaid.length})</h3>
          {match.unpaid.map((o) => <div key={o.orderId} className="small" style={{ padding: '4px 0' }}>{orderLabel(o)} · 주문 {dateTime(o.orderedAt)}</div>)}
        </div>
      )}

      {match && match.used.length > 0 && (
        <div className="card">
          <h3>④ 확정한 입금 ({match.used.length})</h3>
          {match.used.map((u) => (
            <div key={u.orderId} className="small" style={{ borderTop: '1px solid var(--line)', padding: '6px 0' }}>
              <div>
                <b>{u.depositorName}</b> {won(u.amount)} <span className="muted">{u.datetimeText ?? ''}</span>
                {u.manual && <span className="chip">{u.rowId != null ? '수동 확정 후 연결' : '수동 확정'}</span>}
              </div>
              <div className="muted">→ {u.order ? orderLabel(u.order) : `${u.orderNo}번`}</div>
            </div>
          ))}
        </div>
      )}

      {match && match.handled.length > 0 && (
        <div className="card">
          <div className="row between">
            <h3>이미 처리 완료된 입금 ({match.handled.length})</h3>
            <button className="btn small" onClick={() => setShowHandled(!showHandled)}>{showHandled ? '접기' : '이미 처리 완료된 입금 보기'}</button>
          </div>
          {showHandled && (
            <>
              <p className="muted small">{T.handledHelp}</p>
              {match.handled.map((h) => (
                <div key={h.rowId} className="row between small" style={{ borderTop: '1px solid var(--line)', padding: '6px 0' }}>
                  <span><b>{h.depositorName}</b> {won(h.amount)} <span className="muted">{h.datetimeText ?? ''} · 옮긴 시각 {dateTime(h.handledAt)}</span></span>
                  <button className="btn small" onClick={() => setHandled(h, false)}>되돌리기</button>
                </div>
              ))}
            </>
          )}
        </div>
      )}

      {showCriteria && <CriteriaModal onClose={() => setShowCriteria(false)} />}

      {uploads.length > 0 && (
        <div className="card">
          <h3>올린 파일</h3>
          {uploads.map((u) => (
            <div key={u.id} className="row between small" style={{ padding: '4px 0' }}>
              <span>{u.fileName ?? (u.source === 'SCREENSHOT' ? '스크린샷' : '')} · 입금 {u.rowCount}건 · 확정 {u.usedCount}건 · {dateTime(u.uploadedAt)}</span>
              <button className="btn small danger" onClick={() => deleteUpload(u.id)} disabled={u.usedCount > 0}>지우기</button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** 대조 결과 기준 설명 창 (2026-10-26 사용자 요청: 처음 보는 판매자가 이해하기 쉽게 핵심만) */
function CriteriaModal({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal modal-wide" role="dialog" aria-modal="true" aria-labelledby="criteria-title" onClick={(e) => e.stopPropagation()}>
        <h3 id="criteria-title">대조결과 기준 설명</h3>
        {T.criteria.map((c) => (
          <div key={c.name} className="criteria-item">
            <StatusBadge status={c.status} label={c.name} />
            <div className="small">{c.desc}</div>
          </div>
        ))}
        <p className="small muted mt">{T.criteriaEvidence}</p>
        <p className="small muted">{T.criteriaFooter}</p>
        <div className="row modal-foot" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  )
}

function MatchItem({ row, openOrders, manualOrders, value, busy, onChange, onConfirm, onRefundCheck, onHandled }: {
  row: MatchRow; openOrders: OrderBrief[]; value: string; busy: boolean; onChange: (v: string) => void; onConfirm: () => void
  /** 거래내역 없이 입금 확정한 주문: 이 입금 줄을 연결할 수 있다 (2026-10-26) */
  manualOrders: OrderBrief[]
  /** 취소된 주문의 입금자: 그 주문을 입금 확정 + 환불 필요로 표시 (2026-10-19) */
  onRefundCheck: (orderId: number) => void
  /** "이미 처리 완료된 입금"으로 옮기기 (2026-10-26) */
  onHandled: () => void
}) {
  // 후보가 있으면 후보를 먼저, 그 외 모든 미입금 주문(다른 입금에 추천된 주문 포함)도 고를 수 있게 한다
  const candidateIds = new Set(row.candidates.map((c) => c.order.orderId))
  const others = openOrders.filter((o) => !candidateIds.has(o.orderId))
  return (
    <div style={{ borderTop: '1px solid var(--line)', padding: '10px 0' }}>
      <div className="row between">
        <div>
          <b>{row.depositorName}</b> {won(row.amount)} <span className="muted small">{row.datetimeText ?? ''}</span>
        </div>
        <StatusBadge status={row.status} label={row.statusLabel} />
      </div>
      {row.evidence && <div className="small muted">근거: {row.evidence}</div>}
      {row.checkNote && <div className="small" style={{ color: 'var(--danger)' }}><span className="badge badge-CHECK">확인 필요</span> {row.checkNote}</div>}
      {row.note && <div className="small" style={{ color: 'var(--warn)' }}>⚠ {row.note}</div>}
      {row.refundCheckOrderId != null && (
        <div className="row mt">
          <button className="btn small" disabled={busy} onClick={() => onRefundCheck(row.refundCheckOrderId!)}>환불 필요로 표시</button>
          <span className="small muted">{T.refundCheckHint}</span>
        </div>
      )}
      <div className="row mt">
        <select value={value} onChange={(e) => onChange(e.target.value)} style={{ flex: 1, minWidth: 220 }}>
          <option value="">{row.status === 'AMBIGUOUS' ? '후보 중에서 고르세요' : '주문 고르기'}</option>
          {row.candidates.length > 0 && (
            <optgroup label="후보">
              {row.candidates.map((c) => (
                <option key={c.order.orderId} value={c.order.orderId}>
                  {orderLabel(c.order)} · {c.evidence}{c.amountDiff ? ` · 차이 ${c.amountDiff > 0 ? '+' : ''}${c.amountDiff}원` : ''}
                </option>
              ))}
            </optgroup>
          )}
          {others.length > 0 && (
            <optgroup label="다른 미입금 주문">
              {others.map((o) => <option key={o.orderId} value={o.orderId}>{orderLabel(o)}</option>)}
            </optgroup>
          )}
          {manualOrders.length > 0 && (
            <optgroup label="거래내역 없이 입금 확정한 주문에 연결">
              {manualOrders.map((o) => <option key={o.orderId} value={o.orderId}>{orderLabel(o)}</option>)}
            </optgroup>
          )}
        </select>
        <button className="btn small primary" disabled={!value || busy} onClick={onConfirm}>확정</button>
        <button className="btn small" disabled={busy} onClick={onHandled}>이미 처리 완료된 입금</button>
      </div>
    </div>
  )
}
