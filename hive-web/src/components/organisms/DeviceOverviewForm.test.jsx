import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DeviceOverviewForm } from './DeviceOverviewForm'

const device = { deviceId: 'd1', serial: 'SER', label: 'lab-ap', siteId: 's1', groups: ['g1'] }
const sites = [{ siteId: 's1', name: 'HQ' }]
const groups = [{ groupId: 'g1', name: 'Floor 3' }, { groupId: 'g2', name: 'Roof' }]

function setup(handlers = {}) {
  const props = { device, sites, groups, onSave: vi.fn(), onTag: vi.fn(), onUntag: vi.fn(), onApply: vi.fn(), ...handlers }
  render(<DeviceOverviewForm {...props} />)
  return props
}

describe('DeviceOverviewForm', () => {
  it('shows the current name and group chips', () => {
    setup()
    expect(screen.getByDisplayValue('lab-ap')).toBeInTheDocument()
    expect(screen.getByText('Floor 3')).toBeInTheDocument()
  })

  it('saves the edited name (keeping the current site)', () => {
    const { onSave } = setup()
    fireEvent.change(screen.getByDisplayValue('lab-ap'), { target: { value: 'New Name' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))
    expect(onSave).toHaveBeenCalledWith(device, { label: 'New Name', siteId: 's1' })
  })

  it('removes the device from a group', () => {
    const { onUntag } = setup()
    fireEvent.click(screen.getByRole('button', { name: /remove from Floor 3/i }))
    expect(onUntag).toHaveBeenCalledWith(device, 'g1')
  })

  it('sets the AP hostname (the on-device name, separate from the display label)', () => {
    const { onApply } = setup()
    // the hostname input is the one whose placeholder is the current label; its value starts empty
    fireEvent.change(screen.getByPlaceholderText('lab-ap'), { target: { value: 'new-host' } })
    fireEvent.click(screen.getByRole('button', { name: /set hostname/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['hostname new-host'], save: true })
  })
})
