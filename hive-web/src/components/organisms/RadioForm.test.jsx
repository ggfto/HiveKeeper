import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RadioForm } from './RadioForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('RadioForm', () => {
  it('applies channel + power for the default radio (wifi0), skipping blank mode', () => {
    const onApply = vi.fn()
    render(<RadioForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('auto or 36'), { target: { value: '36' } })
    fireEvent.change(screen.getByPlaceholderText('auto or 12'), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: /apply radio/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['interface wifi0 radio channel 36', 'interface wifi0 radio power 12'],
      save: true,
    })
  })

  it('does nothing when no radio field is filled in', () => {
    const onApply = vi.fn()
    render(<RadioForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply radio/i }))
    expect(onApply).not.toHaveBeenCalled()
  })
})
