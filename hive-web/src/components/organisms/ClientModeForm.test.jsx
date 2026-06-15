import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ClientModeForm } from './ClientModeForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

afterEach(() => vi.restoreAllMocks())

describe('ClientModeForm', () => {
  it('connects as a client (binds the SSID profile) after confirmation', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onApply = vi.fn()
    render(<ClientModeForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('upstream-ssid'), { target: { value: 'HomeAP' } })
    fireEvent.click(screen.getByRole('button', { name: /connect as client/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['client-mode ssid HomeAP', 'client-mode connect'],
      save: true,
    })
  })

  it('does not connect when the danger confirm is declined', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const onApply = vi.fn()
    render(<ClientModeForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('upstream-ssid'), { target: { value: 'HomeAP' } })
    fireEvent.click(screen.getByRole('button', { name: /connect as client/i }))
    expect(onApply).not.toHaveBeenCalled()
  })

  it('reverts to AP mode', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const onApply = vi.fn()
    render(<ClientModeForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /back to ap mode/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['no client-mode connect'], save: true })
  })
})
