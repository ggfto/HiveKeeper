import { describe, it, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { AgentsPage } from './AgentsPage'

describe('AgentsPage', () => {
  it('rolls the fleet up per agent: device count + site', async () => {
    const gateway = fakeGateway({
      agentsAll: () => Promise.resolve([{ agentId: 'lab-agent', name: 'lab-agent', siteId: 's1' }]),
      agents: () => Promise.resolve(['lab-agent']), // connected
      devices: () =>
        Promise.resolve([
          { deviceId: 'd1', serial: 'SER-A', reachableAgents: ['lab-agent'], siteId: 's1', groups: [] },
          { deviceId: 'd2', serial: 'SER-B', reachableAgents: ['other'], siteId: 's2', groups: [] },
        ]),
      sites: () => Promise.resolve([{ siteId: 's1', name: 'HQ' }]),
    })
    renderWithAuth(<AgentsPage />, { gateway })
    expect(await screen.findByText('lab-agent')).toBeInTheDocument()
    expect(screen.getByText(/1 device/)).toBeInTheDocument() // only d1 is reachable by lab-agent
    expect(screen.getByText(/HQ/)).toBeInTheDocument()
  })

  it('shows a gateway-unreachable note when the agent list fails', async () => {
    const gateway = fakeGateway({ agentsAll: () => Promise.reject(new Error('down')) })
    renderWithAuth(<AgentsPage />, { gateway })
    expect(await screen.findByText(/unreachable/i)).toBeInTheDocument()
  })
})
