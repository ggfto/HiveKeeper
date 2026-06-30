import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { FleetAlertsPanel } from './FleetAlertsPanel'

const stations = (n) => Array.from({ length: n }, (_, i) => ({ mac: String(i) }))

const scan = [
  { device: { deviceId: 'd1', label: 'Lobby AP', serial: 'SER-A', mgmtIp: '10.0.0.1', model: 'AP230' }, online: true, snapshot: { stations: stations(40), cloud: { managed: true }, radios: [] } },
  { device: { deviceId: 'd2', label: 'Roof AP', serial: 'SER-B', mgmtIp: '10.0.0.2', model: 'AP250' }, online: false, snapshot: null },
  { device: { deviceId: 'd3', label: 'Office AP', serial: 'SER-C', mgmtIp: '10.0.0.3', model: 'AP230' }, online: true, snapshot: { stations: stations(2), cloud: { managed: false }, radios: [] } },
]

describe('FleetAlertsPanel', () => {
  it('lists devices with active alerts and their messages', () => {
    render(<FleetAlertsPanel scan={scan} onScan={vi.fn()} thresholds={{ maxStations: 30 }} onThresholds={vi.fn()} />)
    const table = within(screen.getByTestId('fleet-alerts'))
    // d1: high-clients + cloud-managed ; d2: offline. d3 is healthy -> not listed among alerting rows.
    expect(table.getByText('Lobby AP')).toBeInTheDocument()
    expect(table.getByText('Roof AP')).toBeInTheDocument()
    expect(table.getByText(/still cloud-managed/i)).toBeInTheDocument()
    expect(table.getByText(/unreachable/i)).toBeInTheDocument()
    expect(table.queryByText('Office AP')).not.toBeInTheDocument()
  })

  it('summarizes how many devices have alerts', () => {
    render(<FleetAlertsPanel scan={scan} onScan={vi.fn()} thresholds={{ maxStations: 30 }} onThresholds={vi.fn()} />)
    expect(screen.getByText(/2 of 3 devices/i)).toBeInTheDocument()
  })

  it('re-evaluates live when the threshold changes (raising it clears the high-clients alert)', () => {
    const { rerender } = render(
      <FleetAlertsPanel scan={scan} onScan={vi.fn()} thresholds={{ maxStations: 30 }} onThresholds={vi.fn()} />,
    )
    expect(screen.getByText(/40 clients/)).toBeInTheDocument()
    rerender(<FleetAlertsPanel scan={scan} onScan={vi.fn()} thresholds={{ maxStations: 100 }} onThresholds={vi.fn()} />)
    expect(screen.queryByText(/40 clients/)).not.toBeInTheDocument()
  })

  it('shows an all-clear message when nothing is alerting', () => {
    const healthy = [scan[2]]
    render(<FleetAlertsPanel scan={healthy} onScan={vi.fn()} thresholds={{ maxStations: 30 }} onThresholds={vi.fn()} />)
    expect(screen.getByText(/no active alerts/i)).toBeInTheDocument()
  })

  it('triggers a scan and edits the station threshold', () => {
    const onScan = vi.fn()
    const onThresholds = vi.fn()
    render(<FleetAlertsPanel scan={scan} onScan={onScan} thresholds={{ maxStations: 30 }} onThresholds={onThresholds} />)
    fireEvent.click(screen.getByRole('button', { name: /scan fleet/i }))
    expect(onScan).toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText(/max clients/i), { target: { value: '50' } })
    expect(onThresholds).toHaveBeenCalledWith({ maxStations: 50 })
  })

  it('prompts to scan before any scan has run', () => {
    render(<FleetAlertsPanel scan={null} onScan={vi.fn()} thresholds={{ maxStations: 30 }} onThresholds={vi.fn()} />)
    expect(screen.getByText(/scan the fleet/i)).toBeInTheDocument()
  })
})
