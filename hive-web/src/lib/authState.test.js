import { describe, it, expect } from 'vitest'
import { resolveAuth, defaultOrg, accountLabel, orgOptions } from './authState'

describe('resolveAuth', () => {
  it('uses the access token + active org when signed in', () => {
    const auth = resolveAuth({ user: { access_token: 'tok' }, activeOrg: 'acme' })
    expect(auth).toEqual({ accessToken: 'tok', org: 'acme' })
  })
  it('signed in with no org yet yields an empty org', () => {
    expect(resolveAuth({ user: { access_token: 'tok' } })).toEqual({ accessToken: 'tok', org: '' })
  })
  it('uses the dev tenant key only when dev mode is explicitly enabled', () => {
    expect(resolveAuth({ tenantKey: 'acme-key', devMode: true })).toEqual({ tenantKey: 'acme-key' })
  })
  it('is anonymous (no auth) when signed out and not in dev mode', () => {
    expect(resolveAuth({ tenantKey: 'acme-key' })).toEqual({})
    expect(resolveAuth({})).toEqual({})
  })
})

describe('defaultOrg', () => {
  it('picks the first organization', () => {
    expect(defaultOrg({ organizations: [{ tenantId: 'acme' }, { tenantId: 'globex' }] })).toBe('acme')
  })
  it('is empty with no organizations', () => {
    expect(defaultOrg({ organizations: [] })).toBe('')
    expect(defaultOrg(null)).toBe('')
  })
})

describe('accountLabel', () => {
  it('prefers the DB name from /api/me', () => {
    expect(accountLabel({ profile: { name: 'Token Name' } }, { name: 'DB Name' })).toBe('DB Name')
  })
  it('falls back through token claims', () => {
    expect(accountLabel({ profile: { preferred_username: 'op' } }, null)).toBe('op')
  })
  it('defaults to Signed in', () => {
    expect(accountLabel({ profile: {} }, null)).toBe('Signed in')
  })
})

describe('orgOptions', () => {
  it('maps organizations to select options', () => {
    const opts = orgOptions({ organizations: [{ tenantId: 'acme', tenantName: 'Acme' }, { tenantId: 'gx' }] })
    expect(opts).toEqual([{ label: 'Acme', value: 'acme' }, { label: 'gx', value: 'gx' }])
  })
  it('is empty with no organizations', () => expect(orgOptions(null)).toEqual([]))
})
