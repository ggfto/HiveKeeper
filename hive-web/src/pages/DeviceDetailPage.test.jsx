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
  agentId: 'lab-agent',
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

  it('shows a not-found state for an unknown device', async () => {
    const gateway = fakeGateway({ devices: () => Promise.resolve([]) })
    renderDevice(gateway)
    expect(await screen.findByText(/device not found/i)).toBeInTheDocument()
  })
})
