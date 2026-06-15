import { describe, it, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { DevicesPage } from './DevicesPage'

describe('DevicesPage', () => {
  it('loads the fleet and shows live status (the device agent is connected)', async () => {
    const gateway = fakeGateway({
      devices: () =>
        Promise.resolve([
          { deviceId: 'd1', label: 'lab-ap', serial: 'SER123', model: 'AP230', mgmtIp: '10.0.0.1', siteId: 's1', agentId: 'lab-agent', groups: [] },
        ]),
      groups: () => Promise.resolve([]),
      sites: () => Promise.resolve([{ siteId: 's1', name: 'HQ' }]),
      agents: () => Promise.resolve(['lab-agent']),
    })
    renderWithAuth(<DevicesPage />, { gateway })
    expect(await screen.findByText('SER123')).toBeInTheDocument()
    expect(screen.getByText('online')).toBeInTheDocument() // agent connected -> online
  })

  it('filters to a single agent via ?agent=', async () => {
    const gateway = fakeGateway({
      devices: () =>
        Promise.resolve([
          { deviceId: 'd1', serial: 'SER-A', agentId: 'a1', groups: [] },
          { deviceId: 'd2', serial: 'SER-B', agentId: 'a2', groups: [] },
        ]),
      agents: () => Promise.resolve(['a1', 'a2']),
    })
    renderWithAuth(<DevicesPage />, { gateway, route: '/devices?agent=a1' })
    expect(await screen.findByText('SER-A')).toBeInTheDocument()
    expect(screen.queryByText('SER-B')).not.toBeInTheDocument()
  })

  it('shows a forbidden note when the fleet list returns 403', async () => {
    const forbidden = Object.assign(new Error('forbidden'), { status: 403 })
    const gateway = fakeGateway({ devices: () => Promise.reject(forbidden) })
    renderWithAuth(<DevicesPage />, { gateway })
    expect(await screen.findByText(/viewer or admin role/i)).toBeInTheDocument()
  })
})
