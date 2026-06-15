import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DevicesTable } from './DevicesTable'

const devices = [
  {
    deviceId: 'd1',
    label: 'lab-ap',
    serial: '02301512211756',
    model: 'AP230',
    mgmtIp: '192.168.1.100',
    siteId: 's1',
    groups: ['g1'],
  },
]
const groups = [{ groupId: 'g1', name: 'Floor 3' }, { groupId: 'g2', name: 'Roof' }]
const sites = [{ siteId: 's1', name: 'HQ' }]

describe('DevicesTable', () => {
  it('shows an empty state when there are no devices', () => {
    render(<DevicesTable devices={[]} />)
    expect(screen.getByText(/no devices yet/i)).toBeInTheDocument()
  })

  it('renders identity, resolved site, and group names (not ids)', () => {
    render(<DevicesTable devices={devices} groups={groups} sites={sites} />)
    expect(screen.getByText('02301512211756')).toBeInTheDocument()
    expect(screen.getByText('AP230')).toBeInTheDocument()
    expect(screen.getByText('192.168.1.100')).toBeInTheDocument()
    expect(screen.getByText('HQ')).toBeInTheDocument()
    expect(screen.getByText('Floor 3')).toBeInTheDocument()
  })

  it('fires per-device inventory, backup and configure actions with the device', () => {
    const onInventory = vi.fn()
    const onBackup = vi.fn()
    const onConfigure = vi.fn()
    render(
      <DevicesTable
        devices={devices}
        groups={groups}
        sites={sites}
        onInventory={onInventory}
        onBackup={onBackup}
        onConfigure={onConfigure}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Inventory' }))
    fireEvent.click(screen.getByRole('button', { name: 'Backup' }))
    fireEvent.click(screen.getByRole('button', { name: 'Configure' }))
    expect(onInventory).toHaveBeenCalledWith(devices[0])
    expect(onBackup).toHaveBeenCalledWith(devices[0])
    expect(onConfigure).toHaveBeenCalledWith(devices[0])
  })
})
