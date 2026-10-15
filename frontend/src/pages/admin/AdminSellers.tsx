import { useEffect, useState } from 'react'
import { api, errorMessage } from '../../api'
import { ErrorBox } from '../../components/Ui'
import type { Plan, Role } from '../../types'

interface Row {
  id: number
  email: string
  displayName: string
  role: Role
  plan: Plan
  trialEndsAt: string
  paidUntil: string | null
  expired: boolean
  suspended: boolean
  adminMemo: string | null
  createdAt: string
  groupBuyCount: number
  captureToday: number
  captureThisMonth: number
  dailyCaptureLimit: number
  customCaptureLimit: boolean
  /** 판매자별 댓글 주문 기능 허용 */
  commentFeatureEnabled: boolean
}

/** 슈퍼 관리자: 판매자 목록, 플랜(무료 체험/유료)·유료 만료일·사용 제한 수동 변경 */
export default function AdminSellers() {
  const [rows, setRows] = useState<Row[]>([])
  const [editing, setEditing] = useState<Record<number, Row>>({})
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<'all' | 'expired' | 'suspended'>('all')
  /** 댓글 주문 기능 전체 공개 (꺼져 있으면 아래에서 허용한 판매자만 씀) */
  const [commentOpen, setCommentOpen] = useState<boolean | null>(null)

  const load = () => api.get<Row[]>('/api/admin/sellers').then(setRows).catch((e) => setError(errorMessage(e)))
  useEffect(() => {
    load()
    api.get<{ commentFeatureOpen: boolean }>('/api/admin/settings').then((s) => setCommentOpen(s.commentFeatureOpen)).catch((e) => setError(errorMessage(e)))
  }, [])

  async function toggleCommentOpen(open: boolean) {
    const text = open
      ? '댓글 주문 기능을 모든 판매자에게 엽니다. 계속할까요?'
      : '댓글 주문 기능 전체 공개를 끕니다. 아래에서 따로 허용한 판매자만 쓸 수 있습니다. 계속할까요?'
    if (!confirm(text)) return
    setError(null)
    try {
      const s = await api.put<{ commentFeatureOpen: boolean }>('/api/admin/settings', { commentFeatureOpen: open })
      setCommentOpen(s.commentFeatureOpen)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function save(r: Row) {
    setError(null)
    try {
      await api.put(`/api/admin/sellers/${r.id}`, {
        plan: r.plan, paidUntil: r.paidUntil || null, trialEndsAt: r.trialEndsAt || null, suspended: r.suspended, adminMemo: r.adminMemo,
        // 기본값을 쓰던 판매자는 제한을 바꾸지 않았으면 그대로 기본값(null) 유지
        dailyCaptureLimit: r.customCaptureLimit ? r.dailyCaptureLimit : null,
        commentFeatureEnabled: r.commentFeatureEnabled,
      })
      setEditing((e) => {
        const next = { ...e }
        delete next[r.id]
        return next
      })
      load()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function remove(r: Row) {
    if (!confirm(`${r.email} 판매자를 탈퇴 처리합니다. 이 판매자의 공구·주문·입금 정보가 모두 삭제됩니다. 계속할까요?`)) return
    try {
      await api.del(`/api/admin/sellers/${r.id}`)
      load()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  const shown = rows.filter((r) => filter === 'all' || (filter === 'expired' ? r.expired : r.suspended))

  return (
    <div>
      <h1>판매자 관리</h1>
      <p className="muted small">캡처(비전 AI) 하루 제한: 판매자별로 바꿀 수 있습니다. 비우면 기본값을 씁니다. 날짜는 한국 시간 기준입니다.</p>
      <p className="muted small">판매자가 이용료를 입금하면 플랜을 "유료"로 바꾸고 유료 만료일을 넣으세요. 기간이 끝나도 자동으로 막지 않습니다. 막아야 하면 "사용 제한"을 켜세요 (조회만 가능, 고객 주문은 계속 받음).</p>
      <div className="card">
        <div className="row between">
          <div>
            <b>댓글 주문 기능</b> <span className="small muted">(댓글 캡처·집계·주문 반영, 공구의 "주문 링크 + 댓글")</span>
            <div className="small muted">
              {commentOpen === null ? '불러오는 중…' : commentOpen
                ? '모든 판매자에게 열려 있습니다.'
                : '닫혀 있습니다. 아래 표에서 "댓글 기능"을 체크한 판매자만 씁니다. 주문 링크·입금 대조는 상관없이 씁니다.'}
            </div>
          </div>
          {commentOpen !== null && (
            <label className="check" style={{ whiteSpace: 'nowrap' }}>
              <input type="checkbox" checked={commentOpen} onChange={(e) => toggleCommentOpen(e.target.checked)} /> 전체 공개
            </label>
          )}
        </div>
      </div>
      <div className="row mb">
        <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)} style={{ width: 200 }}>
          <option value="all">전체 ({rows.length})</option>
          <option value="expired">기간 만료 ({rows.filter((r) => r.expired).length})</option>
          <option value="suspended">사용 제한 ({rows.filter((r) => r.suspended).length})</option>
        </select>
      </div>
      <ErrorBox message={error} />
      <div className="table-wrap">
        <table className="stack admin-table">
          <thead>
            <tr><th>판매자</th><th>플랜</th><th>무료 체험 끝</th><th>유료 만료일</th><th>상태</th><th>캡처 (오늘/이번 달 · 하루 제한)</th><th>메모</th><th></th></tr>
          </thead>
          <tbody>
            {shown.map((row) => {
              const r = editing[row.id] ?? row
              const isEditing = Boolean(editing[row.id])
              const update = (patch: Partial<Row>) => setEditing((e) => ({ ...e, [row.id]: { ...r, ...patch } }))
              return (
                <tr key={row.id}>
                  <td className="stack-title">{row.displayName}<div className="muted small">{row.email}{row.role === 'ADMIN' && ' (관리자)'}</div>
                    <div className="muted small">가입 {row.createdAt.substring(0, 10)} · 공구 {row.groupBuyCount}개</div></td>
                  <td data-label="플랜">
                    <select value={r.plan} onChange={(e) => update({ plan: e.target.value as Plan })} disabled={row.role === 'ADMIN'} style={{ minWidth: 120 }}>
                      <option value="TRIAL">무료 체험</option>
                      <option value="PAID">유료</option>
                    </select>
                  </td>
                  <td data-label="무료 체험 끝"><input type="date" className="date-compact" value={r.trialEndsAt.substring(0, 10)} onChange={(e) => {
                    // 날짜를 지우면 'T23:59:59' 만 보내져 저장이 400 으로 실패하므로 무시한다 (무료 체험 종료일은 필수)
                    if (e.target.value) update({ trialEndsAt: e.target.value + 'T23:59:59' })
                  }} disabled={row.role === 'ADMIN'} /></td>
                  <td data-label="유료 만료일"><input type="date" className="date-compact" value={r.paidUntil ?? ''} onChange={(e) => update({ paidUntil: e.target.value })} disabled={row.role === 'ADMIN'} /></td>
                  <td data-label="상태">
                    {row.expired && <div className="badge badge-CLOSED">만료</div>}
                    <label className="check small" style={{ whiteSpace: 'nowrap' }}><input type="checkbox" checked={r.suspended} onChange={(e) => update({ suspended: e.target.checked })} disabled={row.role === 'ADMIN'} /> 사용 제한</label>
                    <label className="check small" style={{ whiteSpace: 'nowrap' }} title="전체 공개가 꺼져 있을 때 이 판매자만 댓글 주문 기능을 쓰게 함">
                      <input type="checkbox" checked={r.commentFeatureEnabled} onChange={(e) => update({ commentFeatureEnabled: e.target.checked })} disabled={row.role === 'ADMIN'} /> 댓글 기능
                    </label>
                  </td>
                  <td data-label="캡처">
                    <div className="small" style={{ whiteSpace: 'nowrap' }}>오늘 {row.captureToday} / 이번 달 {row.captureThisMonth}장</div>
                    <input type="number" min={0} style={{ width: 110 }} aria-label="캡처 하루 제한"
                      value={r.customCaptureLimit ? r.dailyCaptureLimit : ''}
                      placeholder="기본값"
                      onChange={(e) => update(e.target.value === ''
                        ? { customCaptureLimit: false }
                        : { customCaptureLimit: true, dailyCaptureLimit: Number(e.target.value) })} />
                  </td>
                  <td data-label="메모"><input type="text" value={r.adminMemo ?? ''} onChange={(e) => update({ adminMemo: e.target.value })} placeholder="입금 기록 등" style={{ minWidth: 120 }} /></td>
                  <td className="stack-action">
                    {isEditing && <button className="btn small primary" onClick={() => save(r)}>저장</button>}
                    {row.role !== 'ADMIN' && <button className="btn small danger" onClick={() => remove(row)}>탈퇴</button>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
