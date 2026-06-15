import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CaptivePortalForm } from './CaptivePortalForm'

const device = { deviceId: 'd1', agentId: 'a1', mgmtIp: '10.0.0.1' }

describe('CaptivePortalForm', () => {
  it('builds a captive web portal on the named security object', () => {
    const onApply = vi.fn()
    render(<CaptivePortalForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText(/usually the ssid name/i), { target: { value: 'HK-JOB' } })
    fireEvent.change(screen.getByPlaceholderText(/updates\.example\.com/), {
      target: { value: '192.168.1.10\nupdates.example.com' },
    })
    fireEvent.click(screen.getByRole('button', { name: /apply captive portal/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'security-object HK-JOB web-server', // the internal web server is on by default
        'security-object HK-JOB walled-garden ip-address 192.168.1.10',
        'security-object HK-JOB walled-garden hostname updates.example.com',
      ],
      save: true,
    })
  })

  it('disables apply until a security object is named', () => {
    render(<CaptivePortalForm device={device} onApply={vi.fn()} />)
    expect(screen.getByRole('button', { name: /apply captive portal/i })).toBeDisabled()
  })
})
