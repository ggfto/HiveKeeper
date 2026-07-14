import { describe, it, expect } from 'vitest'
import { resolveOidcSettings } from './oidcConfig'

describe('resolveOidcSettings', () => {
  it('signs in against the gateway-reported issuer, so nothing is baked into the bundle', () => {
    const settings = resolveOidcSettings(
      { authority: 'https://id.acme.org/realms/hivekeeper', clientId: 'hive-gateway' },
      {},
      'https://hivekeeper.acme.org',
    )

    expect(settings.authority).toBe('https://id.acme.org/realms/hivekeeper')
    expect(settings.client_id).toBe('hive-gateway')
    expect(settings.redirect_uri).toBe('https://hivekeeper.acme.org/')
    expect(settings.post_logout_redirect_uri).toBe('https://hivekeeper.acme.org/')
  })

  it('prefers the gateway over the build-time fallback', () => {
    // This is the whole point: the SAME published image, run by two different self-hosters, must sign in
    // against each one's own IdP — never against whatever was compiled in.
    const settings = resolveOidcSettings(
      { authority: 'https://id.acme.org/realms/hivekeeper' },
      { authority: 'http://localhost:8081/realms/hivekeeper' },
      'https://hivekeeper.acme.org',
    )

    expect(settings.authority).toBe('https://id.acme.org/realms/hivekeeper')
  })

  it('falls back to the build-time authority for local development', () => {
    // `pnpm dev` against a gateway with no oidc profile: it reports no issuer, but a developer may still be
    // pointing at a Keycloak of their own.
    const settings = resolveOidcSettings(null, { authority: 'http://localhost:8081/realms/hivekeeper' }, '')

    expect(settings.authority).toBe('http://localhost:8081/realms/hivekeeper')
  })

  it('returns null when no identity provider is configured anywhere', () => {
    // The console must then show no sign-in button at all, rather than one that leads nowhere.
    expect(resolveOidcSettings(null, {}, 'https://hivekeeper.acme.org')).toBeNull()
    expect(resolveOidcSettings({}, {}, '')).toBeNull()
    expect(resolveOidcSettings({ authority: '   ' }, { authority: '' }, '')).toBeNull()
  })

  it('defaults the client id but lets a self-hoster override it', () => {
    const dflt = resolveOidcSettings({ authority: 'https://id.acme.org/realms/x' }, {}, '')
    expect(dflt.client_id).toBe('hive-gateway')

    const custom = resolveOidcSettings(
      { authority: 'https://id.acme.org/realms/x', clientId: 'hivekeeper-console' },
      {},
      '',
    )
    expect(custom.client_id).toBe('hivekeeper-console')
  })

  it('asks for auth-code + PKCE and keeps the session alive', () => {
    const settings = resolveOidcSettings({ authority: 'https://id.acme.org/realms/x' }, {}, '')

    expect(settings.response_type).toBe('code')
    expect(settings.scope).toContain('openid')
    expect(settings.automaticSilentRenew).toBe(true)
  })
})
