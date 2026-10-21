import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, dateTime, errorMessage, won } from '../../api'
import { ErrorBox, Rich, StatusBadge } from '../../components/Ui'
import { groupBuyText as T } from '../../texts/groupBuy'
import type { GroupBuySummary } from '../../types'

export default function GroupBuyList() {
  const [list, setList] = useState<GroupBuySummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.get<GroupBuySummary[]>('/api/seller/group-buys').then(setList).catch((e) => setError(errorMessage(e)))
  }, [])

  return (
    <div>
      <div className="row between mb">
        <h1>내 공구</h1>
        <Link className="btn primary" to="/seller/group-buys/new">+ 주문서 링크 만들기</Link>
      </div>
      <ErrorBox message={error} />
      {list && list.length === 0 && (
        <div className="card">
          <p>{T.emptyList}</p>
          <p className="muted"><Rich text={T.emptyListHint} /></p>
          <Link className="btn primary" to="/seller/group-buys/new">첫 주문서 링크 만들기</Link>
        </div>
      )}
      <div className="card-grid">
      {list?.map((g) => (
        <Link key={g.id} to={`/seller/group-buys/${g.id}`} className="list-item">
          <div className="card">
            <div className="row between">
              <h3 style={{ margin: 0 }}>{g.title}</h3>
              <StatusBadge status={g.status} label={g.statusLabel} />
            </div>
            <div className="muted small">
              {g.closeAt ? `마감 ${dateTime(g.closeAt)}` : '마감 시각 없음'} · 주문 {g.dashboard.orderCount}건 · {won(g.dashboard.totalAmount)}
            </div>
            <div className="row small mt">
              <span>입금 확인 <b>{g.dashboard.paidCount}</b></span>
              <span>미입금 <b style={{ color: g.dashboard.unpaidCount ? 'var(--warn)' : undefined }}>{g.dashboard.unpaidCount}</b></span>
              <span>수령 <b>{g.dashboard.pickedUpCount}</b></span>
              {g.paused && <span style={{ color: 'var(--warn)' }}><b>⏸ 주문 멈춤 중</b></span>}
              {g.dashboard.refundRequestCount > 0 && <span style={{ color: 'var(--warn)' }}>환불 신청 <b>{g.dashboard.refundRequestCount}</b></span>}
              {g.dashboard.refundPendingCount > 0 && <span style={{ color: 'var(--danger)' }}>환불 필요 <b>{g.dashboard.refundPendingCount}</b></span>}
            </div>
          </div>
        </Link>
      ))}
      </div>
    </div>
  )
}
