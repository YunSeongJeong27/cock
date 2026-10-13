import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api'
import { useAuth } from '../auth'
import { ErrorBox } from '../components/Ui'
import type { Me } from '../types'

export default function Signup() {
  const { setMe } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ email: '', password: '', displayName: '' })
  const [agree, setAgree] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!agree) {
      setError('이용약관과 개인정보처리방침에 동의해 주세요.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const me = await api.post<Me>('/api/auth/signup', form)
      setMe(me)
      // 가입 직후 첫 화면은 "주문서 링크 만들기" (정리본 1장 제안)
      navigate('/seller/group-buys/new?welcome=1')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="container narrow">
      <h1 className="mt"><Link to="/" style={{ color: 'inherit' }}>📌 입금콕</Link> 회원가입</h1>
      <form className="card" onSubmit={submit}>
        <label className="field"><span>이메일</span>
          <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
        </label>
        <label className="field"><span>비밀번호 (8자 이상)</span>
          <input type="password" minLength={8} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required />
        </label>
        <label className="field"><span>판매자 이름 (고객 화면에 보임)</span>
          <input type="text" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} required placeholder="예: 달콤 과일가게" />
        </label>
        <label className="check">
          <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
          <span><Link to="/terms" target="_blank">이용약관</Link>과 <Link to="/privacy" target="_blank">개인정보처리방침</Link>에 동의합니다.</span>
        </label>
        <ErrorBox message={error} />
        <button className="btn primary block" disabled={busy}>가입하고 첫 달 무료로 시작</button>
      </form>
      <p className="muted">이미 계정이 있나요? <Link to="/login">로그인</Link></p>
    </div>
  )
}
