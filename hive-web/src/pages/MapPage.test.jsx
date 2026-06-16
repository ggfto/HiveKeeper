import { describe, it, expect, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { MapPage } from './MapPage'

// React Flow needs a real canvas + element measurements that jsdom lacks; stub it to render the node labels so
// the test can assert the page fetched, built the topology, and handed real nodes (with live counts) to the map.
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({ nodes }) => (
    <div data-testid="flow">
      {nodes.map((n) => (
        <div key={n.id}>
          {n.data.label}
          {n.type === 'ap' && n.data.clientCount != null ? ` (${n.data.clientCount})` : ''}
        </div>
      ))}
    </div>
  ),
  Background: () => null,
  Controls: () => null,
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
}))

describe('MapPage', () => {
  it('builds a live map: site nodes, AP nodes, and per-AP client counts', async () => {
    const gateway = fakeGateway({
      agents: () => Promise.resolve(['lab-agent']),
      sites: () => Promise.resolve([{ siteId: 's1', name: 'HQ' }]),
      devices: () =>
        Promise.resolve([
          {
            deviceId: 'd1',
            siteId: 's1',
            agentId: 'lab-agent',
            serial: 'SER-1',
            model: 'AP230',
            label: 'Lobby AP',
            mgmtIp: '10.0.0.1',
          },
        ]),
      inventory: () => Promise.resolve({ device: { hiveName: 'hk-mesh', stations: [{ mac: 'a' }, { mac: 'b' }] } }),
    })
    renderWithAuth(<MapPage />, { gateway })
    expect(await screen.findByText('HQ')).toBeInTheDocument() // a site node
    expect(screen.getByText('Lobby AP (2)')).toBeInTheDocument() // the AP with its live client count
  })

  it('shows an empty note when there is nothing to map', async () => {
    const gateway = fakeGateway({
      agents: () => Promise.resolve([]),
      sites: () => Promise.resolve([]),
      devices: () => Promise.resolve([]),
    })
    renderWithAuth(<MapPage />, { gateway })
    expect(await screen.findByText(/no sites or devices to map/i)).toBeInTheDocument()
  })
})
