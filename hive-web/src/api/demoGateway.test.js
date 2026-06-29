import { describe, it, expect } from 'vitest'
import { createDemoGateway } from './demoGateway'
import { createGateway } from './gateway'
import { parseSsids, parseAcsp, parseCapwap } from '../lib/hiveosParse'

describe('demoGateway', () => {
  it('mirrors the real gateway method surface (no missing methods)', () => {
    const real = createGateway()
    const demo = createDemoGateway()
    const realMethods = Object.keys(real).filter((k) => typeof real[k] === 'function')
    const missing = realMethods.filter((m) => typeof demo[m] !== 'function')
    expect(missing).toEqual([])
  })

  it('sets a credential and identifies/adopts a discovered host', async () => {
    const gw = createDemoGateway()
    // setCredential returns a credential result, never a secret
    const cred = await gw.setCredential('hq-agent', { host: '192.168.10.11', deviceId: 'd-rcp', username: 'admin', password: 'Aerohive1' })
    expect(cred).toMatchObject({ vaultUpdated: true })

    // a discovered host can be identified (model -> support badge) and then adopted
    const { hosts } = await gw.discover('hq-agent')
    expect(hosts[0].looksLikeSsh).toBe(true)
    const inv = await gw.inventory('hq-agent', hosts[0].host)
    expect(inv.device.model).toBe('AP230')
    const adopted = await gw.adopt('hq-agent', { host: hosts[1].host })
    expect(adopted.model).toBe('AP1130')
  })

  it('serves a seeded fleet', async () => {
    const gw = createDemoGateway()
    expect((await gw.sites()).length).toBeGreaterThan(0)
    const devices = await gw.devices()
    expect(devices.length).toBeGreaterThan(0)
    expect(devices[0]).toHaveProperty('deviceId')
    expect(devices[0]).toHaveProperty('mgmtIp')
  })

  it('returns inventory whose canned CLI the real parsers can read', async () => {
    const gw = createDemoGateway()
    const d = (await gw.devices()).find((x) => x.agentId === 'hq-agent')
    const inv = await gw.inventory(d.agentId, d.mgmtIp)
    expect(inv.device.model).toBeTruthy()
    expect(inv.device.stations.length).toBeGreaterThan(0)

    const rc = await gw.agentOp(d.agentId, 'apply-config', { host: d.mgmtIp, commands: ['show running-config'] })
    expect(parseSsids(rc.outputs[0]).length).toBeGreaterThan(0)

    const live = await gw.agentOp(d.agentId, 'apply-config', { host: d.mgmtIp, commands: ['show capwap client', 'show acsp'] })
    expect(parseCapwap(live.outputs[0]).managed).toBe(false) // standalone, the HiveKeeper story
    expect(parseAcsp(live.outputs[1]).length).toBe(2)
  })

  it('reflects a configured SSID and rejects offline agents', async () => {
    const gw = createDemoGateway()
    const d = (await gw.devices()).find((x) => x.agentId === 'hq-agent')
    await gw.agentOp(d.agentId, 'configure-ssid', { host: d.mgmtIp, name: 'Lab-Net', vlan: 99, remove: false })
    const rc = await gw.agentOp(d.agentId, 'apply-config', { host: d.mgmtIp, commands: ['show running-config'] })
    expect(parseSsids(rc.outputs[0]).some((s) => s.name === 'Lab-Net' && s.vlan === 99)).toBe(true)

    const offline = (await gw.devices()).find((x) => x.agentId === 'old-agent')
    await expect(gw.inventory(offline.agentId, offline.mgmtIp)).rejects.toThrow()
  })
})
