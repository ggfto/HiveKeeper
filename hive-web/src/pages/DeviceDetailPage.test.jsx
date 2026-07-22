import { describe, it, expect } from 'vitest'
import { screen, fireEvent } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { DeviceDetailPage } from './DeviceDetailPage'

const device = {
  deviceId: 'dev-1',
  label: 'lab-ap',
  serial: 'SER123',
  model: 'AP230',
  mgmtIp: '10.0.0.1',
  siteId: 's1',
  reachableAgents: ['lab-agent'],
  groups: [],
}

function renderDevice(gateway) {
  return renderWithAuth(<DeviceDetailPage />, {
    gateway,
    route: '/devices/dev-1',
    path: '/devices/:deviceId',
  })
}

describe('DeviceDetailPage', () => {
  it('loads the device (by route id) and shows inventory + the config categories', async () => {
    const gateway = fakeGateway({
      devices: () => Promise.resolve([device]),
      groups: () => Promise.resolve([]),
      sites: () => Promise.resolve([{ siteId: 's1', name: 'HQ' }]),
    })
    renderDevice(gateway)
    expect(await screen.findByText('SER123')).toBeInTheDocument() // inventory header
    expect(screen.getByDisplayValue('lab-ap')).toBeInTheDocument() // overview name field (default section)
    expect(screen.getByText('Radio')).toBeInTheDocument() // category nav
    expect(screen.getByText('Advanced')).toBeInTheDocument()
  })

  it('opens a config category from the side nav', async () => {
    const gateway = fakeGateway({ devices: () => Promise.resolve([device]) })
    renderDevice(gateway)
    await screen.findByText('SER123')
    fireEvent.click(screen.getByText('Radio'))
    expect(screen.getByRole('button', { name: /apply radio/i })).toBeInTheDocument()
  })

  it('shows live status (agent connected) and runs an inventory from the header', async () => {
    const gateway = fakeGateway({
      devices: () => Promise.resolve([device]),
      agents: () => Promise.resolve(['lab-agent']), // the device's agent is connected -> online
      inventory: () => Promise.resolve({ device: { model: 'AP230', firmwareVersion: '10.6r7', stations: [{}, {}] } }),
    })
    renderDevice(gateway)
    await screen.findByText('SER123')
    expect(screen.getByText('online')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /inventory/i }))
    expect(await screen.findByText(/2 station/)).toBeInTheDocument()
  })

  it('shows offline and disables the header reads when the agent is not connected', async () => {
    const gateway = fakeGateway({
      devices: () => Promise.resolve([device]),
      agents: () => Promise.resolve([]), // no agent connected -> offline
    })
    renderDevice(gateway)
    await screen.findByText('SER123')
    expect(screen.getByText('offline')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /inventory/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /backup/i })).toBeDisabled()
  })

  it('exposes restore + firmware upgrade in the Power section, gated on the agent being online', async () => {
    const gateway = fakeGateway({
      devices: () => Promise.resolve([device]),
      agents: () => Promise.resolve(['lab-agent']), // online
    })
    renderDevice(gateway)
    await screen.findByText('SER123')
    fireEvent.click(screen.getByText('Power'))
    expect(screen.getByRole('button', { name: /restore config/i })).toBeEnabled()
    expect(screen.getByRole('button', { name: /firmware upgrade/i })).toBeEnabled()
  })

  it('disables restore + firmware upgrade when the agent is offline', async () => {
    const gateway = fakeGateway({
      devices: () => Promise.resolve([device]),
      agents: () => Promise.resolve([]), // offline
    })
    renderDevice(gateway)
    await screen.findByText('SER123')
    fireEvent.click(screen.getByText('Power'))
    expect(screen.getByRole('button', { name: /restore config/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /firmware upgrade/i })).toBeDisabled()
  })

  it('lists the reachable agents and offers the serving-agent picker when two agents reach the device', async () => {
    const twoAgent = { ...device, reachableAgents: ['lab-agent', 'lab-agent-02'] }
    const gateway = fakeGateway({
      devices: () => Promise.resolve([twoAgent]),
      agents: () => Promise.resolve(['lab-agent', 'lab-agent-02']), // both connected
    })
    renderDevice(gateway)
    await screen.findByText('SER123')
    // the operator can pick which agent ops run through
    expect(screen.getByLabelText('Serving agent')).toBeInTheDocument()
    // both agents show as removable reachability chips (each can drive the AP)
    expect(screen.getByRole('button', { name: /remove lab-agent-02/i })).toBeInTheDocument()
  })

  it('shows a not-found state for an unknown device', async () => {
    const gateway = fakeGateway({ devices: () => Promise.resolve([]) })
    renderDevice(gateway)
    expect(await screen.findByText(/device not found/i)).toBeInTheDocument()
  })
})
