import { useCallback, useEffect, useState } from 'react'
import { api, dateTime, errorMessage, won } from '../../api'
import { ErrorBox, StatusBadge } from '../../components/Ui'
import type { ColumnMapping, GroupBuy, MatchResponse, MatchRow, OrderBrief, ParsedStatement, Preset, UploadSummary } from '../../types'
import ScreenshotDeposits from './ScreenshotDeposits'

interface Props { groupBuy: GroupBuy; reload: () => void }

const emptyMapping: ColumnMapping = { dateColumn: '', nameColumn: '', amountColumn: '', typeColumn: '' }

function orderLabel(o: OrderBrief) {
  return `${o.orderNo}번 ${o.nickname ? o.nickname + ' / ' : ''}${o.depositorName ?? ''} (${o.phoneLast4 || '-'}) ${won(o.expectedAmount)}${o.depositReported ? ' · 입금신고' : ''}`
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
  const base = `/api/seller/group-buys/${groupBuy.id}/deposits`

  const loadMatch = useCallback(async () => {
    try {
      const [m, u] = await Promise.all([api.get<MatchResponse>(`${base}/match`), api.get<UploadSummary[]>(`${base}/uploads`)])
      setMatch(m)
      setUploads(u)
      const initial: Record<number, string> = {}
      m.rows.forEach((r) => {
        if (r.order) initial[r.rowId] = String(r.order.orderId)
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
      setNotice(`입금 ${res.savedCount}건을 올렸습니다.${res.duplicateCount ? ` (이미 올린 입금 ${res.duplicateCount}건은 건너뜀)` : ''}`)
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
    const name = prompt('이 열 설정의 이름 (예: 신협, 국민은행)')
    if (!name) return
    try {
      const p = await api.post<Preset>('/api/seller/bank-columns/presets', { name, ...mapping })
      setPresets((list) => [...list.filter((x) => x.id !== p.id), p])
      setNotice(`"${name}" 열 설정을 저장했습니다. 다음부터 목록에서 고르면 됩니다.`)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function confirmItems(items: { rowId: number; orderId: number }[]) {
    if (items.length === 0) return
    setError(null)
    try {
      const res = await api.post<{ confirmedCount: number; errors: string[] }>(`${base}/confirm`, { items })
      setNotice(`${res.confirmedCount}건 입금 확정했습니다.`)
      if (res.errors.length) setError(res.errors.join('\n'))
      await loadMatch()
      reload()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function deleteUpload(id: number) {
    if (!confirm('이 업로드의 입금 줄을 지울까요?')) return
    try {
      await api.del(`${base}/uploads/${id}`)
      loadMatch()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  const matched = match?.rows.filter((r) => r.status === 'MATCHED') ?? []

  return (
    <div>
      <div className="card">
        <h3>① 거래내역 올리기 (엑셀·CSV)</h3>
        <p className="muted small">은행 앱·인터넷뱅킹의 거래내역조회에서 엑셀로 받아 올려주세요. 토스뱅크는 발급할 때 '입금만 + 엑셀'로 받으면 편합니다. 원본 파일은 저장하지 않습니다.</p>
        <input key={inputKey} type="file" accept=".xlsx,.xls,.csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setParsed(null) }} />
        <div className="row mt">
          <select value={presetId} onChange={(e) => { setPresetId(e.target.value); setManual(false) }} style={{ width: 220 }}>
            <option value="">열 자동 인식</option>
            {presets.map((p) => <option key={p.id} value={p.id}>저장한 설정: {p.name}</option>)}
          </select>
          <label className="check"><input type="checkbox" checked={manual} onChange={(e) => { setManual(e.target.checked); setPresetId('') }} /> 열 이름 직접 지정</label>
        </div>
        {manual && (
          <div className="mt">
            <p className="small muted">은행마다 열 이름이 다릅니다. 파일의 열 이름을 그대로 적거나 목록에서 고르세요. (띄어쓰기는 무시)</p>
            <datalist id="column-options">{parsed?.columnOptions.map((c) => <option key={c} value={c} />)}</datalist>
            <div className="mapping-grid">
              <label className="field"><span>입금자명 열 *</span><input type="text" list="column-options" value={mapping.nameColumn ?? ''} onChange={(e) => setMapping({ ...mapping, nameColumn: e.target.value })} placeholder="예: 기재내용" /></label>
              <label className="field"><span>금액 열 *</span><input type="text" list="column-options" value={mapping.amountColumn ?? ''} onChange={(e) => setMapping({ ...mapping, amountColumn: e.target.value })} placeholder="예: 입금액" /></label>
              <label className="field"><span>날짜 열</span><input type="text" list="column-options" value={mapping.dateColumn ?? ''} onChange={(e) => setMapping({ ...mapping, dateColumn: e.target.value })} placeholder="예: 거래일시" /></label>
              <label className="field"><span>거래 유형 열</span><input type="text" list="column-options" value={mapping.typeColumn ?? ''} onChange={(e) => setMapping({ ...mapping, typeColumn: e.target.value })} placeholder="예: 구분" /></label>
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
                  {parsed.autoDetected ? '열을 자동으로 찾았습니다.' : '지정한 열 이름으로 읽었습니다.'} (헤더 {parsed.headerRowNo}행)
                  <div className="mt">
                    <span className="chip">입금자명: {parsed.mapping.nameColumn}</span>
                    <span className="chip">금액: {parsed.mapping.amountColumn}</span>
                    <span className="chip">날짜: {parsed.mapping.dateColumn || '없음'}</span>
                    <span className="chip">유형: {parsed.mapping.typeColumn || '없음'}</span>
                  </div>
                  <div className="small mt">입금 {parsed.deposits.length}건 · 건너뛴 줄 {parsed.skippedRows}개 (출금·0원·이자 등){duplicates ? ` · 이미 올린 입금 ${duplicates}건` : ''}</div>
                  <div className="small">열이 틀렸으면 "열 이름 직접 지정"으로 바꿔서 다시 미리보기 하세요.</div>
                </div>
                {manual && <button className="btn small" onClick={savePreset}>이 열 설정 저장 (은행별)</button>}
                <div className="table-wrap mt">
                  <table>
                    <thead><tr><th>행</th><th>입금자명</th><th className="right">금액</th><th>일시(원문)</th></tr></thead>
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
            {matched.length > 0 && (
              <button className="btn ok" onClick={() => confirmItems(matched.map((r) => ({ rowId: r.rowId, orderId: r.order!.orderId })))}>
                자동 매칭 {matched.length}건 모두 확정
              </button>
            )}
          </div>
          <p className="muted small">결과는 "확정"을 눌러야 저장됩니다. 확정하면 입금자명이 그 고객의 별칭으로 저장되어 다음 공구부터 자동 매칭에 쓰입니다.</p>
          <div className="row small mb">
            <StatusBadge status="MATCHED" label={`자동 매칭 ${match.matchedCount}`} />
            <StatusBadge status="SUGGESTED" label={`추천 ${match.suggestedCount}`} />
            <StatusBadge status="AMBIGUOUS" label={`후보 여러 명 ${match.ambiguousCount}`} />
            <StatusBadge status="UNRELATED" label={`관련 없는 입금 ${match.unrelatedCount}`} />
          </div>
          {match.rows.length === 0 && <p className="muted">대조할 입금이 없습니다. 거래내역을 올려주세요.</p>}
          {match.rows.map((r) => (
            <MatchItem key={r.rowId} row={r} openOrders={match.openOrders} value={choice[r.rowId] ?? ''}
              onChange={(v) => setChoice({ ...choice, [r.rowId]: v })}
              onConfirm={() => choice[r.rowId] && confirmItems([{ rowId: r.rowId, orderId: Number(choice[r.rowId]) }])} />
          ))}
        </div>
      )}

      {match && match.unpaid.length > 0 && (
        <div className="card">
          <h3>③ 입금이 안 보이는 주문 ({match.unpaid.length})</h3>
          {match.unpaid.map((o) => <div key={o.orderId} className="small" style={{ padding: '4px 0' }}>{orderLabel(o)} · 주문 {dateTime(o.orderedAt)}</div>)}
        </div>
      )}

      {uploads.length > 0 && (
        <div className="card">
          <h3>올린 파일</h3>
          {uploads.map((u) => (
            <div key={u.id} className="row between small" style={{ padding: '4px 0' }}>
              <span>{u.fileName ?? (u.source === 'SCREENSHOT' ? '스크린샷' : '')} · 입금 {u.rowCount}건 (확정 {u.usedCount}) · {dateTime(u.uploadedAt)}</span>
              <button className="btn small danger" onClick={() => deleteUpload(u.id)} disabled={u.usedCount > 0}>지우기</button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function MatchItem({ row, openOrders, value, onChange, onConfirm }: {
  row: MatchRow; openOrders: OrderBrief[]; value: string; onChange: (v: string) => void; onConfirm: () => void
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
      <div className="row mt">
        <select value={value} onChange={(e) => onChange(e.target.value)} style={{ flex: 1, minWidth: 220 }}>
          <option value="">{row.status === 'AMBIGUOUS' ? '후보 중에서 고르세요' : '주문 선택 (확정하지 않으려면 비워두기)'}</option>
          {row.candidates.length > 0 && (
            <optgroup label="후보">
              {row.candidates.map((c) => (
                <option key={c.order.orderId} value={c.order.orderId}>
                  {orderLabel(c.order)} · {c.evidence}{c.amountDiff ? ` (차이 ${c.amountDiff > 0 ? '+' : ''}${c.amountDiff}원)` : ''}
                </option>
              ))}
            </optgroup>
          )}
          {others.length > 0 && (
            <optgroup label="다른 미입금 주문">
              {others.map((o) => <option key={o.orderId} value={o.orderId}>{orderLabel(o)}</option>)}
            </optgroup>
          )}
        </select>
        <button className="btn small primary" disabled={!value} onClick={onConfirm}>확정</button>
      </div>
    </div>
  )
}
