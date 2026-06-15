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
})
