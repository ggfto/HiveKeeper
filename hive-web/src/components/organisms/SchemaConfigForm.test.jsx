import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Clock } from 'lucide-react'
import { SchemaConfigForm } from './SchemaConfigForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }
const section = {
  id: 'ntp',
  label: 'NTP',
  icon: Clock,
  fields: [{ key: 'server', label: 'NTP server', placeholder: 'pool.ntp.org' }],
  toCli: (v) => (v.server ? [`ntp server ${v.server}`, 'ntp enable'] : []),
}

describe('SchemaConfigForm', () => {
  it('renders the section fields and applies the built commands', () => {
    const onApply = vi.fn()
    render(<SchemaConfigForm section={section} device={device} onApply={onApply} />)
    fireEvent.change(screen.getByPlaceholderText('pool.ntp.org'), { target: { value: 'time.google.com' } })
    fireEvent.click(screen.getByRole('button', { name: /apply ntp/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ntp server time.google.com', 'ntp enable'],
      save: true,
    })
  })

  it('does nothing when the section yields no commands', () => {
    const onApply = vi.fn()
    render(<SchemaConfigForm section={section} device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply ntp/i }))
    expect(onApply).not.toHaveBeenCalled()
  })
})
