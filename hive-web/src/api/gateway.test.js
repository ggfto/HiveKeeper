import { describe, it, expect, vi } from 'vitest'
import { buildHeaders, bulkBody, createGateway } from './gateway'

describe('buildHeaders', () => {
  it('uses the bearer token and active org when signed in', () => {
    const h = buildHeaders({ accessToken: 'tok', org: 'acme' })
    expect(h.Authorization).toBe('Bearer tok')
    expect(h['X-Org']).toBe('acme')
  })

  it('omits X-Org until an organization is active', () => {
    const h = buildHeaders({ accessToken: 'tok' })
    expect(h.Authorization).toBe('Bearer tok')
    expect(h['X-Org']).toBeUndefined()
  })

  it('falls back to the tenant key when signed out', () => {
    const h = buildHeaders({ tenantKey: 'acme-key' })
    expect(h['X-Tenant-Key']).toBe('acme-key')
    expect(h.Authorization).toBeUndefined()
  })

  it('prefers the bearer token over a tenant key when both are present', () => {
    const h = buildHeaders({ accessToken: 'tok', org: 'acme', tenantKey: 'acme-key' })
    expect(h.Authorization).toBe('Bearer tok')
    expect(h['X-Tenant-Key']).toBeUndefined()
  })

  it('merges extra headers', () => {
    const h = buildHeaders({ tenantKey: 'k' }, { 'Content-Type': 'application/json' })
    expect(h['Content-Type']).toBe('application/json')
  })
})

describe('bulkBody', () => {
  it('maps an org target to an empty body', () => expect(bulkBody({ kind: 'org' })).toEqual({}))
  it('maps a site target', () => expect(bulkBody({ kind: 'site', id: 's1' })).toEqual({ siteId: 's1' }))
  it('maps a group target', () => expect(bulkBody({ kind: 'group', id: 'g1' })).toEqual({ groupId: 'g1' }))
  it('defaults to org for an unknown target', () => expect(bulkBody()).toEqual({}))
})

describe('createGateway', () => {
  const okFetch = (result, { ok = true, status = 200 } = {}) =>
    vi.fn(async () => ({ ok, status, text: async () => (result === undefined ? '' : JSON.stringify(result)) }))

  it('GETs devices with the auth headers and parses the body', async () => {
    const fetchImpl = okFetch([{ deviceId: 'd1' }])
    const gw = createGateway({ getAuth: () => ({ accessToken: 'tok', org: 'acme' }), fetchImpl })
    const devices = await gw.devices()
    expect(devices).toEqual([{ deviceId: 'd1' }])
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/devices')
    expect(opts.headers.Authorization).toBe('Bearer tok')
    expect(opts.headers['X-Org']).toBe('acme')
  })

  it('POSTs a bulk op with the target body and JSON content type', async () => {
    const fetchImpl = okFetch({ op: 'inventory', total: 1, ok: 1, failed: 0, results: [] })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'acme-key' }), fetchImpl })
    await gw.bulk('inventory', { kind: 'group', id: 'g1' })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/fleet/bulk/inventory')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ groupId: 'g1' })
    expect(opts.headers['X-Tenant-Key']).toBe('acme-key')
    expect(opts.headers['Content-Type']).toBe('application/json')
  })

  it('throws a structured error (status + message) on a non-ok response', async () => {
    const fetchImpl = okFetch({ error: 'forbidden', detail: 'requires VIEWER' }, { ok: false, status: 403 })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await expect(gw.devices()).rejects.toMatchObject({ status: 403, message: 'forbidden' })
  })

  it('tags a device into a group', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.tagDevice('dev-1', 'grp-1')
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/devices/dev-1/groups')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ groupId: 'grp-1' })
  })

  it('updates a device with PATCH', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.updateDevice('dev-1', { label: 'Lab AP', siteId: 's2' })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/devices/dev-1')
    expect(opts.method).toBe('PATCH')
    expect(JSON.parse(opts.body)).toEqual({ label: 'Lab AP', siteId: 's2' })
  })

  it('untags a device from a group with DELETE', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.untagDevice('dev-1', 'g1')
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/devices/dev-1/groups/g1')
    expect(opts.method).toBe('DELETE')
  })

  it('adopts a discovered host through an agent', async () => {
    const fetchImpl = okFetch({ deviceId: 'dev-9', serial: 'SER', model: 'AP230' })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.adopt('lab-agent', { host: '10.0.0.1', credRef: 'lab-ap' })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/agents/lab-agent/adopt')
    expect(JSON.parse(opts.body)).toEqual({ host: '10.0.0.1', credRef: 'lab-ap' })
  })
})
