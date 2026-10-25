import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './auth'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* React Router 7 방식을 미리 켠다 (개발 중 콘솔에 매 화면 뜨던 경고 2개가 없어짐, 동작 차이 없음) */}
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
