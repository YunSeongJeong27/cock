import { useEffect, useState, type FormEvent } from 'react'
import { api, dateTime, errorMessage } from '../../api'
import { ErrorBox } from '../../components/Ui'
import type { Preset } from '../../types'

interface Defaults { date: string[]; name: string[]; amount: string[]; type: string[] }
interface Alias { id: number; depositorName: string; customerKey: string; customerLabel: string; createdAt: string }

/** 은행 거래내역 열 설정 (은행마다 열 이름이 달라서) + 저장된 입금자 별칭 관리 */
export default function BankSettings() {
  const [defaults, setDefaults] = useState<Defaults | null>(null)
  const [presets, setPresets] = useState<Preset[]>([])
  const [aliases, setAliases] = useState<Alias[]>([])
  const [form, setForm] = useState({ name: '', nameColumn: '', amountColumn: '', dateColumn: '', typeColumn: '' })
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    api.get<Defaults>('/api/seller/bank-columns/defaults').then(setDefaults).catch(() => undefined)
    api.get<Preset[]>('/api/seller/bank-columns/presets').then(setPresets).catch((e) => setError(errorMessage(e)))
    api.get<Alias[]>('/api/seller/aliases').then(setAliases).catch(() => undefined)
  }
  useEffect(load, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await api.post('/api/seller/bank-columns/presets', form)
      setForm({ name: '', nameColumn: '', amountColumn: '', dateColumn: '', typeColumn: '' })
      load()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function remove(url: string) {
    if (!confirm('삭제할까요?')) return
    try {
      await api.del(url)
      load()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <div>
      <h1>은행 거래내역 열 설정</h1>
      <div className="card">
        <h3>기본값 (자동 인식)</h3>
        <p className="muted small">거래내역 파일 위쪽 30줄에서 아래 이름의 열을 찾습니다 (띄어쓰기 무시, 정확히 같아야 함). 은행 파일의 열 이름이 다르면 자동 인식이 안 되므로, 아래에서 은행별로 열 이름을 저장해 두거나 올릴 때 직접 지정하세요.</p>
        {defaults && (
          <div className="small">
            <div><b>입금자명</b>: {defaults.name.join(', ')}</div>
            <div><b>금액</b>: {defaults.amount.join(', ')} <span className="muted">(앞에서부터 먼저 찾음. "거래금액"은 부호 있는 금액, 양수만 입금)</span></div>
            <div><b>날짜</b>: {defaults.date.join(', ')}</div>
            <div><b>거래 유형</b>: {defaults.type.join(', ')} <span className="muted">(값에 "이자"가 있으면 제외)</span></div>
          </div>
        )}
      </div>
      <form className="card" onSubmit={submit}>
        <h3>은행별 열 이름 저장</h3>
        <div className="mapping-grid">
          <label className="field"><span>이름 (예: 신협) *</span><input type="text" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
          <label className="field"><span>입금자명 열 *</span><input type="text" value={form.nameColumn} onChange={(e) => setForm({ ...form, nameColumn: e.target.value })} required /></label>
          <label className="field"><span>금액 열 *</span><input type="text" value={form.amountColumn} onChange={(e) => setForm({ ...form, amountColumn: e.target.value })} required /></label>
          <label className="field"><span>날짜 열</span><input type="text" value={form.dateColumn} onChange={(e) => setForm({ ...form, dateColumn: e.target.value })} /></label>
          <label className="field"><span>거래 유형 열</span><input type="text" value={form.typeColumn} onChange={(e) => setForm({ ...form, typeColumn: e.target.value })} /></label>
        </div>
        <ErrorBox message={error} />
        <button className="btn primary">저장</button>
        {presets.length > 0 && (
          <div className="table-wrap mt">
            <table className="stack">
              <thead><tr><th>이름</th><th>입금자명</th><th>금액</th><th>날짜</th><th>유형</th><th></th></tr></thead>
              <tbody>
                {presets.map((p) => (
                  <tr key={p.id}><td className="stack-title">{p.name}</td><td data-label="입금자명">{p.nameColumn}</td><td data-label="금액">{p.amountColumn}</td><td data-label="날짜">{p.dateColumn ?? '-'}</td><td data-label="유형">{p.typeColumn ?? '-'}</td>
                    <td className="stack-action"><button type="button" className="btn small danger" onClick={() => remove(`/api/seller/bank-columns/presets/${p.id}`)}>삭제</button></td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </form>
      <div className="card">
        <h3>저장된 입금자 별칭 ({aliases.length})</h3>
        <p className="muted small">입금을 확정하면 "입금자명 → 고객"이 저장되고, 다음 공구부터 같은 입금자명이면 자동으로 그 고객과 매칭합니다. 잘못 저장된 것은 지우세요.</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>입금자명</th><th>고객</th><th>저장</th><th></th></tr></thead>
            <tbody>
              {aliases.map((a) => (
                <tr key={a.id}><td>{a.depositorName}</td><td>{a.customerLabel}</td><td className="small">{dateTime(a.createdAt)}</td>
                  <td><button className="btn small danger" onClick={() => remove(`/api/seller/aliases/${a.id}`)}>삭제</button></td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
