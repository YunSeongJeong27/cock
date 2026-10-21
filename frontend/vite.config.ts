import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 중에는 Vite(5173)가 /api 요청을 Spring Boot(8080)로 넘긴다.
// 배포 때는 빌드 결과가 Spring Boot jar 안(static/)에 들어가 같은 주소에서 동작한다.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
