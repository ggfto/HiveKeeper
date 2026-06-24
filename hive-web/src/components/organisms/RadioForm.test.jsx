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

  it('shows a best-practice advisory for high TX power but still applies it', () => {
    const onApply = vi.fn()
    render(<RadioForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('auto or 12'), { target: { value: '20' } })
    expect(screen.getByTestId('radio-advisories')).toHaveTextContent(/TX power 20 dBm/i)
    // Advisory is non-blocking: the apply still goes through.
    fireEvent.click(screen.getByRole('button', { name: /apply radio/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['interface wifi0 radio power 20'],
      save: true,
    })
  })

  it('shows no advisory for a sensible config (ch 6, power 12 on 2.4 GHz)', () => {
    render(<RadioForm device={device} onApply={vi.fn()} />)
    fireEvent.change(screen.getByPlaceholderText('auto or 36'), { target: { value: '6' } })
    fireEvent.change(screen.getByPlaceholderText('auto or 12'), { target: { value: '12' } })
    expect(screen.queryByTestId('radio-advisories')).not.toBeInTheDocument()
  })
})
