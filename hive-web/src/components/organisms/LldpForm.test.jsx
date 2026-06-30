import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LldpForm } from './LldpForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1' }

describe('LldpForm', () => {
  it('enables LLDP and sets the advertise interval', () => {
    const onApply = vi.fn()
    render(<LldpForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/^LLDP$/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/advertise interval/i), { target: { value: '45' } })
    fireEvent.click(screen.getByRole('button', { name: /apply lldp/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['lldp', 'lldp timer 45'], save: true })
  })

  it('does nothing when no field is changed', () => {
    const onApply = vi.fn()
    render(<LldpForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply lldp/i }))
    expect(onApply).not.toHaveBeenCalled()
  })
})
