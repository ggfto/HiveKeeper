import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { PowerForm } from './PowerForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('PowerForm', () => {
  it('disconnects from the cloud (standalone)', () => {
    const onApply = vi.fn()
    render(<PowerForm device={device} onApply={onApply} onReboot={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /disconnect/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['no capwap client enable'], save: true })
  })

  it('reconnects to the cloud', () => {
    const onApply = vi.fn()
    render(<PowerForm device={device} onApply={onApply} onReboot={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /reconnect/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['capwap client enable'], save: true })
  })

  it('reboots the device', () => {
    const onReboot = vi.fn()
    render(<PowerForm device={device} onApply={() => {}} onReboot={onReboot} />)
    fireEvent.click(screen.getByRole('button', { name: /reboot device/i }))
    expect(onReboot).toHaveBeenCalledWith(device)
  })
})
