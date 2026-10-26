import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { commonText } from '../texts/common'

export default function SellerLayout() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  // 관리자 표는 열이 많아 PC 에서 더 넓게 쓴다
  const path = useLocation().pathname
  // 관리자 화면과 공구 상세(주문 그리드)는 PC 에서 더 넓게
  const wider = path.startsWith('/admin') || /^\/seller\/group-buys\/\d+$/.test(path)
  return (
    <div>
      <header className={`topbar${wider ? ' wider' : ''}`}>
        <Link to="/seller" className="brand">📌 입금콕</Link>
        <nav>
          <NavLink to="/seller" end>공구</NavLink>
          <NavLink to="/seller/bank">은행 헤더(컬럼) 설정</NavLink>
          <NavLink to="/seller/profile">내 정보</NavLink>
          {me?.role === 'ADMIN' && <NavLink to="/admin">관리자</NavLink>}
          <button
            className="link"
            onClick={async () => {
              await logout()
              navigate('/login')
            }}
          >
            로그아웃
          </button>
        </nav>
      </header>
      {me?.suspended && (
        <div className="banner danger">{commonText.suspendedBanner}</div>
      )}
      {me && !me.suspended && me.expired && (
        <div className="banner warn">{commonText.expiredBanner(me.plan === 'TRIAL')}</div>
      )}
      <main className={`container wide${wider ? ' wider' : ''}`}>
        <Outlet />
      </main>
      <footer className="footer">
        <Link to="/privacy">개인정보처리방침</Link> · <Link to="/terms">이용약관</Link>
      </footer>
    </div>
  )
}
