import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, dateTime, errorMessage, storageGet, storageSet, won } from '../../api'
import { useAuth } from '../../auth'
import { CopyButton, ErrorBox, Stat, StatusBadge } from '../../components/Ui'
import { groupBuyText as T } from '../../texts/groupBuy'
import type { Dashboard, GroupBuy, GroupBuyDetailResponse, Product, SellerOrder } from '../../types'
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
  const [tab, setTab] = useState<Tab>(() => (storageGet('dc_tab') as Tab) || 'orders')
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    try {
      const d = await api.get<GroupBuyDetailResponse>(`/api/seller/group-buys/${id}/detail`)
      setGroupBuy(d.groupBuy)
      setDashboard(d.dashboard)
      setOrders(d.orders)
    } catch (e) {
      setError(errorMessage(e))
    }
  }, [id])

  useEffect(() => {
    reload()
  }, [reload])

  function changeTab(t: Tab) {
    setTab(t)
    storageSet('dc_tab', t)
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
    if (!confirm(T.confirmDelete)) return
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
        <StatusBadge status={groupBuy.status} label={groupBuy.statusLabel} />
      </div>
      <div className="muted small mb">{groupBuy.closeAt ? `마감 ${dateTime(groupBuy.closeAt)}` : '마감 시각 없음 (직접 마감)'}</div>

      <div className="card">
        {groupBuy.status === 'DRAFT' ? (
          <div className="row between">
            <span>{T.draftNote}</span>
            <button className="btn primary" onClick={() => action('open')}>주문 받기 시작</button>
          </div>
        ) : (
          <>
            <div className="muted small">{T.linkHint}</div>
            <div className="row">
              <code className="grow" style={{ wordBreak: 'break-all' }}>{link}</code>
              <CopyButton text={link} label="링크 복사" />
              <a className="btn small" href={link} target="_blank" rel="noreferrer">열어보기</a>
            </div>
          </>
        )}
        <div className="row mt">
          <Link className="btn small" to={`/seller/group-buys/${id}/edit`}>공구 수정</Link>
          {groupBuy.status === 'OPEN' && <button className="btn small" onClick={() => action('close', T.confirmClose)}>지금 마감</button>}
          {groupBuy.status === 'CLOSED' && <button className="btn small" onClick={() => action('open')}>다시 열기</button>}
          <a className="btn small" href={`/api/seller/group-buys/${id}/export.xlsx`}>주문 정리표 엑셀</a>
          <a className="btn small" href={`/api/seller/group-buys/${id}/export.xlsx?includeContact=true`}>엑셀 (연락처·배송지 포함)</a>
          <button className="btn small danger" onClick={remove}>공구 삭제</button>
        </div>
      </div>

      {groupBuy.status !== 'DRAFT' && <PauseCard key={groupBuy.id} groupBuy={groupBuy} onChange={setGroupBuy} />}

      {dashboard && (
        <div className="stats">
          <Stat label="주문" value={`${dashboard.orderCount}건`} />
          <Stat label="총 수량" value={dashboard.totalQuantity} />
          <Stat label="총 금액" value={won(dashboard.totalAmount)} />
          <Stat label="입금 확인" value={dashboard.paidCount} tone="ok" />
          <Stat label="미입금" value={dashboard.unpaidCount} tone={dashboard.unpaidCount ? 'warn' : undefined} />
          <Stat label="현장결제" value={dashboard.onsiteCount} />
          <Stat label="수령" value={`${dashboard.pickedUpCount}/${dashboard.orderCount}`} />
          {dashboard.refundRequestCount > 0 && <Stat label="환불 신청" value={dashboard.refundRequestCount} tone="warn" />}
          {dashboard.refundPendingCount > 0 && <Stat label="환불 필요" value={dashboard.refundPendingCount} tone="danger" />}
        </div>
      )}
      <StockStatus products={groupBuy.products} warning={groupBuy.stockAlertWarning} showAlert={me?.notifications.SCREEN_STOCK !== false} />
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

/**
 * 판매자용 상품별 재고 현황 + 알림 (2026-10-16 사용자 결정). 재고로 주문을 막지 않으므로 초과 수량까지 그대로 보여 주고,
 * 남은 수량이 알림 기준 이하이거나 초과되면 맨 위에 알린다. 알림 칸은 판매자가 알림 설정에서 끌 수 있다 (표는 그대로, 2026-10-19).
 */
function StockStatus({ products, warning, showAlert }: { products: Product[]; warning: string; showAlert: boolean }) {
  const stocked = products.filter((p) => p.stockLimit != null)
  if (stocked.length === 0) return null
  const alerts = stocked.filter((p) => p.stockAlert)
  return (
    <>
      {showAlert && alerts.length > 0 && (
        <div className="warn-box">
          {alerts.map((p) => <div key={p.id}><b>{p.stockAlert}</b></div>)}
          <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>{warning}</div>
        </div>
      )}
      <div className="card">
        <div className="row between"><h3 style={{ margin: 0 }}>상품별 재고</h3><span className="small muted">{T.soldOutToggleHint}</span></div>
        <div className="table-wrap mt">
          <table className="stack">
            <thead><tr><th>상품</th><th className="right">준비</th><th className="right">주문</th><th className="right">남은 수량</th><th className="right">알림 기준</th><th>다 팔리면</th></tr></thead>
            <tbody>
              {stocked.map((p) => {
                const r = p.remaining ?? 0
                const alert = p.stockAlert != null
                return (
                  <tr key={p.id}>
                    <td className="stack-title"><b>{p.name}</b></td>
                    <td className="right" data-label="준비">{p.stockLimit}</td>
                    <td className="right" data-label="주문">{p.sold}</td>
                    <td className="right" data-label="남은 수량" style={{ color: alert ? 'var(--danger)' : undefined, fontWeight: alert ? 700 : undefined }}>
                      {r < 0 ? `${r} (${-r}개 초과)` : r}
                    </td>
                    <td className="right" data-label="알림 기준">{p.stockAlertThreshold != null ? `${p.stockAlertThreshold}개 이하` : <span className="muted">없음</span>}</td>
                    <td data-label="다 팔리면">{p.soldOut ? <span className="badge badge-CANCELLED">품절 (주문 막는 중)</span> : p.blockWhenSoldOut ? '품절로 막음' : <span className="muted">계속 받음</span>}</td>
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

/**
 * 주문 잠시 멈춤 (2026-10-18 사용자 요청): 입금 대조 중에 고객의 새 주문·주문 수정·취소·환불 신청을 막고 판매자가 바로 푼다.
 * 고객 화면 문구·링크(밴드 글 등)는 판매자가 정하고, 풀어도 남겨서 다음에 다시 쓴다
 */
function PauseCard({ groupBuy, onChange }: { groupBuy: GroupBuy; onChange: (g: GroupBuy) => void }) {
  const [message, setMessage] = useState(groupBuy.pauseMessage ?? groupBuy.defaultPauseMessage)
  const [link, setLink] = useState(groupBuy.pauseLinkUrl ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(paused: boolean) {
    setBusy(true)
    setError(null)
    try {
      onChange(await api.put<GroupBuy>(`/api/seller/group-buys/${groupBuy.id}/pause`, { paused, message, linkUrl: link || null }))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const fields = (
    <>
      <label className="field mt"><span>고객 화면 문구</span>
        <textarea rows={2} maxLength={300} value={message} onChange={(e) => setMessage(e.target.value)} />
      </label>
      <label className="field"><span>링크 (선택, 밴드 공지 글 등)</span>
        <input type="url" placeholder="https://" value={link} onChange={(e) => setLink(e.target.value)} />
      </label>
    </>
  )

  if (groupBuy.paused) {
    return (
      <div className="card" style={{ borderColor: 'var(--warn)', background: 'var(--warn-weak)' }}>
        <div className="row between">
          <b>⏸ 고객 주문을 잠시 멈춘 상태입니다</b>
          <button className="btn primary" disabled={busy} onClick={() => save(false)}>다시 받기</button>
        </div>
        <div className="small mt">{T.pausedNote}</div>
        {fields}
        <button className="btn small" disabled={busy} onClick={() => save(true)}>문구 저장</button>
        <ErrorBox message={error} />
      </div>
    )
  }
  return (
    <div className="card">
      <div className="row between">
        <b>고객 주문 일시정지</b>
        <button className="btn small" disabled={busy} onClick={() => save(true)}>주문 정지</button>
      </div>
      <details className="small mt">
        <summary>멈추면 고객 화면에 보일 문구·링크</summary>
        {fields}
      </details>
      <ErrorBox message={error} />
    </div>
  )
}
