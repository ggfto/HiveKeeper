import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { configureAuth, getUserManager, login, logout, resolveUser } from '../auth'
import { createGateway } from '../api/gateway'
import { createDemoGateway, DEMO_USER, DEMO_ME } from '../api/demoGateway'
import { resolveAuth, defaultOrg } from '../lib/authState'
import { resolveOidcSettings } from '../lib/oidcConfig'

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
  // Whether this deployment has an identity provider at all — also learned on boot. Without one there is
  // nothing to sign in to, so the console must not offer a button that leads nowhere.
  const [oidcReady, setOidcReady] = useState(false)

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
  //
  // Runs after the boot effect has built the client (oidcReady), since before that there is no client to
  // listen to.
  useEffect(() => {
    const manager = getUserManager()
    if (!manager) return // demo build, or a deployment with no IdP
    const onLoaded = (u) => {
      authRef.current = { ...authRef.current, user: u }
      setUser(u)
    }
    const onExpired = () => {
      setUser(null)
      setMe(null)
      setActiveOrg('')
    }
    manager.events.addUserLoaded(onLoaded)
    manager.events.addAccessTokenExpired(onExpired)
    return () => {
      manager.events.removeUserLoaded(onLoaded)
      manager.events.removeAccessTokenExpired(onExpired)
    }
  }, [oidcReady])

  // Boot, in this order and no other: learn the deployment's shape, BUILD the OIDC client from what it
  // reports, and only then resolve the session. The console is one published image run at addresses we cannot
  // know, so it cannot know its identity provider until the gateway names it — which means the OIDC client
  // cannot exist until this has run. bootReady gates the first render, so the app never flashes a sign-in page
  // before learning it is in solo mode (or that there is no IdP at all).
  const didBoot = useRef(false)
  useEffect(() => {
    if (didBoot.current) return // resolveUser() consumes a one-time OIDC callback; never run it twice
    didBoot.current = true

    let active = true
    ;(async () => {
      let mode = null
      try {
        mode = await gateway.mode()
      } catch {
        /* gateway unreachable: fall back to a normal, non-solo deployment and the build-time IdP (if any) */
      }
      if (!active) return
      setSolo(!!mode?.solo)

      const settings = resolveOidcSettings(
        mode?.oidc,
        {
          authority: import.meta.env.VITE_OIDC_AUTHORITY,
          clientId: import.meta.env.VITE_OIDC_CLIENT_ID,
        },
        window.location.origin,
      )
      // The demo build has no IdP and a pre-seeded session, so it never gets a client.
      const manager = DEMO ? null : configureAuth(settings)
      setOidcReady(!!manager)

      if (manager) {
        const u = await resolveUser()
        if (u && active) {
          authRef.current = { ...authRef.current, user: u }
          setUser(u)
          try {
            const profile = await gateway.me()
            if (active) {
              setMe(profile)
              setActiveOrg(defaultOrg(profile))
            }
          } catch {
            /* identity stays token-only */
          }
        }
      }
      if (active) setBootReady(true)
    })()

    return () => {
      active = false
    }
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
      // Whether this deployment has an IdP to sign in to. The sign-in gate reads it so a gateway running
      // without OIDC does not offer a button that leads nowhere.
      oidcReady,
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
    [user, me, activeOrg, gateway, devMode, solo, bootReady, oidcReady],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}
