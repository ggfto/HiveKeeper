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

  it('POSTs a bulk apply-config with the target, commands and save flag', async () => {
    const fetchImpl = okFetch({ op: 'apply-config', total: 1, ok: 1, failed: 0, results: [] })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'acme-key' }), fetchImpl })
    await gw.bulkApplyConfig({ kind: 'site', id: 's1' }, ['hostname X'], true)
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/fleet/bulk/apply-config')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ siteId: 's1', commands: ['hostname X'], save: true })
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

  it('restores a running-config through an agent (POST with the config text)', async () => {
    const fetchImpl = okFetch({ commands: ['ssid Corp'], outputs: [''], saved: true })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.restore('lab-agent', '10.0.0.1', 'ssid Corp\n')
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/agents/lab-agent/restore')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ host: '10.0.0.1', port: 22, runningConfig: 'ssid Corp\n', save: true })
  })

  it('upgrades firmware through an agent (POST with the image url and reboot flag)', async () => {
    const fetchImpl = okFetch({ imageUrl: 'tftp://10.0.0.5/AP230.img', output: 'ok', rebooting: true })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.firmwareUpgrade('lab-agent', '10.0.0.1', 'tftp://10.0.0.5/AP230.img', { reboot: false })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/agents/lab-agent/firmware-upgrade')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({
      host: '10.0.0.1',
      port: 22,
      imageUrl: 'tftp://10.0.0.5/AP230.img',
      reboot: false,
    })
  })

  it('adopts a discovered host through an agent', async () => {
    const fetchImpl = okFetch({ deviceId: 'dev-9', serial: 'SER', model: 'AP230' })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.adopt('lab-agent', { host: '10.0.0.1', credRef: 'lab-ap' })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/agents/lab-agent/adopt')
    expect(JSON.parse(opts.body)).toEqual({ host: '10.0.0.1', credRef: 'lab-ap' })
  })

  it('sets a device credential through an agent (POST with the secret in the body)', async () => {
    const fetchImpl = okFetch({ credRef: 'dev-1', vaultUpdated: true, deviceUpdated: false })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.setCredential('lab-agent', { host: '10.0.0.1', port: 22, deviceId: 'dev-1', username: 'admin', password: 'pw', alsoSetOnDevice: false })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/agents/lab-agent/set-credential')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({
      host: '10.0.0.1', port: 22, deviceId: 'dev-1', username: 'admin', password: 'pw', alsoSetOnDevice: false,
    })
  })

  it('mints, lists, rotates and revokes PPSK users through an agent', async () => {
    const fetchImpl = okFetch({ users: [] })
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })

    await gw.ppskUsers('lab-agent')
    expect(fetchImpl.mock.calls[0][0]).toBe('/gw/api/agents/lab-agent/ppsk-users')
    expect(fetchImpl.mock.calls[0][1].method ?? 'GET').toBe('GET')

    await gw.createPpskUser('lab-agent', { securityObject: 'Corp', username: 'alice', vlanId: 30 })
    expect(fetchImpl.mock.calls[1][0]).toBe('/gw/api/agents/lab-agent/ppsk-users')
    expect(fetchImpl.mock.calls[1][1].method).toBe('POST')
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({ securityObject: 'Corp', username: 'alice', vlanId: 30 })

    await gw.rotatePpskUser('lab-agent', 'ppsk-1')
    expect(fetchImpl.mock.calls[2][0]).toBe('/gw/api/agents/lab-agent/ppsk-users/ppsk-1/rotate')
    expect(fetchImpl.mock.calls[2][1].method).toBe('POST')

    await gw.revokePpskUser('lab-agent', 'ppsk-1')
    expect(fetchImpl.mock.calls[3][0]).toBe('/gw/api/agents/lab-agent/ppsk-users/ppsk-1')
    expect(fetchImpl.mock.calls[3][1].method).toBe('DELETE')
  })

  it('adds an organization member with POST', async () => {
    const fetchImpl = okFetch({ userId: 'usr-9' })
    const gw = createGateway({ getAuth: () => ({ accessToken: 'tok', org: 'acme' }), fetchImpl })
    await gw.addMember({ username: 'bob', password: 'pw', role: 'viewer' })
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/members')
    expect(opts.method).toBe('POST')
    expect(JSON.parse(opts.body)).toEqual({ username: 'bob', password: 'pw', role: 'viewer' })
  })

  it('changes a member role with PATCH', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ accessToken: 'tok', org: 'acme' }), fetchImpl })
    await gw.setMemberRole('usr-2', 'admin')
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/members/usr-2')
    expect(opts.method).toBe('PATCH')
    expect(JSON.parse(opts.body)).toEqual({ role: 'admin' })
  })

  it('removes a member with DELETE', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ accessToken: 'tok', org: 'acme' }), fetchImpl })
    await gw.removeMember('usr-2')
    const [url, opts] = fetchImpl.mock.calls[0]
    expect(url).toBe('/gw/api/members/usr-2')
    expect(opts.method).toBe('DELETE')
  })

  it('renames a site with PATCH and deletes one with DELETE', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.renameSite('site-1', 'HQ2')
    expect(fetchImpl.mock.calls[0][0]).toBe('/gw/api/sites/site-1')
    expect(fetchImpl.mock.calls[0][1].method).toBe('PATCH')
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ name: 'HQ2' })
    await gw.deleteSite('site-1')
    expect(fetchImpl.mock.calls[1][0]).toBe('/gw/api/sites/site-1')
    expect(fetchImpl.mock.calls[1][1].method).toBe('DELETE')
  })

  it('updates a group with PATCH (name + site) and deletes one with DELETE', async () => {
    const fetchImpl = okFetch(undefined)
    const gw = createGateway({ getAuth: () => ({ tenantKey: 'k' }), fetchImpl })
    await gw.updateGroup('grp-1', { name: 'Floor 9', siteId: 's2' })
    expect(fetchImpl.mock.calls[0][0]).toBe('/gw/api/groups/grp-1')
    expect(fetchImpl.mock.calls[0][1].method).toBe('PATCH')
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ name: 'Floor 9', siteId: 's2' })
    await gw.deleteGroup('grp-1')
    expect(fetchImpl.mock.calls[1][0]).toBe('/gw/api/groups/grp-1')
    expect(fetchImpl.mock.calls[1][1].method).toBe('DELETE')
  })
})
