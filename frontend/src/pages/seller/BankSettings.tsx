import { useEffect, useState, type FormEvent } from 'react'
import { api, dateTime, errorMessage } from '../../api'
import { ErrorBox } from '../../components/Ui'
import { depositText as T } from '../../texts/deposit'
import type { Preset } from '../../types'

interface Defaults { date: string[]; name: string[]; amount: string[]; type: string[] }
interface Alias { id: number; depositorName: string; customerId: number; customerLabel: string; createdAt: string }
/** GET /api/seller/bank-settings: 화면에 필요한 세 가지를 한 번에 */
interface Settings { defaults: Defaults; presets: Preset[]; aliases: Alias[] }

/** 은행 거래내역 헤더(컬럼) 설정 (은행마다 헤더(컬럼)명이 달라서) + 저장된 입금자 별칭 관리 */
const emptyPreset = { name: '', nameColumn: '', amountColumn: '', dateColumn: '', typeColumn: '' }

export default function BankSettings() {
  const [defaults, setDefaults] = useState<Defaults | null>(null)
  const [presets, setPresets] = useState<Preset[]>([])
  const [aliases, setAliases] = useState<Alias[]>([])
  const [form, setForm] = useState(emptyPreset)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    api.get<Settings>('/api/seller/bank-settings')
      .then((s) => {
        setDefaults(s.defaults)
        setPresets(s.presets)
        setAliases(s.aliases)
      })
      .catch((e) => setError(errorMessage(e)))
  }
  useEffect(load, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await api.post('/api/seller/bank-columns/presets', form)
      setForm(emptyPreset)
      load()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function remove(url: string) {
    if (!confirm(T.confirmRemove)) return
    try {
      await api.del(url)
      load()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <div>
      <h1>은행 거래내역 헤더(컬럼) 설정</h1>
      <div className="card">
        <h3>자동 인식 기본값</h3>
        <p className="muted small">{T.defaultsHelp}</p>
        {defaults && (
          <div className="small">
            <div><b>입금자명</b>: {defaults.name.join(', ')}</div>
            <div><b>금액</b>: {defaults.amount.join(', ')}</div>
            <div><b>날짜</b>: {defaults.date.join(', ')}</div>
            <div><b>거래 유형</b>: {defaults.type.join(', ')}</div>
            <div className="muted mt">{T.amountOrderNote}</div>
            <div className="muted">{T.typeExcludeNote}</div>
          </div>
        )}
      </div>
      <form className="card" onSubmit={submit}>
        <h3>은행별 헤더(컬럼) 명칭 저장</h3>
        <div className="mapping-grid">
          <label className="field"><span>은행 이름 *</span><input type="text" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required placeholder="예: 신협" /></label>
          <label className="field"><span>입금자명 헤더(컬럼) *</span><input type="text" value={form.nameColumn} onChange={(e) => setForm({ ...form, nameColumn: e.target.value })} required /></label>
          <label className="field"><span>금액 헤더(컬럼) *</span><input type="text" value={form.amountColumn} onChange={(e) => setForm({ ...form, amountColumn: e.target.value })} required /></label>
          <label className="field"><span>날짜 헤더(컬럼)</span><input type="text" value={form.dateColumn} onChange={(e) => setForm({ ...form, dateColumn: e.target.value })} /></label>
          <label className="field"><span>거래 유형 헤더(컬럼)</span><input type="text" value={form.typeColumn} onChange={(e) => setForm({ ...form, typeColumn: e.target.value })} /></label>
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
        <p className="muted small">{T.aliasHelp}</p>
        <div className="table-wrap">
          <table className="stack">
            <thead><tr><th>입금자명</th><th>고객</th><th>저장</th><th></th></tr></thead>
            <tbody>
              {aliases.map((a) => (
                <tr key={a.id}><td className="stack-title">{a.depositorName}</td><td data-label="고객">{a.customerLabel}</td>
                  <td className="small" data-label="저장" style={{ whiteSpace: 'nowrap' }}>{dateTime(a.createdAt)}</td>
                  <td className="stack-action"><button className="btn small danger" onClick={() => remove(`/api/seller/aliases/${a.id}`)}>삭제</button></td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
