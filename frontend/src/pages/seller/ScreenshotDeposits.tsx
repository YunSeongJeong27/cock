import { useState } from 'react'
import { api, errorMessage, won } from '../../api'
import { ErrorBox } from '../../components/Ui'
import type { GroupBuy } from '../../types'

interface Extracted { name: string; amount: number; datetime: string | null; type: string | null; duplicateSuspected: boolean; imageIndex: number }
interface ExtractResponse { deposits: Extracted[]; skippedWithdrawals: number; provider: string; notice: string | null }

/**
 * 은행앱 거래내역 스크린샷 → 비전 AI 추출 → 검수(수정·삭제·추가) → 저장 (정리본 7장, M4)
 * 원본 이미지는 서버에 저장하지 않고 추출 직후 버린다.
 */
export default function ScreenshotDeposits({ groupBuy, onSaved }: { groupBuy: GroupBuy; onSaved: () => void }) {
  const [files, setFiles] = useState<FileList | null>(null)
  const [rows, setRows] = useState<Extracted[] | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [inputKey, setInputKey] = useState(0)
  const base = `/api/seller/group-buys/${groupBuy.id}/deposits`

  async function extract() {
    if (!files || files.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const fd = new FormData()
      Array.from(files).forEach((f) => fd.append('files', f))
      const res = await api.post<ExtractResponse>(`${base}/screenshots/extract`, fd)
      setRows(res.deposits)
      setInfo([res.notice, res.skippedWithdrawals ? `출금 ${res.skippedWithdrawals}건은 뺐습니다.` : null].filter(Boolean).join(' '))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    if (!rows) return
    setBusy(true)
    setError(null)
    try {
      await api.post(`${base}/screenshots/save`, {
        rows: rows.filter((r) => r.name.trim() && r.amount > 0).map((r) => ({ name: r.name, amount: r.amount, datetimeText: r.datetime })),
      })
      setRows(null)
      setFiles(null)
      setInputKey((k) => k + 1)
      setInfo('스크린샷 입금을 저장했습니다. 아래 대조 결과에 반영됩니다.')
      onSaved()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const update = (i: number, patch: Partial<Extracted>) => setRows((list) => list!.map((r, j) => (j === i ? { ...r, ...patch } : r)))

  return (
    <div className="card">
      <div className="row between">
        <h3>① - 2 은행앱 화면 캡처로 올리기</h3>
        <button className="btn small" onClick={() => setOpen(!open)}>{open ? '접기' : '펼치기'}</button>
      </div>
      {open && (
        <>
          <p className="muted small">폰에서 엑셀 받기가 번거로우면 은행앱 거래내역 화면을 캡처해서 여러 장 올리세요. AI가 입금자명·금액을 읽고, 직접 확인·수정한 뒤 저장합니다. 읽기 어려운 글자는 [?]로 표시됩니다. 캡처 이미지는 외부 AI 서비스로 전송되며 읽은 직후 삭제됩니다.</p>
          <input key={inputKey} type="file" accept="image/*" multiple onChange={(e) => setFiles(e.target.files)} />
          <div className="row mt"><button className="btn" disabled={!files || busy} onClick={extract}>{busy ? '읽는 중…' : '글자 읽기'}</button></div>
          <ErrorBox message={error} />
          {info && <div className="warn-box">{info}</div>}
          {rows && (
            <div className="mt">
              <div className="table-wrap">
                <table>
                  <thead><tr><th>캡처</th><th>입금자명</th><th>금액</th><th>일시</th><th></th></tr></thead>
                  <tbody>
                    {rows.map((r, i) => (
                      <tr key={i} style={{ background: r.duplicateSuspected ? 'var(--warn-weak)' : undefined }}>
                        <td className="small">{r.imageIndex + 1}{r.duplicateSuspected && <div style={{ color: 'var(--warn)' }}>중복 의심</div>}</td>
                        <td><input type="text" value={r.name} onChange={(e) => update(i, { name: e.target.value })} style={{ borderColor: r.name.includes('[?]') ? 'var(--danger)' : undefined }} /></td>
                        <td><input type="number" value={r.amount} onChange={(e) => update(i, { amount: Number(e.target.value) })} style={{ width: 110 }} /></td>
                        <td><input type="text" value={r.datetime ?? ''} onChange={(e) => update(i, { datetime: e.target.value })} style={{ width: 140 }} /></td>
                        <td><button className="btn small danger" onClick={() => setRows(rows.filter((_, j) => j !== i))}>삭제</button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="row mt">
                <button className="btn small" onClick={() => setRows([...rows, { name: '', amount: 0, datetime: null, type: '입금', duplicateSuspected: false, imageIndex: 0 }])}>+ 줄 추가</button>
                <span className="grow muted small">합계 {won(rows.reduce((s, r) => s + (r.amount || 0), 0))} / {rows.length}건</span>
                <button className="btn primary" disabled={busy || rows.some((r) => r.name.includes('[?]'))} onClick={save}>검수 완료, 저장</button>
              </div>
              {rows.some((r) => r.name.includes('[?]')) && <div className="small" style={{ color: 'var(--danger)' }}>[?] 표시를 고친 뒤 저장할 수 있습니다.</div>}
            </div>
          )}
        </>
      )}
    </div>
  )
}
