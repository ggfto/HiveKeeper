import { describe, it, expect, vi } from 'vitest'
import { screen, fireEvent, waitFor } from '@testing-library/react'
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

  it('removes an agent and reloads the list', async () => {
    let identities = [{ agentId: 'old-agent', name: 'old-agent', siteId: null }]
    const deleteAgent = vi.fn((id) => {
      identities = identities.filter((a) => a.agentId !== id)
      return Promise.resolve({})
    })
    const gateway = fakeGateway({
      agentsAll: () => Promise.resolve(identities),
      agents: () => Promise.resolve([]),
      deleteAgent,
    })
    renderWithAuth(<AgentsPage />, { gateway })
    expect(await screen.findByText('old-agent')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^remove$/i }))
    fireEvent.click(screen.getByRole('button', { name: /remove agent/i }))

    await waitFor(() => expect(deleteAgent).toHaveBeenCalledWith('old-agent'))
    await waitFor(() => expect(screen.queryByText('old-agent')).not.toBeInTheDocument())
  })

  it('keeps the agent listed and reports why when the delete is refused', async () => {
    const gateway = fakeGateway({
      agentsAll: () => Promise.resolve([{ agentId: 'old-agent', name: 'old-agent', siteId: null }]),
      agents: () => Promise.resolve([]),
      deleteAgent: () => Promise.reject(new Error('forbidden')),
    })
    renderWithAuth(<AgentsPage />, { gateway })
    expect(await screen.findByText('old-agent')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^remove$/i }))
    fireEvent.click(screen.getByRole('button', { name: /remove agent/i }))

    expect(await screen.findByText(/forbidden/i)).toBeInTheDocument()
    expect(screen.getByText('old-agent')).toBeInTheDocument()
  })
})
