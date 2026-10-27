import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../auth'

export default function Home() {
  const { me, loading } = useAuth()
  if (!loading && me) return <Navigate to="/seller" replace />
  return (
    <div>
      <div className="hero">
        <h1>📌 입금콕</h1>
        <p>계좌 입금내역만 올리면 입금자 콕! 공구 장부 한 번에</p>
        <div className="row" style={{ justifyContent: 'center' }}>
          <Link className="btn primary" to="/signup">무료로 시작하기</Link>
          <Link className="btn" to="/login">로그인</Link>
        </div>
        <div className="promo">가입 후 조건 없이 첫 달 무료</div>
        <p className="small">무료 기간이 끝나도 자동 결제는 없으니 안심하세요!</p>
      </div>
      <div className="container">
        <div className="feature-grid">
          <div className="card">
            <h3>① 주문서 링크</h3>
            <p className="muted">밴드·카톡 글에 링크 하나만 붙이세요. 고객은 로그인 없이 주문하고 내 주문 상태도 확인합니다.</p>
          </div>
          <div className="card">
            <h3>② 입금 대조</h3>
            <p className="muted">은행 앱에서 받은 거래내역 엑셀을 올리면 입금자와 주문을 맞춰 줍니다. 확정은 판매자가 눌러요.</p>
          </div>
          <div className="card">
            <h3>③ 픽업 체크·정리표</h3>
            <p className="muted">수령 체크, 미입금 경고, 주문 정리표 엑셀, 입금 확인 문구 복사까지 한 화면에서.</p>
          </div>
        </div>
      </div>
      <footer className="footer">
        <Link to="/privacy">개인정보처리방침</Link> · <Link to="/terms">이용약관</Link>
      </footer>
    </div>
  )
}
