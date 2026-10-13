import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from './api'
import type { Me } from './types'

interface AuthState {
  me: Me | null
  loading: boolean
  refresh: () => Promise<void>
  setMe: (me: Me | null) => void
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState>(null as unknown as AuthState)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setMe(await api.get<Me>('/api/me'))
    } catch {
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    await api.post('/api/auth/logout')
    setMe(null)
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return <AuthContext.Provider value={{ me, loading, refresh, setMe, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  return useContext(AuthContext)
}
