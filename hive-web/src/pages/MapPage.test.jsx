import { describe, it, expect, vi } from 'vitest'
import { screen, fireEvent } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { MapPage } from './MapPage'

// React Flow needs a real canvas + element measurements that jsdom lacks; stub it to render the node labels so
// the test can assert the page fetched, built the topology, and handed real nodes (with live counts) to the map.
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({ nodes, onNodeClick }) => (
    <div data-testid="flow">
      {nodes.map((n) => (
        <div key={n.id} onClick={(e) => onNodeClick?.(e, n)}>
          {n.data.label}
          {n.type === 'ap' && n.data.clientCount != null ? ` (${n.data.clientCount})` : ''}
        </div>
      ))}
    </div>
  ),
  Background: () => null,
  Panel: ({ children }) => <div>{children}</div>,
  Handle: () => null,
  Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
  useReactFlow: () => ({ zoomIn: () => {}, zoomOut: () => {}, fitView: () => {} }),
}))

const oneApGateway = () =>
  fakeGateway({
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
    inventory: () =>
      Promise.resolve({
        device: { hiveName: 'hk-mesh', stations: [{ mac: 'aa:bb', rssi: -50 }, { mac: 'cc:dd', rssi: -70 }] },
      }),
  })

describe('MapPage', () => {
  it('builds a live map: site nodes, AP nodes (with count), and per-AP client nodes', async () => {
    renderWithAuth(<MapPage />, { gateway: oneApGateway() })
    expect(await screen.findByText('HQ')).toBeInTheDocument() // a site node
    expect(screen.getByText('Lobby AP (2)')).toBeInTheDocument() // the AP with its live client count
    expect(screen.getByText('aa:bb')).toBeInTheDocument() // a client node, shown by default
  })

  it('hides the client nodes when the Clients switch is turned off', async () => {
    renderWithAuth(<MapPage />, { gateway: oneApGateway() })
    expect(await screen.findByText('aa:bb')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /show clients/i })) // MriSwitch is a styled checkbox
    expect(screen.queryByText('aa:bb')).not.toBeInTheDocument() // clients gone
    expect(screen.getByText('Lobby AP (2)')).toBeInTheDocument() // the AP still mapped, count intact
  })

  it('expands an AP to all its clients when the "+N more" node is clicked', async () => {
    const stations = Array.from({ length: 8 }, (_, i) => ({ mac: `m${i}`, rssi: -40 - i }))
    const gateway = fakeGateway({
      agents: () => Promise.resolve(['lab-agent']),
      sites: () => Promise.resolve([{ siteId: 's1', name: 'HQ' }]),
      devices: () =>
        Promise.resolve([{ deviceId: 'd1', siteId: 's1', agentId: 'lab-agent', label: 'AP', mgmtIp: '10.0.0.1' }]),
      inventory: () => Promise.resolve({ device: { stations } }),
    })
    renderWithAuth(<MapPage />, { gateway })
    expect(await screen.findByText('+2 more')).toBeInTheDocument() // 8 clients, capped at 6
    expect(screen.queryByText('m7')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('+2 more'))
    expect(screen.queryByText('+2 more')).not.toBeInTheDocument() // expanded -> overflow node gone
    expect(screen.getByText('m7')).toBeInTheDocument() // the 8th (weakest) client now shown
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
