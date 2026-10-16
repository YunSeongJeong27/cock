import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, dateTime, errorMessage, won } from '../../api'
import { useAuth } from '../../auth'
import { CopyButton, ErrorBox, Stat, StatusBadge } from '../../components/Ui'
import type { Dashboard, GroupBuy, Product, SellerOrder } from '../../types'
import OrdersTab from './OrdersTab'
import DepositTab from './DepositTab'
import PickupTab from './PickupTab'
import AnnounceTab from './AnnounceTab'
import CommentsTab from './CommentsTab'

type Tab = 'orders' | 'deposit' | 'pickup' | 'announce' | 'comments'

export default function GroupBuyDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { me } = useAuth()
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
  // 댓글 집계는 관리자가 댓글 주문 기능을 연 판매자의 "주문 링크 + 댓글" 공구에서만
  const comments = Boolean(me?.commentFeatureEnabled) && groupBuy.orderChannel === 'LINK_AND_COMMENT'
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
      <StockStatus products={groupBuy.products} />
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

/** 재고 알림 문구 (메일과 같은 문장) */
function stockHeadline(p: Product): string {
  const r = p.remaining ?? 0
  if (r > 0) return `'${p.name}' 재고가 ${r}개 남았습니다.`
  if (r === 0) return `'${p.name}' 준비 수량이 모두 주문됐습니다.`
  return `'${p.name}' 재고보다 ${-r}개 더 주문됐습니다.`
}

/**
 * 판매자용 상품별 재고 현황 + 알림 (2026-10-16 사용자 결정). 재고로 주문을 막지 않으므로 초과 수량까지 그대로 보여 주고,
 * 남은 수량이 알림 기준 이하이거나 초과되면 맨 위에 알린다.
 */
function StockStatus({ products }: { products: Product[] }) {
  const stocked = products.filter((p) => p.stockLimit != null)
  if (stocked.length === 0) return null
  const alerts = stocked.filter((p) => (p.remaining ?? 0) < 0
    || (p.stockAlertThreshold != null && p.remaining != null && p.remaining <= p.stockAlertThreshold))
  return (
    <>
      {alerts.length > 0 && (
        <div className="warn-box">
          {alerts.map((p) => <div key={p.id}><b>{stockHeadline(p)}</b></div>)}
          <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>악용하는 고객이 있을 수 있으니 계좌 입금내역 대조를 통해 확인하십시오.</div>
        </div>
      )}
      <div className="card">
        <div className="row between"><h3 style={{ margin: 0 }}>상품별 재고</h3><span className="small muted">준비 수량을 넘어도 주문은 받습니다</span></div>
        <div className="table-wrap mt">
          <table className="stack">
            <thead><tr><th>상품</th><th className="right">준비</th><th className="right">주문</th><th className="right">남은 수량</th><th className="right">알림 기준</th></tr></thead>
            <tbody>
              {stocked.map((p) => {
                const r = p.remaining ?? 0
                const alert = r < 0 || (p.stockAlertThreshold != null && r <= p.stockAlertThreshold)
                return (
                  <tr key={p.id}>
                    <td className="stack-title"><b>{p.name}</b></td>
                    <td className="right" data-label="준비">{p.stockLimit}</td>
                    <td className="right" data-label="주문">{p.sold}</td>
                    <td className="right" data-label="남은 수량" style={{ color: alert ? 'var(--danger)' : undefined, fontWeight: alert ? 700 : undefined }}>
                      {r < 0 ? `${r} (${-r}개 초과)` : r}
                    </td>
                    <td className="right" data-label="알림 기준">{p.stockAlertThreshold != null ? `${p.stockAlertThreshold}개 이하` : <span className="muted">없음</span>}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
