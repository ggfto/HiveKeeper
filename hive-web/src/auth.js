import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

// OIDC login against Keycloak (auth-code + PKCE). The IdP only authenticates; the gateway authorizes via
// our own database. Dev defaults point at scripts/dev-keycloak.ps1; override for a real IdP via Vite env.
const settings = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY || 'http://localhost:8081/realms/hivekeeper',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID || 'hive-gateway',
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  response_type: 'code',
  scope: 'openid profile email',
  // Keep tokens in sessionStorage so a refresh restores the session but closing the tab signs out.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: false,
}

export const userManager = new UserManager(settings)

export const login = () => userManager.signinRedirect()

// True single-logout: redirect to the IdP end-session endpoint so the Keycloak SSO session is terminated
// (otherwise the next sign-in is promptless). The browser comes back to post_logout_redirect_uri, where
// resolveUser() finishes the handshake.
export const logout = () => userManager.signoutRedirect()

const clean = () => window.history.replaceState({}, document.title, window.location.pathname)

/**
 * On load, finish whichever OIDC handshake the URL represents:
 *  - login callback (code + state) -> exchange for tokens, return the user;
 *  - logout callback (state, no code) -> finalize sign-out, return null;
 *  - otherwise restore any stored session.
 */
export async function resolveUser() {
  const params = new URLSearchParams(window.location.search)
  if (params.has('code') && params.has('state')) {
    try {
      const user = await userManager.signinRedirectCallback()
      clean()
      return user
    } catch {
      clean()
      return null
    }
  }
  if (params.has('state') && !params.has('code')) {
    try { await userManager.signoutRedirectCallback() } catch { /* stale/no logout in flight */ }
    clean()
    return null
  }
  const user = await userManager.getUser()
  return user && !user.expired ? user : null
}
