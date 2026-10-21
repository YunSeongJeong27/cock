import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import SellerLayout from './components/SellerLayout'
import Home from './pages/Home'
import Login from './pages/Login'
import Signup from './pages/Signup'
import Privacy from './pages/Privacy'
import Terms from './pages/Terms'
import GroupBuyList from './pages/seller/GroupBuyList'
import GroupBuyForm from './pages/seller/GroupBuyForm'
import GroupBuyDetail from './pages/seller/GroupBuyDetail'
import Profile from './pages/seller/Profile'
import BankSettings from './pages/seller/BankSettings'
import AdminSellers from './pages/admin/AdminSellers'
import PublicOrder from './pages/public/PublicOrder'
import type { ReactNode } from 'react'

function RequireLogin({ children, admin }: { children: ReactNode; admin?: boolean }) {
  const { me, loading } = useAuth()
  if (loading) return <div className="page-loading">불러오는 중…</div>
  if (!me) return <Navigate to="/login" replace />
  if (admin && me.role !== 'ADMIN') return <Navigate to="/seller" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />
      <Route path="/privacy" element={<Privacy />} />
      <Route path="/terms" element={<Terms />} />
      <Route path="/g/:token" element={<PublicOrder />} />
      <Route
        element={
          <RequireLogin>
            <SellerLayout />
          </RequireLogin>
        }
      >
        <Route path="/seller" element={<GroupBuyList />} />
        <Route path="/seller/group-buys/new" element={<GroupBuyForm />} />
        <Route path="/seller/group-buys/:id/edit" element={<GroupBuyForm />} />
        <Route path="/seller/group-buys/:id" element={<GroupBuyDetail />} />
        <Route path="/seller/profile" element={<Profile />} />
        <Route path="/seller/bank" element={<BankSettings />} />
        <Route
          path="/admin"
          element={
            <RequireLogin admin>
              <AdminSellers />
            </RequireLogin>
          }
        />
      </Route>
      <Route path="*" element={<div className="container"><h2>페이지를 찾을 수 없습니다.</h2></div>} />
    </Routes>
  )
}
