import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { login, logout, resolveUser } from '../auth'
import { createGateway } from '../api/gateway'
import { resolveAuth, defaultOrg } from '../lib/authState'

export const AuthContext = createContext(null)

// Dev fallback when signed out: the X-Tenant-Key owner service principal (pre-login behavior).
const DEFAULT_TENANT_KEY = 'acme-key'

/**
 * Holds the OIDC session (user + /api/me + active org) and exposes a single gateway client whose auth is read
 * live, so switching the active organization re-scopes every subsequent request without rebuilding anything.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [me, setMe] = useState(null)
  const [activeOrg, setActiveOrg] = useState('')
  // Dev escape hatch: only when explicitly enabled does the console act as the X-Tenant-Key owner. Never the
  // silent default — signed out and not in dev mode is anonymous, and the app gates it behind sign-in.
  const [devMode, setDevMode] = useState(false)

  // The gateway client reads the latest auth through this ref, so it never needs to be recreated.
  const authRef = useRef({ user: null, activeOrg: '', tenantKey: DEFAULT_TENANT_KEY, devMode: false })
  authRef.current = { user, activeOrg, tenantKey: DEFAULT_TENANT_KEY, devMode }
  const gateway = useMemo(() => createGateway({ getAuth: () => resolveAuth(authRef.current) }), [])

  const didInit = useRef(false)
  useEffect(() => {
    if (didInit.current) return // resolveUser() consumes a one-time OIDC callback; never run it twice
    didInit.current = true
    resolveUser().then(async (u) => {
      if (!u) return
      authRef.current = { ...authRef.current, user: u }
      setUser(u)
      try {
        const profile = await gateway.me()
        setMe(profile)
        setActiveOrg(defaultOrg(profile))
      } catch {
        /* the gateway may be running without the oidc profile; identity stays token-only */
      }
    })
  }, [gateway])

  const value = useMemo(
    () => ({
      user,
      me,
      activeOrg,
      setActiveOrg,
      gateway,
      devMode,
      // The console renders only when there is a real identity OR the dev owner key was explicitly chosen.
      authenticated: !!user || devMode,
      signIn: login,
      enableDevMode: () => setDevMode(true),
      signOut: () => {
        if (user) logout() // OIDC single-logout only when there is a session to end
        setUser(null)
        setMe(null)
        setActiveOrg('')
        setDevMode(false)
      },
    }),
    [user, me, activeOrg, gateway, devMode],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
