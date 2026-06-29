import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { WifiSection } from './WifiSection'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }
const ssids = [
  { name: 'TESTE', security: 'wpa2-aes-psk', vlan: null, radios: ['wifi0', 'wifi1'] },
  { name: 'HK-JOB', security: 'wpa2-aes-psk', vlan: 7, radios: ['wifi0', 'wifi1'] },
]

describe('WifiSection', () => {
  it('lists the SSIDs loaded from the AP', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} />)
    expect(await screen.findByText('TESTE')).toBeInTheDocument()
    expect(screen.getByText('HK-JOB')).toBeInTheDocument()
    expect(loadSsids).toHaveBeenCalledWith(device)
  })

  it('marks a secured SSID with a lock and an open one without', async () => {
    const loadSsids = vi.fn().mockResolvedValue([
      { name: 'Secure', security: 'wpa2-aes-psk', vlan: null, radios: ['wifi0'] },
      { name: 'Guest', security: null, vlan: null, radios: ['wifi0'] },
    ])
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} />)
    await screen.findByText('Secure')
    expect(screen.getByTitle(/secured \(wpa2-aes-psk\)/i)).toBeInTheDocument()
    expect(screen.getByTitle(/open \(no authentication\)/i)).toBeInTheDocument()
  })

  it('shows an empty state when there are no SSIDs', async () => {
    const loadSsids = vi.fn().mockResolvedValue([])
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} />)
    expect(await screen.findByText(/no ssids/i)).toBeInTheDocument()
  })

  it('adds a new SSID (VLAN coerced to a number)', async () => {
    const loadSsids = vi.fn().mockResolvedValue([])
    const configureSsid = vi.fn().mockResolvedValue(undefined)
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={configureSsid} />)
    await screen.findByText(/no ssids/i)
    fireEvent.change(screen.getByPlaceholderText('HK-DEMO'), { target: { value: 'Lab' } })
    fireEvent.change(screen.getByPlaceholderText('min 8 chars'), { target: { value: 'pass1234' } })
    fireEvent.change(screen.getByPlaceholderText('7'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: /add ssid/i }))
    expect(configureSsid).toHaveBeenCalledWith(device, { name: 'Lab', psk: 'pass1234', vlan: 5, remove: false })
  })

  it('applies a minimum data rate (rate-set) for the chosen SSID via apply-config', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    const onApply = vi.fn()
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} onApply={onApply} />)
    await screen.findByLabelText(/minimum rate/i)
    fireEvent.change(screen.getByLabelText(/^ssid$/i), { target: { value: 'HK-JOB' } })
    fireEvent.change(screen.getByLabelText(/minimum rate/i), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: /apply minimum rate/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ssid HK-JOB 11g-rate-set 12-basic 18 24 36 48 54'],
      save: true,
    })
  })

  it('switches the rate ladder to 11a for the 5 GHz band', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    const onApply = vi.fn()
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} onApply={onApply} />)
    await screen.findByLabelText(/minimum rate/i)
    fireEvent.change(screen.getByLabelText(/band/i), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText(/minimum rate/i), { target: { value: '24' } })
    fireEvent.click(screen.getByRole('button', { name: /apply minimum rate/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ssid TESTE 11a-rate-set 24-basic 36 48 54'],
      save: true,
    })
  })
})
