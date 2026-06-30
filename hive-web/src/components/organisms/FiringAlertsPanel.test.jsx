import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { FiringAlertsPanel } from './FiringAlertsPanel'

const alert = (over = {}) => ({
  deviceId: 'd1',
  agentId: 'lab-agent',
  alertId: 'high-clients',
  severity: 'warning',
  message: '40 clients (> 30)',
  firstSeen: '2026-06-30T12:00:00Z',
  ...over,
})

describe('FiringAlertsPanel', () => {
  it('lists the poller’s currently-firing alerts', async () => {
    const loadFiring = vi.fn().mockResolvedValue([alert()])
    render(<FiringAlertsPanel loadFiring={loadFiring} />)
    expect(await screen.findByText('high-clients')).toBeInTheDocument()
    expect(screen.getByText('40 clients (> 30)')).toBeInTheDocument()
  })

  it('shows an empty state when nothing is firing', async () => {
    const loadFiring = vi.fn().mockResolvedValue([])
    render(<FiringAlertsPanel loadFiring={loadFiring} />)
    expect(await screen.findByText(/nothing open/i)).toBeInTheDocument()
  })

  it('refreshes on demand', async () => {
    const loadFiring = vi.fn().mockResolvedValue([])
    render(<FiringAlertsPanel loadFiring={loadFiring} />)
    await screen.findByText(/nothing open/i)
    fireEvent.click(screen.getByRole('button', { name: /refresh/i }))
    await waitFor(() => expect(loadFiring).toHaveBeenCalledTimes(2))
  })

  it('shows unreachable when the gateway errors', async () => {
    const loadFiring = vi.fn().mockRejectedValue(new Error('down'))
    render(<FiringAlertsPanel loadFiring={loadFiring} />)
    expect(await screen.findByText(/unreachable/i)).toBeInTheDocument()
  })
})
