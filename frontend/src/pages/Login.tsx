import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api'
import { useAuth } from '../auth'
import { ErrorBox } from '../components/Ui'
import type { Me } from '../types'

export default function Login() {
  const { setMe } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const me = await api.post<Me>('/api/auth/login', { email, password })
      setMe(me)
      navigate(me.role === 'ADMIN' ? '/admin' : '/seller')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="container narrow">
      <h1 className="mt"><Link to="/" style={{ color: 'inherit' }}>📌 입금콕</Link> 로그인</h1>
      <form className="card" onSubmit={submit}>
        <label className="field"><span>이메일</span>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
        </label>
        <label className="field"><span>비밀번호</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoComplete="current-password" />
        </label>
        <ErrorBox message={error} />
        <button className="btn primary block" disabled={busy}>로그인</button>
      </form>
      <p className="muted">계정이 없나요? <Link to="/signup">회원가입</Link></p>
    </div>
  )
}
