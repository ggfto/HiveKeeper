import { describe, it, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { DevicesPage } from './DevicesPage'

describe('DevicesPage', () => {
  it('loads and renders the registered fleet', async () => {
    const gateway = fakeGateway({
      devices: () =>
        Promise.resolve([
          { deviceId: 'd1', label: 'lab-ap', serial: 'SER123', model: 'AP230', mgmtIp: '10.0.0.1', siteId: 's1', groups: [] },
        ]),
      groups: () => Promise.resolve([]),
      sites: () => Promise.resolve([{ siteId: 's1', name: 'HQ' }]),
    })
    renderWithAuth(<DevicesPage />, { gateway })
    expect(await screen.findByText('SER123')).toBeInTheDocument()
    expect(screen.getByText('HQ')).toBeInTheDocument()
  })

  it('shows a forbidden note when the fleet list returns 403', async () => {
    const forbidden = Object.assign(new Error('forbidden'), { status: 403 })
    const gateway = fakeGateway({ devices: () => Promise.reject(forbidden) })
    renderWithAuth(<DevicesPage />, { gateway })
    expect(await screen.findByText(/viewer or admin role/i)).toBeInTheDocument()
  })
})
