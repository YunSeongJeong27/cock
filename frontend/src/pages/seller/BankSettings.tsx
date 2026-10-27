import { useEffect, useState, type FormEvent } from 'react'
import { api, dateTime, errorMessage } from '../../api'
import { ErrorBox } from '../../components/Ui'
import { depositText as T } from '../../texts/deposit'
import { STATEMENT_TYPE_LABEL, type BankHeaderValues, type BankHeaderView, type StatementType } from '../../types'

interface Alias { id: number; depositorName: string; customerId: number; customerLabel: string; createdAt: string }
/** GET /api/seller/bank-settings: 은행·파일 종류별 헤더(컬럼)명 + 입금자 별칭 */
interface Settings { banks: BankHeaderView[]; aliases: Alias[] }

/** 수정 칸: 은행 이름·파일 종류 + 헤더(컬럼)명. adding 이면 새로 추가 (2026-10-27) */
interface Form {
  adding: boolean; bankName: string; fileType: StatementType
  nameColumn: string; amountColumn: string; dateColumn: string; accountLabel: string; typeColumn: string
}

const SOURCE_LABEL: Record<BankHeaderView['source'], string> = { SYSTEM: '기본값', CHANGED: '바꾼 값', CUSTOM: '추가한 값' }

function toForm(bankName: string, fileType: StatementType, v: BankHeaderValues | null, adding: boolean): Form {
  return {
    adding, bankName, fileType,
    nameColumn: v?.nameColumn ?? '', amountColumn: v?.amountColumn ?? '', dateColumn: v?.dateColumn ?? '',
    accountLabel: v?.accountLabel ?? '', typeColumn: v?.typeColumn ?? '',
  }
}

