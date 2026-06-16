import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LedForm } from './LedForm'

const device = { deviceId: 'd1', agentId: 'a1', mgmtIp: '10.0.0.1' }

describe('LedForm', () => {
  it('applies the LED settings as confirmed CLI (defaults to bright, power-saving unchanged)', () => {
    const onApply = vi.fn()
    render(<LedForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply led settings/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['system led brightness bright'], save: true })
  })

  it('explains that amber is the normal standalone state', () => {
    render(<LedForm device={device} onApply={vi.fn()} />)
    expect(screen.getByText(/amber means standalone/i)).toBeInTheDocument()
  })
})
