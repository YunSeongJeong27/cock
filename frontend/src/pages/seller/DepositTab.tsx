import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, dateTime, errorMessage, won } from '../../api'
import { ErrorBox, StatusBadge } from '../../components/Ui'
import { depositText as T } from '../../texts/deposit'
import { STATEMENT_TYPE_LABEL, type BankHeaderView, type GroupBuy, type MatchResponse, type MatchRow, type OrderBrief, type PreviewResponse, type StatementType, type UploadResponse, type UploadSummary } from '../../types'
import ScreenshotDeposits from './ScreenshotDeposits'
import TossFileGuide from './TossFileGuide'

interface Props { groupBuy: GroupBuy; reload: () => void }

/** 거래내역을 올릴 때 알림창으로도 띄우는 서버 오류 */
const ALERT_CODES = ['STATEMENT_HEADER_MISMATCH', 'STATEMENT_BANK_TYPE_MISSING', 'STATEMENT_PDF_NO_TEXT', 'STATEMENT_FILE_PASSWORD_REQUIRED', 'STATEMENT_FILE_PASSWORD_WRONG']

/** 고른 파일의 종류: .pdf 면 PDF, 나머지(엑셀·CSV)는 엑셀 */
function typeOf(file: File | null): StatementType {
  return file && file.name.toLowerCase().endsWith('.pdf') ? 'PDF' : 'EXCEL'
}

/** 같은 입금 계좌를 쓰는 다른 공구의 주문이면 앞에 공구 제목을 붙인다 (2026-10-27) */
function orderLabel(o: OrderBrief, groupBuyId: number) {
  const other = o.groupBuyId !== groupBuyId ? `[${o.groupBuyTitle}] ` : ''
  return `${other}${o.orderNo}번 ${o.nickname ? o.nickname + ' / ' : ''}${o.depositorName ?? ''} · 전화 뒷자리 ${o.phoneLast4 || '-'} · ${won(o.expectedAmount)}`
}

