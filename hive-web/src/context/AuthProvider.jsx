import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { login, logout, resolveUser, userManager } from '../auth'
import { createGateway } from '../api/gateway'
import { createDemoGateway, DEMO_USER, DEMO_ME } from '../api/demoGateway'
import { resolveAuth, defaultOrg } from '../lib/authState'

export const AuthContext = createContext(null)

// Dev fallback when signed out: the X-Tenant-Key owner service principal (pre-login behavior).
const DEFAULT_TENANT_KEY = 'acme-key'

// The static demo build (VITE_DEMO): everything is backed by an in-memory mock gateway and the session is a
// pre-seeded multi-org operator, so there is no IdP, no network, and the full console (not the solo subset)
// is shown. Resolved at build time, so a normal build tree-shakes the demo path away.
const DEMO = !!import.meta.env.VITE_DEMO

/**
 * Holds the OIDC session (user + /api/me + active org) and exposes a single gateway client whose auth is read
 * live, so switching the active organization re-scopes every subsequent request without rebuilding anything.
 */
export function AuthProvider({ children }) {
  const [user, setUser] = useState(DEMO ? DEMO_USER : null)
  const [me, setMe] = useState(DEMO ? DEMO_ME : null)
  const [activeOrg, setActiveOrg] = useState(DEMO ? DEMO_ME.organizations[0]?.tenantId || '' : '')
  // Dev escape hatch: only when explicitly enabled does the console act as the X-Tenant-Key owner. Never the
  // silent default — signed out and not in dev mode is anonymous, and the app gates it behind sign-in.
  const [devMode, setDevMode] = useState(false)
  // Deployment shape, learned once from the gateway on boot. Solo = a single-user, single-AP local gateway
  // (HIVEKEEPER_SOLO): no sign-in, no organizations — the gateway authorizes every request as the local owner.
  const [solo, setSolo] = useState(false)
  const [bootReady, setBootReady] = useState(false)

  // The gateway client reads the latest auth through this ref, so it never needs to be recreated.
  const authRef = useRef({ user: null, activeOrg: '', tenantKey: DEFAULT_TENANT_KEY, devMode: false })
  authRef.current = { user, activeOrg, tenantKey: DEFAULT_TENANT_KEY, devMode }
  const gateway = useMemo(
    () => (DEMO ? createDemoGateway() : createGateway({ getAuth: () => resolveAuth(authRef.current) })),
    [],
  )

  // Keep the session fresh: when oidc-client-ts silently renews the access token it emits userLoaded with the
  // new user — re-point the gateway client at it so subsequent calls carry the fresh token. If renewal ever
  // fails and the token expires, drop to the sign-in gate rather than silently 401ing every request.
  useEffect(() => {
    if (DEMO) return // no IdP in the demo build
    const onLoaded = (u) => {
      authRef.current = { ...authRef.current, user: u }
      setUser(u)
    }
    const onExpired = () => {
      setUser(null)
      setMe(null)
      setActiveOrg('')
    }
    userManager.events.addUserLoaded(onLoaded)
    userManager.events.addAccessTokenExpired(onExpired)
    return () => {
      userManager.events.removeUserLoaded(onLoaded)
      userManager.events.removeAccessTokenExpired(onExpired)
    }
  }, [])

  // One boot probe of the deployment shape. On failure we just assume a normal (non-solo) gateway. bootReady
  // gates the app's first render so it never flashes the sign-in page before learning it is in solo mode.
  useEffect(() => {
    let active = true
    gateway
      .mode()
      .then((m) => active && setSolo(!!m?.solo))
      .catch(() => {})
      .finally(() => active && setBootReady(true))
    return () => {
      active = false
    }
  }, [gateway])

  const didInit = useRef(false)
  useEffect(() => {
    if (DEMO) return // the demo session is pre-seeded; no OIDC callback to resolve
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
      solo,
      demo: DEMO,
      bootReady,
      // The console renders when there is a real identity, the dev owner key was chosen, OR the gateway is in
      // solo mode (single-user local — no sign-in at all).
      authenticated: !!user || devMode || solo,
      signIn: login,
      enableDevMode: () => setDevMode(true),
      // In the demo there is no IdP to sign out of; reloading just resets the in-memory state.
      signOut: DEMO
        ? () => window.location.reload()
        : () => {
            if (user) logout() // OIDC single-logout only when there is a session to end
            setUser(null)
            setMe(null)
            setActiveOrg('')
            setDevMode(false)
          },
    }),
    [user, me, activeOrg, gateway, devMode, solo, bootReady],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