/** 은행 거래내역 헤더(컬럼) 설정 (은행마다 헤더(컬럼)명이 달라서) + 저장된 입금자 별칭 관리 */
export default function BankSettings() {
  const [banks, setBanks] = useState<BankHeaderView[]>([])
  const [aliases, setAliases] = useState<Alias[]>([])
  const [form, setForm] = useState<Form | null>(null)
  const [showHelp, setShowHelp] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = () => {
    api.get<Settings>('/api/seller/bank-settings')
      .then((s) => {
        setBanks(s.banks)
        setAliases(s.aliases)
      })
      .catch((e) => setError(errorMessage(e)))
  }
  useEffect(load, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!form) return
    setError(null)
    try {
      const saved = await api.put<BankHeaderView>('/api/seller/bank-headers', form)
      setNotice(T.bankSaved(saved.bankName, STATEMENT_TYPE_LABEL[saved.fileType]))
      setForm(null)
      load()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  /** 판매자 값 지우기: 기본 은행은 원래 값으로, 추가한 은행은 목록에서 빠진다 */
  async function removeBank(b: BankHeaderView) {
    const type = STATEMENT_TYPE_LABEL[b.fileType]
    if (!confirm(b.source === 'CUSTOM' ? T.confirmRemoveBank(b.bankName, type) : T.confirmRevert(b.bankName, type))) return
    setError(null)
    try {
      await api.del(`/api/seller/bank-headers?bankName=${encodeURIComponent(b.bankName)}&fileType=${b.fileType}`)
      if (form?.bankName === b.bankName && form.fileType === b.fileType) setForm(null)
      load()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function removeAlias(id: number) {
    if (!confirm(T.confirmRemove)) return
    try {
      await api.del(`/api/seller/aliases/${id}`)
      load()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  const editing = form && !form.adding ? banks.find((b) => b.bankName === form.bankName && b.fileType === form.fileType) : undefined
  const bankNames = [...new Set(banks.map((b) => b.bankName))]
  const set = (key: keyof Form) => (e: { target: { value: string } }) => form && setForm({ ...form, [key]: e.target.value })

  return (
    <div>
      <h1>은행 거래내역 헤더(컬럼) 설정</h1>
      <div className="card">
        <div className="row between">
          <h3>은행 종류별 헤더(컬럼)명</h3>
          <button type="button" className="btn small" onClick={() => setShowHelp(true)}>거래내역 입금 헤더(컬럼)명 설정 방법</button>
        </div>
        <p className="muted small">{T.bankHeadersHelp}</p>
        <div className="table-wrap">
          <table className="stack">
            <thead><tr><th>은행</th><th>파일</th><th>입금자명</th><th>입금액</th><th>거래일시</th><th>계좌번호</th><th>거래 구분</th><th>상태</th><th></th></tr></thead>
            <tbody>
              {banks.map((b) => (
                <tr key={`${b.bankName}-${b.fileType}`}>
                  <td className="stack-title">{b.bankName}</td>
                  <td data-label="파일">{STATEMENT_TYPE_LABEL[b.fileType]}</td>
                  <td data-label="입금자명">{b.values.nameColumn}</td>
                  <td data-label="입금액">{b.values.amountColumn}</td>
                  <td data-label="거래일시">{b.values.dateColumn}</td>
                  <td data-label="계좌번호">{b.values.accountLabel ?? '-'}</td>
                  <td data-label="거래 구분">{b.values.typeColumn ?? '-'}</td>
                  <td data-label="상태" className="small">{SOURCE_LABEL[b.source]}</td>
                  <td className="stack-action">
                    <button type="button" className="btn small" onClick={() => { setNotice(null); setForm(toForm(b.bankName, b.fileType, b.values, false)) }}>수정</button>
                    {b.source === 'CHANGED' && <button type="button" className="btn small" onClick={() => removeBank(b)}>원래 값으로</button>}
                    {b.source === 'CUSTOM' && <button type="button" className="btn small danger" onClick={() => removeBank(b)}>삭제</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="row mt">
          <span className="small">{T.missingBank}</span>
          <button type="button" className="btn small primary" onClick={() => { setNotice(null); setForm(toForm('', 'PDF', null, true)) }}>사용 은행 추가</button>
        </div>
        {notice && <div className="success-box mt">{notice}</div>}
        {form && (
          <form className="mt" onSubmit={submit} style={{ borderTop: '1px solid var(--line)', paddingTop: 12 }}>
            <h3>{form.adding ? '사용 은행 추가' : `${form.bankName} ${STATEMENT_TYPE_LABEL[form.fileType]} 수정`}</h3>
            {form.adding && <p className="small muted">{T.addBankHint}</p>}
            <div className="mapping-grid">
              {form.adding && (
                <>
                  <label className="field"><span>은행 이름 *</span>
                    <input type="text" list="bank-names" value={form.bankName} onChange={set('bankName')} required placeholder="예: 신협" />
                    <datalist id="bank-names">{bankNames.map((n) => <option key={n} value={n} />)}</datalist>
                  </label>
                  <label className="field"><span>파일 종류 *</span>
                    <select value={form.fileType} onChange={set('fileType')}>
                      {(['PDF', 'EXCEL'] as StatementType[]).map((t) => <option key={t} value={t}>{STATEMENT_TYPE_LABEL[t]}</option>)}
                    </select>
                  </label>
                </>
              )}
              <label className="field"><span>입금자명 *</span><input type="text" value={form.nameColumn} onChange={set('nameColumn')} required /></label>
              <label className="field"><span>입금액 *</span><input type="text" value={form.amountColumn} onChange={set('amountColumn')} required /></label>
              <label className="field"><span>거래일시 *</span><input type="text" value={form.dateColumn} onChange={set('dateColumn')} required /></label>
              <label className="field"><span>계좌번호 (선택)</span><input type="text" value={form.accountLabel} onChange={set('accountLabel')} /></label>
              <label className="field"><span>거래 구분 (선택)</span><input type="text" value={form.typeColumn} onChange={set('typeColumn')} /></label>
            </div>
            <p className="small muted">{T.accountLabelHint}</p>
            <p className="small muted">{T.typeColumnHint}</p>
            {editing?.original && editing.source === 'CHANGED' && (
              <p className="small muted">
                원래 값: 입금자명 {editing.original.nameColumn} · 입금액 {editing.original.amountColumn} · 거래일시 {editing.original.dateColumn}
                {editing.original.accountLabel ? ` · 계좌번호 ${editing.original.accountLabel}` : ''}
                {editing.original.typeColumn ? ` · 거래 구분 ${editing.original.typeColumn}` : ''}
              </p>
            )}
            <ErrorBox message={error} />
            <div className="row">
              <button className="btn primary">저장</button>
              <button type="button" className="btn" onClick={() => setForm(null)}>닫기</button>
            </div>
          </form>
        )}
        {!form && <ErrorBox message={error} />}
      </div>
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
                  <td className="stack-action"><button className="btn small danger" onClick={() => removeAlias(a.id)}>삭제</button></td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {showHelp && <HowToModal onClose={() => setShowHelp(false)} />}
    </div>
  )
}

/** "거래내역 입금 헤더(컬럼)명 설정 방법" 창: 짧은 설명 + KB국민·토스뱅크 예시 그림 (2026-10-27 사용자 요청) */
function HowToModal({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal modal-wide modal-help" role="dialog" aria-modal="true" aria-labelledby="howto-title" onClick={(e) => e.stopPropagation()}>
        <h3 id="howto-title">거래내역 입금 헤더(컬럼)명 설정 방법</h3>
        <ol className="small howto-steps">
          {T.howToSteps.map((s) => <li key={s}>{s}</li>)}
        </ol>
        <p className="small"><b>{T.visibleInfoTip}</b></p>
        <h4>KB국민은행 PDF 예시</h4>
        <a href="/help/kb-header-example.png" target="_blank" rel="noreferrer">
          <img className="howto-image" src="/help/kb-header-example.png" alt="KB국민은행 거래내역 PDF에서 보낸분/받는분, 입금액, 거래일시, 계좌번호, 적요 표시" />
        </a>
        <h4>토스뱅크 PDF 예시</h4>
        <a href="/help/toss-header-example.png" target="_blank" rel="noreferrer">
          <img className="howto-image" src="/help/toss-header-example.png" alt="토스뱅크 거래내역 PDF에서 거래내용, 거래금액, 거래일자, 계좌번호, 구분 표시" />
        </a>
        <p className="small muted">{T.exampleNote}</p>
        <div className="row modal-foot" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn primary" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  )
}
