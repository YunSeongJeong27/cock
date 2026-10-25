import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../../api'
import { useAuth } from '../../auth'
import { ErrorBox } from '../../components/Ui'
import { accountText as T } from '../../texts/account'
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
    const password = prompt(T.withdrawPrompt)
    if (!password) return
    try {
      await api.post('/api/seller/withdraw', { password })
      setMe(null)
      navigate('/')
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  // 폼은 로그인 정보를 처음 받았을 때만 채운다. 알림 체크박스도 로그인 정보(me)를 바꾸므로 me 가 바뀔 때마다 채우면
  // 저장 전에 고치던 이름·계좌가 저장된 값으로 되돌아갔음 (2026-10-21 브라우저로 재현)
  const meId = me?.id
  useEffect(() => {
    if (me) {
      setForm({ displayName: me.displayName, defaultBankName: me.defaultBankName ?? '', defaultAccountNumber: me.defaultAccountNumber ?? '', defaultAccountHolder: me.defaultAccountHolder ?? '' })
    }
  }, [meId])

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
          {/* 공구 만들기의 입금 계좌 칸과 같은 폭 규칙: 좁으면 예금주가 다음 줄로 */}
          <input type="text" placeholder="은행" value={form.defaultBankName} onChange={(e) => setForm({ ...form, defaultBankName: e.target.value })} style={{ flex: 1, minWidth: 100 }} />
          <input type="text" placeholder="계좌번호" value={form.defaultAccountNumber} onChange={(e) => setForm({ ...form, defaultAccountNumber: e.target.value })} style={{ flex: 2, minWidth: 160 }} />
          <input type="text" placeholder="예금주" value={form.defaultAccountHolder} onChange={(e) => setForm({ ...form, defaultAccountHolder: e.target.value })} style={{ flex: 1, minWidth: 100 }} />
        </div>
        <ErrorBox message={error} />
        {saved && <div className="success-box">저장했습니다.</div>}
        <button className="btn primary mt">저장</button>
      </form>
      <div className="card">
        <h3>알림 설정</h3>
        <div className="muted small">{T.notifyHelp}</div>
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
        <div className="muted small mt">{T.screenNotifyNote}</div>
        <ErrorBox message={notifyError} />
      </div>
      {me.role !== 'ADMIN' && (
        <div className="card">
          <h3>탈퇴</h3>
          <p className="muted small">{T.withdrawNote}</p>
          <button className="btn danger" onClick={withdraw}>탈퇴하기</button>
        </div>
      )}
    </div>
  )
}
