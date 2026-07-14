import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

// OIDC login (auth-code + PKCE). The IdP only authenticates; the gateway authorizes via our own database.
//
// The client is built at RUNTIME, not at import time, from settings the gateway reports on /api/mode (see
// lib/oidcConfig). The console is published as a container image and run by operators at addresses we cannot
// know, so an authority compiled into the bundle would point every self-hoster at our dev Keycloak.

let manager = null

/**
 * (Re)build the OIDC client from resolved settings. Pass null when the deployment has no identity provider —
 * the console then offers no sign-in at all, rather than a button that leads nowhere.
 *
 * @param {object | null} settings from {@link resolveOidcSettings}
 * @returns {UserManager | null} the configured client
 */
export function configureAuth(settings) {
  manager = settings
    ? new UserManager({
        ...settings,
        // sessionStorage, so a refresh restores the session but closing the tab signs out.
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      })
    : null
  return manager
}

/** The current OIDC client, or null before {@link configureAuth} has run (or with no IdP configured). */
export const getUserManager = () => manager

export const login = () => manager?.signinRedirect()

// True single-logout: redirect to the IdP end-session endpoint so the SSO session is terminated (otherwise the
// next sign-in is promptless). The browser comes back to post_logout_redirect_uri, where resolveUser()
// finishes the handshake.
export const logout = () => manager?.signoutRedirect()

const clean = () => window.history.replaceState({}, document.title, window.location.pathname)

/**
 * Finish whichever OIDC handshake the URL represents:
 *  - login callback (code + state) -> exchange for tokens, return the user;
 *  - logout callback (state, no code) -> finalize sign-out, return null;
 *  - otherwise restore any stored session.
 *
 * Returns null when there is no OIDC client, so a gateway with no IdP simply has no session to resolve.
 */
export async function resolveUser() {
  if (!manager) return null

  const params = new URLSearchParams(window.location.search)
  if (params.has('code') && params.has('state')) {
    try {
      const user = await manager.signinRedirectCallback()
      clean()
      return user
    } catch {
      clean()
      return null
    }
  }
  if (params.has('state') && !params.has('code')) {
    try {
      await manager.signoutRedirectCallback()
    } catch {
      /* stale/no logout in flight */
    }
    clean()
    return null
  }
  const user = await manager.getUser()
  return user && !user.expired ? user : null
}
