import { describe, it, expect } from 'vitest'
import { buildTopology } from './topology'

const sites = [
  { siteId: 's1', name: 'HQ' },
  { siteId: 's2', name: 'Branch' },
]
const devices = [
  { deviceId: 'd1', siteId: 's1', agentId: 'a1', serial: 'SER-1', model: 'AP230', label: 'Lobby' },
  { deviceId: 'd2', siteId: 's1', agentId: 'a1', serial: 'SER-2', model: 'AP230', label: null },
  { deviceId: 'd3', siteId: null, agentId: 'a2', serial: 'SER-3', model: 'AP250', label: 'Loose' },
]
const statuses = {
  d1: { online: true, clientCount: 4, hive: 'hk-mesh', cloud: { managed: false } },
  d2: { online: true, clientCount: 1, hive: 'hk-mesh' },
  // d3 deliberately has no status (offline / not fetched)
}

describe('buildTopology', () => {
  it('makes a node per site (empty ones included, siteless -> Unassigned) and per AP', () => {
    const { nodes } = buildTopology({ sites, agents: ['a1'], devices, statuses })
    expect(nodes.filter((n) => n.type === 'site').map((n) => n.data.label)).toEqual([
      'HQ',
      'Branch',
      'Unassigned',
    ])
    expect(nodes.filter((n) => n.type === 'ap')).toHaveLength(3)
  })

  it('carries the live status onto the AP node and derives online', () => {
    const { nodes } = buildTopology({ sites, agents: ['a1'], devices, statuses })
    expect(nodes.find((n) => n.id === 'ap:d1').data).toMatchObject({
      label: 'Lobby',
      model: 'AP230',
      agentId: 'a1',
      hive: 'hk-mesh',
      clientCount: 4,
      online: true,
    })
    // d3 has no status: online falls back to whether its agent (a2) is connected; a2 is not -> offline.
    const d3 = nodes.find((n) => n.id === 'ap:d3').data
    expect(d3.online).toBe(false)
    expect(d3.clientCount).toBeNull()
  })

  it('links each AP to its site and chains same-hive APs with a mesh edge', () => {
    const { edges } = buildTopology({ sites, agents: ['a1'], devices, statuses })
    expect(edges).toContainEqual(expect.objectContaining({ source: 'site:s1', target: 'ap:d1' }))
    expect(edges).toContainEqual(expect.objectContaining({ source: 'site:__none__', target: 'ap:d3' }))
    const mesh = edges.filter((e) => e.data?.mesh)
    expect(mesh).toHaveLength(1) // d1 + d2 share hk-mesh -> a single chain edge
    expect(mesh[0]).toMatchObject({ source: 'ap:d1', target: 'ap:d2' })
  })

  it('is empty-safe', () => {
    expect(buildTopology()).toEqual({ nodes: [], edges: [] })
    expect(buildTopology({})).toEqual({ nodes: [], edges: [] })
  })
})

describe('buildTopology with clients', () => {
  const oneSite = [{ siteId: 's1', name: 'HQ' }]
  const oneAp = [{ deviceId: 'd1', siteId: 's1', agentId: 'a1', serial: 'SER-1', model: 'AP230', label: 'AP' }]

  it('emits a client node + edge per station, strongest signal first', () => {
    const statuses = {
      d1: {
        online: true,
        stations: [
          { mac: 'aa', ipAddress: '10.0.0.2', ssid: 'g', rssi: -80 },
          { mac: 'bb', ipAddress: '10.0.0.3', ssid: 'g', rssi: -50 },
        ],
      },
    }
    const { nodes, edges } = buildTopology({ sites: oneSite, agents: ['a1'], devices: oneAp, statuses }, { showClients: true })
    expect(nodes.filter((n) => n.type === 'client').map((c) => c.data.mac)).toEqual(['bb', 'aa']) // -50 before -80
    expect(edges).toContainEqual(expect.objectContaining({ source: 'ap:d1', target: 'client:d1:bb' }))
  })

  it('caps clients per AP and adds a single "+N more" node', () => {
    const stations = Array.from({ length: 9 }, (_, i) => ({ mac: `m${i}`, rssi: -40 - i }))
    const { nodes } = buildTopology(
      { sites: oneSite, agents: ['a1'], devices: oneAp, statuses: { d1: { online: true, stations } } },
      { showClients: true, clientCap: 6 },
    )
    expect(nodes.filter((n) => n.type === 'client' && !n.data.more)).toHaveLength(6)
    expect(nodes.find((n) => n.data.more).data.label).toBe('+3 more')
  })

  it('omits client nodes when showClients is off (default), but still counts them on the AP', () => {
    const statuses = { d1: { online: true, stations: [{ mac: 'aa', rssi: -50 }] } }
    const { nodes } = buildTopology({ sites: oneSite, agents: ['a1'], devices: oneAp, statuses })
    expect(nodes.some((n) => n.type === 'client')).toBe(false)
    expect(nodes.find((n) => n.id === 'ap:d1').data.clientCount).toBe(1)
  })

  it('expands a given AP to all its clients (no +N more)', () => {
    const stations = Array.from({ length: 9 }, (_, i) => ({ mac: `m${i}`, rssi: -40 - i }))
    const statuses = { d1: { online: true, stations } }
    const capped = buildTopology({ sites: oneSite, devices: oneAp, statuses }, { showClients: true, clientCap: 6 })
    expect(capped.nodes.some((n) => n.data.more)).toBe(true)
    expect(capped.nodes.filter((n) => n.type === 'client' && !n.data.more)).toHaveLength(6)
    const open = buildTopology({ sites: oneSite, devices: oneAp, statuses }, { showClients: true, clientCap: 6, expanded: ['d1'] })
    expect(open.nodes.some((n) => n.data.more)).toBe(false)
    expect(open.nodes.filter((n) => n.type === 'client')).toHaveLength(9)
  })
})

describe('buildTopology hive grouping', () => {
  const oneSite = [{ siteId: 's1', name: 'HQ' }]
  const threeAps = [
    { deviceId: 'd1', siteId: 's1', agentId: 'a1', label: 'A' },
    { deviceId: 'd2', siteId: 's1', agentId: 'a1', label: 'B' },
    { deviceId: 'd3', siteId: 's1', agentId: 'a1', label: 'C' },
  ]

  it('draws a hive box behind a cluster of 2+ same-hive APs (not for a lone AP)', () => {
    const statuses = { d1: { hive: 'hk' }, d2: { hive: 'hk' }, d3: { hive: 'solo' } }
    const { nodes } = buildTopology({ sites: oneSite, devices: threeAps, statuses })
    const groups = nodes.filter((n) => n.type === 'hiveGroup')
    expect(groups.map((g) => g.data.label)).toEqual(['hk']) // only the 2-AP hive
    expect(nodes[0].type).toBe('hiveGroup') // prepended so it renders behind
    expect(groups[0].zIndex).toBeLessThan(0)
  })

  it('orders same-hive APs contiguously within a site', () => {
    const statuses = { d1: { hive: 'x' }, d2: { hive: 'y' }, d3: { hive: 'x' } }
    const { nodes } = buildTopology({ sites: oneSite, devices: threeAps, statuses })
    expect(nodes.filter((n) => n.type === 'ap').map((n) => n.id)).toEqual(['ap:d1', 'ap:d3', 'ap:d2'])
  })
})
