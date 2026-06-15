import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { IdentityForm } from './IdentityForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER', label: 'lab-ap' }

describe('IdentityForm', () => {
  it('sets the hostname', () => {
    const onApply = vi.fn()
    render(<IdentityForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'lab-ap-01' } })
    fireEvent.click(screen.getByRole('button', { name: /set hostname/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['hostname lab-ap-01'], save: true })
  })
})
