import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../../api'
import { useAuth } from '../../auth'
import { ErrorBox } from '../../components/Ui'
import type { Me, NotificationType } from '../../types'

/** 알림 설정 (2026-10-19). 처음에는 꼭 필요한 것만 켜져 있다 (서버 NotificationType 기본값) */
const MAIL_ITEMS: { type: NotificationType; label: string; note: string }[] = [
  { type: 'MAIL_REFUND_REQUEST', label: '고객 환불 신청', note: '신청마다 1통' },
  { type: 'MAIL_STOCK', label: '재고 알림 기준 도달', note: '기준 이하가 될 때 1통' },
  { type: 'MAIL_NEW_ORDER', label: '새 주문', note: '주문마다 1통' },
  { type: 'MAIL_CUSTOMER_CHANGE', label: '고객 주문 수정', note: '수정마다 1통' },
  { type: 'MAIL_CUSTOMER_CANCEL', label: '고객 바로 취소', note: '취소마다 1통' },
  { type: 'MAIL_AUTO_CLOSE', label: '마감 시각 자동 마감', note: '공구마다 1통' },
]
const SCREEN_ITEMS: { type: NotificationType; label: string; note: string }[] = [
  { type: 'SCREEN_REFUND_REQUEST', label: '고객 환불 신청', note: '주문 목록 위에 표시' },
  { type: 'SCREEN_STOCK', label: '재고 알림 기준 도달', note: '공구 화면 위에 표시' },
  { type: 'SCREEN_SAME_NICKNAME', label: '같은 닉네임 중복 주문', note: '주문 목록 위에 표시 (링크 주문과 댓글 주문)' },
]

export default function Profile() {
  const { me, setMe } = useAuth()
  const [form, setForm] = useState({ displayName: '', defaultBankName: '', defaultAccountNumber: '', defaultAccountHolder: '' })
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [notifyError, setNotifyError] = useState<string | null>(null)
  const [notifySaving, setNotifySaving] = useState(false)
  const navigate = useNavigate()

  /** 체크하면 화면에 먼저 반영하고 바로 저장. 실패하면 되돌린다 */
  async function toggle(type: NotificationType, on: boolean) {
    if (!me) return
    setNotifyError(null)
    setNotifySaving(true)
    setMe({ ...me, notifications: { ...me.notifications, [type]: on } })
    try {
      setMe(await api.put<Me>('/api/seller/notifications', { notifications: { [type]: on } }))
    } catch (err) {
      setMe(me)
      setNotifyError(errorMessage(err))
    } finally {
      setNotifySaving(false)
    }
  }

  async function withdraw() {
    const password = prompt('탈퇴하면 모든 공구·주문·입금 정보가 삭제되고 되돌릴 수 없습니다. 필요한 주문 정리표는 먼저 내려받으세요.\n계속하려면 비밀번호를 입력하세요.')
    if (!password) return
    try {
      await api.post('/api/seller/withdraw', { password })
      setMe(null)
      navigate('/')
    } catch (err) {
      setError(errorMessage(err))
    }
  }

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
      <div className="card">
        <h3>알림 설정</h3>
        <div className="muted small">처음에는 꼭 필요한 알림만 켜져 있습니다. 체크하면 바로 저장됩니다.</div>
        <h4 className="mt">메일 ({me.email})</h4>
        {MAIL_ITEMS.map((item) => (
          <label key={item.type} className="check">
            <input type="checkbox" checked={me.notifications[item.type]} disabled={notifySaving}
              onChange={(e) => toggle(item.type, e.target.checked)} />
            <span>{item.label} <span className="muted small">— {item.note}</span></span>
          </label>
        ))}
        <h4 className="mt">화면 (노란 안내 칸)</h4>
        {SCREEN_ITEMS.map((item) => (
          <label key={item.type} className="check">
            <input type="checkbox" checked={me.notifications[item.type]} disabled={notifySaving}
              onChange={(e) => toggle(item.type, e.target.checked)} />
            <span>{item.label} <span className="muted small">— {item.note}</span></span>
          </label>
        ))}
        <div className="muted small mt">화면 안내를 꺼도 주문 목록의 표시와 숫자는 그대로 보입니다.</div>
        <ErrorBox message={notifyError} />
      </div>
      {me.role !== 'ADMIN' && (
        <div className="card">
          <h3>탈퇴</h3>
          <p className="muted small">탈퇴하면 판매자 정보와 받은 주문자·입금 정보가 모두 파기됩니다.</p>
          <button className="btn danger" onClick={withdraw}>탈퇴하기</button>
        </div>
      )}
    </div>
  )
}
