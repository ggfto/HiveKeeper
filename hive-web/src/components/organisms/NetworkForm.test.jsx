import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NetworkForm } from './NetworkForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

afterEach(() => vi.restoreAllMocks())

describe('NetworkForm', () => {
  it('applies IP/netmask + gateway after confirmation', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onApply = vi.fn()
    render(<NetworkForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('10.0.0.1'), { target: { value: '192.168.1.50' } }) // IP (placeholder = mgmtIp)
    fireEvent.change(screen.getByPlaceholderText('192.168.1.1'), { target: { value: '192.168.1.254' } }) // gateway
    fireEvent.click(screen.getByRole('button', { name: /apply management/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['interface mgt0 ip 192.168.1.50/255.255.255.0', 'ip route default gateway 192.168.1.254'],
      save: true,
    })
  })

  it('does nothing if the danger confirm is declined', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const onApply = vi.fn()
    render(<NetworkForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('10.0.0.1'), { target: { value: '192.168.1.50' } })
    fireEvent.click(screen.getByRole('button', { name: /apply management/i }))
    expect(onApply).not.toHaveBeenCalled()
  })

  it('does nothing when no field was changed', () => {
    const onApply = vi.fn()
    render(<NetworkForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply management/i }))
    expect(onApply).not.toHaveBeenCalled()
  })
})