export default function DepositTab({ groupBuy, reload }: Props) {
  const [file, setFile] = useState<File | null>(null)
  const [banks, setBanks] = useState<BankHeaderView[]>([])
  const [bankName, setBankName] = useState('')
  /** 파일 종류 (2026-10-27): 파일을 고르면 맞춰지고 판매자가 바꿀 수도 있다 */
  const [fileType, setFileType] = useState<StatementType>('PDF')
  /** 암호 걸린 PDF·엑셀의 비밀번호 (2026-10-27). 서버에 저장하지 않고 올리면 비운다 */
  const [password, setPassword] = useState('')
  const [checked, setChecked] = useState<PreviewResponse | null>(null)
  const [match, setMatch] = useState<MatchResponse | null>(null)
  const [uploads, setUploads] = useState<UploadSummary[]>([])
  const [choice, setChoice] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [inputKey, setInputKey] = useState(0)
  const [showCriteria, setShowCriteria] = useState(false)
  const [showTossGuide, setShowTossGuide] = useState(false)
  const [showHandled, setShowHandled] = useState(false)
  const base = `/api/seller/group-buys/${groupBuy.id}/deposits`

  const loadMatch = useCallback(async () => {
    try {
      const m = await api.get<MatchResponse>(`${base}/match`)
      setMatch(m)
      setUploads(m.uploads)
      setBankName((current) => current || m.bankName)
      const initial: Record<number, string> = {}
      ;[...m.rows, ...m.otherRows].forEach((r) => {
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
    api.get<{ banks: BankHeaderView[] }>('/api/seller/bank-settings').then((s) => setBanks(s.banks)).catch(() => undefined)
  }, [loadMatch])

  function formData() {
    const fd = new FormData()
    fd.append('file', file as File)
    if (bankName) fd.append('bankName', bankName)
    fd.append('fileType', fileType)
    if (password) fd.append('password', password)
    return fd
  }

  /** 은행 헤더(컬럼) 설정이 파일과 안 맞을 때, 글자가 그림으로 된 PDF일 때는 알림창으로도 알린다 (2026-10-27 사용자 결정) */
  function fail(e: unknown) {
    const message = errorMessage(e)
    setError(message)
    if (e instanceof ApiError && e.code !== null && ALERT_CODES.includes(e.code)) alert(message)
  }

  async function preview(): Promise<PreviewResponse | null> {
    if (!file) return null
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const res = await api.post<PreviewResponse>(`${base}/preview`, formData())
      setChecked(res)
      return res
    } catch (e) {
      setChecked(null)
      fail(e)
      return null
    } finally {
      setBusy(false)
    }
  }

  async function upload() {
    if (!file) return
    // 거래내역서 계좌번호가 공구 입금 계좌와 다르면 올리기 전에 한 번 더 묻는다 (2026-10-27)
    const res = checked ?? await preview()
    if (!res) return
    if (res.account.check === 'DIFFERENT' && res.account.notice && !confirm(T.accountDifferentConfirm(res.account.notice))) return
    setBusy(true)
    setError(null)
    try {
      const saved = await api.post<UploadResponse>(`${base}/uploads`, formData())
      setNotice(T.uploaded(saved.savedCount, saved.duplicateCount))
      setChecked(null)
      setFile(null)
      setPassword('')
      setInputKey((k) => k + 1)
      await loadMatch()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
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
  const bankNames = [...new Set(banks.map((b) => b.bankName))]
  const bank = banks.find((b) => b.bankName === bankName && b.fileType === fileType)
  const parsed = checked?.parsed ?? null

  return (
    <div>
      <div className="card">
        <div className="row between">
          <h3>① 거래내역 엑셀·CSV·PDF 올리기</h3>
          <button type="button" className="btn small" onClick={() => setShowTossGuide(true)}>토스 모바일 파일다운 예시</button>
        </div>
        <p className="muted small">{T.uploadHelp}</p>
        {/* 2026-10-27 사용자 요청: 공구 말고 다른 거래가 섞인 계좌면 대조가 잘 안 될 수 있음을 강조 */}
        <div className="warn-box"><b>⚠ {T.mixedAccountWarning}</b></div>
        <p className="small">{T.visibleInfoTip}</p>
        <input key={inputKey} type="file" accept=".xlsx,.xls,.csv,.pdf" onChange={(e) => {
          const picked = e.target.files?.[0] ?? null
          setFile(picked)
          if (picked) setFileType(typeOf(picked))
          setChecked(null)
        }} />
        <div className="mt">
          <label className="field" style={{ maxWidth: 320 }}>
            <span>파일 비밀번호 (선택)</span>
            <input type="password" autoComplete="off" value={password} onChange={(e) => { setPassword(e.target.value); setChecked(null) }} />
          </label>
          <p className="small muted">{T.filePasswordHelp}</p>
        </div>
        <div className="mt">
          <div className="row" style={{ alignItems: 'flex-start' }}>
            <label className="field" style={{ width: 200 }}>
              <span>거래내역 은행</span>
              <select value={bankName} onChange={(e) => { setBankName(e.target.value); setChecked(null) }}>
                {bankNames.map((name) => <option key={name} value={name}>{name}</option>)}
              </select>
            </label>
            <label className="field" style={{ width: 160 }}>
              <span>파일 종류</span>
              <select value={fileType} onChange={(e) => { setFileType(e.target.value as StatementType); setChecked(null) }}>
                {(['PDF', 'EXCEL'] as StatementType[]).map((t) => <option key={t} value={t}>{STATEMENT_TYPE_LABEL[t]}</option>)}
              </select>
            </label>
          </div>
          {!bank && bankName && (
            <div className="warn-box small">{T.bankTypeMissing(bankName, STATEMENT_TYPE_LABEL[fileType])} <Link to="/seller/bank">은행 헤더(컬럼) 설정</Link></div>
          )}
          {bank && (
            <div className="small">
              <span className="chip">입금자명: {bank.values.nameColumn}</span>
              <span className="chip">입금액: {bank.values.amountColumn}</span>
              <span className="chip">거래일시: {bank.values.dateColumn}</span>
              {bank.values.accountLabel && <span className="chip">계좌번호: {bank.values.accountLabel}</span>}
              {bank.values.typeColumn && <span className="chip">거래 구분: {bank.values.typeColumn}</span>}
              <Link to="/seller/bank" className="small" style={{ marginLeft: 8 }}>은행 헤더(컬럼) 설정</Link>
            </div>
          )}
        </div>
        <div className="row mt">
          <button className="btn" disabled={!file || busy} onClick={preview}>미리보기</button>
          <button className="btn primary" disabled={!file || busy} onClick={upload}>올리기</button>
        </div>

        {checked && parsed && (
          <div className="mt">
            {checked.account.notice && (
              <div className="warn-box">{checked.account.check === 'DIFFERENT' ? <b>⚠ {checked.account.notice}</b> : checked.account.notice}</div>
            )}
            <div className="success-box">
              {T.columnsFound(checked.bankName, STATEMENT_TYPE_LABEL[checked.fileType], checked.fileType === 'PDF' ? null : parsed.headerRowNo)}
              <div className="small mt">{T.previewCounts(parsed.deposits.length, parsed.skippedRows, parsed.excludedRows, checked.duplicateCount)}</div>
            </div>
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
          {match.sharedGroupBuys.length > 0 && <div className="warn-box small">{T.sharedAccount(match.sharedGroupBuys)}</div>}
          <div className="row small mb">
            <StatusBadge status="MATCHED" label={`자동 매칭 ${match.matchedCount}`} />
            <StatusBadge status="SUGGESTED" label={`추천 ${match.suggestedCount}`} />
            <StatusBadge status="AMBIGUOUS" label={`후보 여러 명 ${match.ambiguousCount}`} />
            <StatusBadge status="UNRELATED" label={`관련 없는 입금 ${match.unrelatedCount}`} />
          </div>
          {match.rows.length === 0 && <p className="muted">{T.noDeposits}</p>}
          {match.rows.map((r) => (
            <MatchItem key={r.rowId} row={r} groupBuyId={groupBuy.id} openOrders={match.openOrders} manualOrders={match.manualOrders} value={choice[r.rowId] ?? ''} busy={busy}
              onHandled={() => setHandled(r, true)}
              onChange={(v) => setChoice({ ...choice, [r.rowId]: v })}
              onConfirm={() => choice[r.rowId] && confirmItems([{ rowId: r.rowId, orderId: Number(choice[r.rowId]) }])}
              onRefundCheck={(orderId) => confirmItems([{ rowId: r.rowId, orderId }])} />
          ))}
        </div>
      )}

      {match && match.otherRows.length > 0 && (
        <div className="card">
          <h3>다른 공구 주문으로 보이는 입금 ({match.otherRows.length})</h3>
          <p className="muted small">{T.otherRowsHelp}</p>
          {match.otherRows.map((r) => (
            <MatchItem key={r.rowId} row={r} groupBuyId={groupBuy.id} openOrders={match.openOrders} manualOrders={match.manualOrders} value={choice[r.rowId] ?? ''} busy={busy}
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
          {match.unpaid.map((o) => <div key={o.orderId} className="small" style={{ padding: '4px 0' }}>{orderLabel(o, groupBuy.id)} · 주문 {dateTime(o.orderedAt)}</div>)}
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
              <div className="muted">→ {u.order ? orderLabel(u.order, groupBuy.id) : `${u.orderNo}번`}</div>
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
      {showTossGuide && <TossFileGuide onClose={() => setShowTossGuide(false)} />}

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

function MatchItem({ row, groupBuyId, openOrders, manualOrders, value, busy, onChange, onConfirm, onRefundCheck, onHandled }: {
  row: MatchRow; groupBuyId: number; openOrders: OrderBrief[]; value: string; busy: boolean; onChange: (v: string) => void; onConfirm: () => void
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
  const mine = others.filter((o) => o.groupBuyId === groupBuyId)
  const otherGroupBuys = others.filter((o) => o.groupBuyId !== groupBuyId)
  const label = (o: OrderBrief) => orderLabel(o, groupBuyId)
  return (
    <div style={{ borderTop: '1px solid var(--line)', padding: '10px 0' }}>
      <div className="row between">
        <div>
          <b>{row.depositorName}</b> {won(row.amount)} <span className="muted small">{row.datetimeText ?? ''}</span>
        </div>
        <StatusBadge status={row.status} label={row.statusLabel} />
      </div>
      {(row.evidence || row.timeNote) && <div className="small muted">{[row.evidence && `근거: ${row.evidence}`, row.timeNote].filter(Boolean).join(' · ')}</div>}
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
                  {label(c.order)} · {c.evidence}{c.amountDiff ? ` · 차이 ${c.amountDiff > 0 ? '+' : ''}${c.amountDiff}원` : ''}
                </option>
              ))}
            </optgroup>
          )}
          {mine.length > 0 && (
            <optgroup label="다른 미입금 주문">
              {mine.map((o) => <option key={o.orderId} value={o.orderId}>{label(o)}</option>)}
            </optgroup>
          )}
          {otherGroupBuys.length > 0 && (
            <optgroup label="같은 계좌를 쓰는 다른 공구의 미입금 주문">
              {otherGroupBuys.map((o) => <option key={o.orderId} value={o.orderId}>{label(o)}</option>)}
            </optgroup>
          )}
          {manualOrders.length > 0 && (
            <optgroup label="거래내역 없이 입금 확정한 주문에 연결">
              {manualOrders.map((o) => <option key={o.orderId} value={o.orderId}>{label(o)}</option>)}
            </optgroup>
          )}
        </select>
        <button className="btn small primary" disabled={!value || busy} onClick={onConfirm}>확정</button>
        <button className="btn small" disabled={busy} onClick={onHandled}>이미 처리 완료된 입금</button>
      </div>
    </div>
  )
}
