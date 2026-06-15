import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NetworkForm } from './NetworkForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

afterEach(() => vi.restoreAllMocks())

describe('NetworkForm', () => {
  it('changes the management IP (ip/netmask) after confirmation', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onApply = vi.fn()
    render(<NetworkForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('10.0.0.1'), { target: { value: '192.168.1.50' } })
    fireEvent.click(screen.getByRole('button', { name: /change management ip/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['interface mgt0 ip 192.168.1.50/255.255.255.0'],
      save: true,
    })
  })

  it('does nothing if the change is not confirmed', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const onApply = vi.fn()
    render(<NetworkForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('10.0.0.1'), { target: { value: '192.168.1.50' } })
    fireEvent.click(screen.getByRole('button', { name: /change management ip/i }))
    expect(onApply).not.toHaveBeenCalled()
  })
})
