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
