import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RadioProfileForm } from './RadioProfileForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('RadioProfileForm', () => {
  it('applies channel width, band-steering and load-balance for the default 5 GHz profile', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/channel width/i), { target: { value: '40' } })
    fireEvent.change(screen.getByLabelText(/band steering/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/client load.?balanc/i), { target: { value: 'enable' } })
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'radio profile radio_ac0 channel-width 40',
        'radio profile radio_ac0 band-steering',
        'radio profile radio_ac0 client-load-balance',
      ],
      save: true,
    })
  })

  it('does nothing when no field is set', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).not.toHaveBeenCalled()
  })

  it('warns (but still applies) a wide channel on the 2.4 GHz profile', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'radio_ng0' } })
    fireEvent.change(screen.getByLabelText(/channel width/i), { target: { value: '40' } })
    expect(screen.getByTestId('profile-advisories')).toHaveTextContent(/2\.4 GHz/i)
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['radio profile radio_ng0 channel-width 40'],
      save: true,
    })
  })
})
