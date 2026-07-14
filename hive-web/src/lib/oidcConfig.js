/**
 * Works out the OIDC settings this console should sign in with — at RUNTIME, from what the gateway reports.
 *
 * The console ships as one static bundle in a container image, built by us and run by operators at addresses
 * we will never know. An identity provider URL baked in at build time is therefore wrong for everybody: the
 * published image would point every self-hoster at our dev Keycloak, and the only fix would be for each of
 * them to rebuild it. So the gateway hands over its issuer and client id on /api/mode, and we build the
 * client from that.
 *
 * The Vite env vars stay as a fallback, for `pnpm dev` against a gateway that has no IdP configured at all.
 */

/** The scopes and token lifetimes are policy, not deployment detail, so they live here rather than in config. */
const FIXED = {
  response_type: 'code',
  scope: 'openid profile email',
  // Renew the (short, ~5 min) access token in the background before it expires, so a longer session does not
  // start 401ing every gateway call mid-work.
  automaticSilentRenew: true,
  accessTokenExpiringNotificationTimeInSeconds: 60,
}

/**
 * @param {{authority?: string, clientId?: string} | null | undefined} fromGateway  the `oidc` block of /api/mode
 * @param {{authority?: string, clientId?: string}} [fallback]  build-time defaults, for local development
 * @param {string} origin  where the browser is, for the redirect URIs
 * @returns {object | null} oidc-client-ts settings, or null when this deployment has no identity provider —
 *                          in which case the console must offer no sign-in rather than one that leads nowhere
 */
export function resolveOidcSettings(fromGateway, fallback = {}, origin = '') {
  const authority = trimmed(fromGateway?.authority) || trimmed(fallback.authority)
  if (!authority) return null

  return {
    ...FIXED,
    authority,
    client_id: trimmed(fromGateway?.clientId) || trimmed(fallback.clientId) || 'hive-gateway',
    redirect_uri: origin + '/',
    post_logout_redirect_uri: origin + '/',
  }
}

const trimmed = (v) => (typeof v === 'string' && v.trim() ? v.trim() : '')
