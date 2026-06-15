/**
 * Pure auth-state helpers. The console authenticates a user via OIDC (oidc-client-ts, see ../auth.js) and the
 * gateway authorizes by the user's scoped role in the ACTIVE organization. These helpers turn the OIDC user +
 * the chosen org into the auth shape the gateway client consumes, and derive the account/org display bits.
 */

/**
 * Turn the current session into the gateway client's auth. Signed in -> bearer + active org. Otherwise the
 * dev owner key ONLY when dev mode was explicitly enabled (a visible opt-in, never the silent default). Plain
 * signed-out is anonymous (no auth headers) so the console gates it behind sign-in instead of granting access.
 */
export function resolveAuth({ user, activeOrg, tenantKey, devMode } = {}) {
  if (user?.access_token) {
    return { accessToken: user.access_token, org: activeOrg || '' }
  }
  if (devMode) {
    return { tenantKey: tenantKey || '' }
  }
  return {}
}

/** The organization to activate by default after sign-in (the first membership). */
export function defaultOrg(me) {
  return me?.organizations?.[0]?.tenantId || ''
}

/** A human label for the signed-in account: DB name, then token claims, then a generic fallback. */
export function accountLabel(user, me) {
  return (
    me?.name ||
    user?.profile?.name ||
    user?.profile?.preferred_username ||
    user?.profile?.email ||
    'Signed in'
  )
}

/** The MriSelect options for the organization switcher. */
export function orgOptions(me) {
  return (me?.organizations || []).map((o) => ({ label: o.tenantName || o.tenantId, value: o.tenantId }))
}
