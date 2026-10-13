import { useEffect, useState, type FormEvent } from 'react'
import { api, errorMessage } from '../../api'
import { useAuth } from '../../auth'
import { ErrorBox } from '../../components/Ui'
import type { Me } from '../../types'

export default function Profile() {
  const { me, setMe } = useAuth()
  const [form, setForm] = useState({ displayName: '', defaultBankName: '', defaultAccountNumber: '', defaultAccountHolder: '' })
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (me) {
      setForm({ displayName: me.displayName, defaultBankName: me.defaultBankName ?? '', defaultAccountNumber: me.defaultAccountNumber ?? '', defaultAccountHolder: me.defaultAccountHolder ?? '' })
    }
  }, [me])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setSaved(false)
    try {
      setMe(await api.put<Me>('/api/seller/profile', form))
      setSaved(true)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  if (!me) return null
  return (
    <div>
      <h1>내 정보</h1>
      <div className="card">
        <div><b>{me.email}</b></div>
        <div className="muted small">
          플랜: {me.plan === 'TRIAL' ? `무료 체험 (~${me.trialEndsAt.substring(0, 10)})` : `유료 (~${me.paidUntil ?? '-'})`}
          {me.expired && <span style={{ color: 'var(--danger)' }}> · 기간 만료</span>}
        </div>
      </div>
      <form className="card" onSubmit={submit}>
        <label className="field"><span>판매자 이름</span>
          <input type="text" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} required />
        </label>
        <h3>기본 입금 계좌 (새 공구에 자동으로 채움)</h3>
        <div className="row">
          <input type="text" placeholder="은행" value={form.defaultBankName} onChange={(e) => setForm({ ...form, defaultBankName: e.target.value })} style={{ flex: 1 }} />
          <input type="text" placeholder="계좌번호" value={form.defaultAccountNumber} onChange={(e) => setForm({ ...form, defaultAccountNumber: e.target.value })} style={{ flex: 2 }} />
          <input type="text" placeholder="예금주" value={form.defaultAccountHolder} onChange={(e) => setForm({ ...form, defaultAccountHolder: e.target.value })} style={{ flex: 1 }} />
        </div>
        <ErrorBox message={error} />
        {saved && <div className="success-box">저장했습니다.</div>}
        <button className="btn primary mt">저장</button>
      </form>
    </div>
  )
}
