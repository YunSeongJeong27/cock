import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'

export default function SellerLayout() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  return (
    <div>
      <header className="topbar">
        <Link to="/seller" className="brand">📌 입금콕</Link>
        <nav>
          <NavLink to="/seller" end>공구</NavLink>
          <NavLink to="/seller/bank">은행 열 설정</NavLink>
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
        <div className="banner danger">관리자가 사용을 제한한 계정입니다. 조회만 할 수 있습니다. 문의: 관리자</div>
      )}
      {me && !me.suspended && me.expired && (
        <div className="banner warn">
          이용 기간이 끝났습니다{me.plan === 'TRIAL' ? ' (무료 체험 종료)' : ''}. 계속 쓰시려면 관리자에게 이용료를 입금해 주세요.
        </div>
      )}
      <main className="container">
        <Outlet />
      </main>
      <footer className="footer">
        <Link to="/privacy">개인정보처리방침</Link> · <Link to="/terms">이용약관</Link>
      </footer>
    </div>
  )
}
