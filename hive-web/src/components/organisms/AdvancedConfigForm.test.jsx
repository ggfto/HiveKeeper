import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AdvancedConfigForm } from './AdvancedConfigForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('AdvancedConfigForm', () => {
  it('applies the CLI lines (trimmed, blanks dropped) and saves by default', () => {
    const onApply = vi.fn()
    render(<AdvancedConfigForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: 'hostname lab-ap-01\n\n  country-code US  \n' },
    })
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['hostname lab-ap-01', 'country-code US'],
      save: true,
    })
  })

  it('can apply without saving', () => {
    const onApply = vi.fn()
    render(<AdvancedConfigForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'show clock' } })
    fireEvent.click(screen.getByLabelText(/save config/i)) // toggle off
    fireEvent.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['show clock'], save: false })
  })

  it('renders applied lines with their output and flags CLI errors', () => {
    const result = { commands: ['hostname x', 'bogus cmd'], outputs: ['', '% invalid input detected'], saved: true }
    render(<AdvancedConfigForm device={device} result={result} onApply={() => {}} />)
    expect(screen.getByText(/hostname x/)).toBeInTheDocument()
    expect(screen.getByText(/invalid input/i)).toBeInTheDocument()
  })
})
