import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, dateTime, errorMessage, won } from '../../api'
import { CopyButton, ErrorBox, Stat, StatusBadge } from '../../components/Ui'
import type { Dashboard, GroupBuy, SellerOrder } from '../../types'
import OrdersTab from './OrdersTab'
import DepositTab from './DepositTab'
import PickupTab from './PickupTab'
import AnnounceTab from './AnnounceTab'
import CommentsTab from './CommentsTab'

type Tab = 'orders' | 'deposit' | 'pickup' | 'announce' | 'comments'

export default function GroupBuyDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [groupBuy, setGroupBuy] = useState<GroupBuy | null>(null)
  const [dashboard, setDashboard] = useState<Dashboard | null>(null)
  const [orders, setOrders] = useState<SellerOrder[]>([])
  const [tab, setTab] = useState<Tab>(() => (localStorageGet('dc_tab') as Tab) || 'orders')
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      const [g, d, o] = await Promise.all([
        api.get<GroupBuy>(`/api/seller/group-buys/${id}`),
        api.get<Dashboard>(`/api/seller/group-buys/${id}/dashboard`),
        api.get<SellerOrder[]>(`/api/seller/group-buys/${id}/orders`),
      ])
      setGroupBuy(g)
      setDashboard(d)
      setOrders(o)
    } catch (e) {
      setError(errorMessage(e))
    }
  }, [id])

  useEffect(() => {
    reload()
  }, [reload])

  function changeTab(t: Tab) {
    setTab(t)
    localStorageSet('dc_tab', t)
  }

  async function action(path: string, confirmText?: string) {
    if (confirmText && !confirm(confirmText)) return
    setError(null)
    try {
      await api.post(`/api/seller/group-buys/${id}/${path}`)
      reload()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function remove() {
    if (!confirm('공구를 삭제하면 주문·입금 정보도 모두 삭제됩니다. 필요하면 먼저 주문 정리표를 내려받으세요. 삭제할까요?')) return
    try {
      await api.del(`/api/seller/group-buys/${id}`)
      navigate('/seller')
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  if (!groupBuy) return <div><ErrorBox message={error} />{!error && <div className="page-loading">불러오는 중…</div>}</div>
  const comments = groupBuy.orderChannel === 'LINK_AND_COMMENT'
  const shownTab: Tab = tab === 'comments' && !comments ? 'orders' : tab
  const link = `${window.location.origin}/g/${groupBuy.publicToken}`

  return (
    <div>
      <div className="row between">
        <h1>{groupBuy.title}</h1>
        <StatusBadge status={groupBuy.status} label={groupBuy.status === 'DRAFT' ? '작성 중' : groupBuy.acceptingOrders ? '주문 받는 중' : '마감'} />
      </div>
      <div className="muted small mb">{groupBuy.closeAt ? `마감 ${dateTime(groupBuy.closeAt)}` : '마감 시각 없음 (직접 마감)'}</div>

      <div className="card">
        {groupBuy.status === 'DRAFT' ? (
          <div className="row between">
            <span>아직 주문을 받지 않는 상태입니다. 주문을 받으려면 시작을 누르세요.</span>
            <button className="btn primary" onClick={() => action('open')}>주문 받기 시작</button>
          </div>
        ) : (
          <>
            <div className="muted small">고객에게 보낼 주문서 링크 (밴드·카톡 글에 붙이세요)</div>
            <div className="row">
              <code className="grow" style={{ wordBreak: 'break-all' }}>{link}</code>
              <CopyButton text={link} label="링크 복사" />
              <a className="btn small" href={link} target="_blank" rel="noreferrer">열어보기</a>
            </div>
          </>
        )}
        <div className="row mt">
          <Link className="btn small" to={`/seller/group-buys/${id}/edit`}>공구 수정</Link>
          {groupBuy.status === 'OPEN' && <button className="btn small" onClick={() => action('close', '지금 마감할까요? 마감하면 고객이 주문·취소할 수 없습니다.')}>지금 마감</button>}
          {groupBuy.status === 'CLOSED' && <button className="btn small" onClick={() => action('open')}>다시 열기</button>}
          <a className="btn small" href={`/api/seller/group-buys/${id}/export.xlsx`}>주문 정리표 엑셀</a>
          <a className="btn small" href={`/api/seller/group-buys/${id}/export.xlsx?includeContact=true`}>엑셀 (연락처·배송지 포함)</a>
          <button className="btn small danger" onClick={remove}>공구 삭제</button>
        </div>
      </div>

      {dashboard && (
        <div className="stats">
          <Stat label="주문" value={`${dashboard.orderCount}건`} />
          <Stat label="총 수량" value={dashboard.totalQuantity} />
          <Stat label="총 금액" value={won(dashboard.totalAmount)} />
          <Stat label="입금 확인" value={dashboard.paidCount} tone="ok" />
          <Stat label="미입금" value={dashboard.unpaidCount} tone={dashboard.unpaidCount ? 'warn' : undefined} />
          <Stat label="고객 입금 신고" value={dashboard.depositReportedCount} />
          <Stat label="현장결제" value={dashboard.onsiteCount} />
          <Stat label="수령" value={`${dashboard.pickedUpCount}/${dashboard.orderCount}`} />
          {dashboard.refundPendingCount > 0 && <Stat label="환불 필요" value={dashboard.refundPendingCount} tone="danger" />}
        </div>
      )}
      <ErrorBox message={error} />

      {/* 주문 링크만 쓰는 공구는 댓글 집계를 숨긴다 (저장된 탭이 댓글이었으면 주문 목록으로) */}
      <div className="tabs">
        <button className={shownTab === 'orders' ? 'active' : ''} onClick={() => changeTab('orders')}>주문 목록</button>
        <button className={shownTab === 'deposit' ? 'active' : ''} onClick={() => changeTab('deposit')}>입금 대조</button>
        <button className={shownTab === 'pickup' ? 'active' : ''} onClick={() => changeTab('pickup')}>수령 체크</button>
        <button className={shownTab === 'announce' ? 'active' : ''} onClick={() => changeTab('announce')}>입금 확인 문구·닉네임</button>
        {comments && <button className={shownTab === 'comments' ? 'active' : ''} onClick={() => changeTab('comments')}>댓글 집계</button>}
      </div>
      {shownTab === 'orders' && <OrdersTab groupBuy={groupBuy} orders={orders} reload={reload} />}
      {shownTab === 'deposit' && <DepositTab groupBuy={groupBuy} reload={reload} />}
      {shownTab === 'pickup' && <PickupTab groupBuy={groupBuy} orders={orders} reload={reload} />}
      {shownTab === 'announce' && <AnnounceTab groupBuy={groupBuy} />}
      {shownTab === 'comments' && <CommentsTab groupBuy={groupBuy} reload={reload} />}
    </div>
  )
}

function localStorageGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function localStorageSet(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 저장 못 해도 화면은 동작한다
  }
}
