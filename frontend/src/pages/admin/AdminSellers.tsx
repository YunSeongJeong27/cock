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
}

/** 슈퍼 관리자: 판매자 목록, 플랜(무료 체험/유료)·유료 만료일·사용 제한 수동 변경 */
export default function AdminSellers() {
  const [rows, setRows] = useState<Row[]>([])
  const [editing, setEditing] = useState<Record<number, Row>>({})
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState<'all' | 'expired' | 'suspended'>('all')

  const load = () => api.get<Row[]>('/api/admin/sellers').then(setRows).catch((e) => setError(errorMessage(e)))
  useEffect(() => {
    load()
  }, [])

  async function save(r: Row) {
    setError(null)
    try {
      await api.put(`/api/admin/sellers/${r.id}`, {
        plan: r.plan, paidUntil: r.paidUntil || null, trialEndsAt: r.trialEndsAt || null, suspended: r.suspended, adminMemo: r.adminMemo,
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
      <p className="muted small">판매자가 이용료를 입금하면 플랜을 "유료"로 바꾸고 유료 만료일을 넣으세요. 기간이 끝나도 자동으로 막지 않습니다. 막아야 하면 "사용 제한"을 켜세요 (조회만 가능, 고객 주문은 계속 받음).</p>
      <div className="row mb">
        <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)} style={{ width: 200 }}>
          <option value="all">전체 ({rows.length})</option>
          <option value="expired">기간 만료 ({rows.filter((r) => r.expired).length})</option>
          <option value="suspended">사용 제한 ({rows.filter((r) => r.suspended).length})</option>
        </select>
      </div>
      <ErrorBox message={error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>판매자</th><th>가입일</th><th>공구</th><th>플랜</th><th>무료 체험 끝</th><th>유료 만료일</th><th>상태</th><th>메모</th><th></th></tr>
          </thead>
          <tbody>
            {shown.map((row) => {
              const r = editing[row.id] ?? row
              const isEditing = Boolean(editing[row.id])
              const update = (patch: Partial<Row>) => setEditing((e) => ({ ...e, [row.id]: { ...r, ...patch } }))
              return (
                <tr key={row.id}>
                  <td>{row.displayName}<div className="muted small">{row.email}{row.role === 'ADMIN' && ' (관리자)'}</div></td>
                  <td className="small">{row.createdAt.substring(0, 10)}</td>
                  <td>{row.groupBuyCount}</td>
                  <td>
                    <select value={r.plan} onChange={(e) => update({ plan: e.target.value as Plan })} disabled={row.role === 'ADMIN'}>
                      <option value="TRIAL">무료 체험</option>
                      <option value="PAID">유료</option>
                    </select>
                  </td>
                  <td><input type="date" value={r.trialEndsAt.substring(0, 10)} onChange={(e) => update({ trialEndsAt: e.target.value + 'T23:59:59' })} disabled={row.role === 'ADMIN'} /></td>
                  <td><input type="date" value={r.paidUntil ?? ''} onChange={(e) => update({ paidUntil: e.target.value })} disabled={row.role === 'ADMIN'} /></td>
                  <td>
                    {row.expired && <div className="badge badge-CLOSED">만료</div>}
                    <label className="check small"><input type="checkbox" checked={r.suspended} onChange={(e) => update({ suspended: e.target.checked })} disabled={row.role === 'ADMIN'} /> 사용 제한</label>
                  </td>
                  <td><input type="text" value={r.adminMemo ?? ''} onChange={(e) => update({ adminMemo: e.target.value })} placeholder="입금 기록 등" /></td>
                  <td>
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
