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

  it('adds a new SSID (defaults to WPA2-PSK, VLAN coerced to a number)', async () => {
    const loadSsids = vi.fn().mockResolvedValue([])
    const configureSsid = vi.fn().mockResolvedValue(undefined)
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={configureSsid} />)
    await screen.findByText(/no ssids/i)
    fireEvent.change(screen.getByPlaceholderText('HK-DEMO'), { target: { value: 'Lab' } })
    fireEvent.change(screen.getByPlaceholderText('min 8 chars'), { target: { value: 'pass1234' } })
    fireEvent.change(screen.getByPlaceholderText('7'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: /add ssid/i }))
    expect(configureSsid).toHaveBeenCalledWith(device, {
      name: 'Lab',
      psk: 'pass1234',
      vlan: 5,
      remove: false,
      security: 'wpa2-aes-psk',
    })
  })

  it('adds a WPA3-SAE SSID when that suite is picked', async () => {
    const loadSsids = vi.fn().mockResolvedValue([])
    const configureSsid = vi.fn().mockResolvedValue(undefined)
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={configureSsid} />)
    await screen.findByText(/no ssids/i)
    fireEvent.change(screen.getByPlaceholderText('HK-DEMO'), { target: { value: 'Secure3' } })
    fireEvent.change(screen.getByLabelText(/security/i), { target: { value: 'wpa3-sae' } })
    fireEvent.change(screen.getByPlaceholderText('min 8 chars'), { target: { value: 'pass1234' } })
    fireEvent.click(screen.getByRole('button', { name: /add ssid/i }))
    expect(configureSsid).toHaveBeenCalledWith(device, {
      name: 'Secure3',
      psk: 'pass1234',
      vlan: null,
      remove: false,
      security: 'wpa3-sae',
    })
  })

  it('adds an open SSID with no passphrase field', async () => {
    const loadSsids = vi.fn().mockResolvedValue([])
    const configureSsid = vi.fn().mockResolvedValue(undefined)
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={configureSsid} />)
    await screen.findByText(/no ssids/i)
    fireEvent.change(screen.getByPlaceholderText('HK-DEMO'), { target: { value: 'Guest' } })
    fireEvent.change(screen.getByLabelText(/security/i), { target: { value: 'open' } })
    expect(screen.queryByPlaceholderText('min 8 chars')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /add ssid/i }))
    expect(configureSsid).toHaveBeenCalledWith(device, {
      name: 'Guest',
      psk: '',
      vlan: null,
      remove: false,
      security: 'open',
    })
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

  it('adds an 802.1X SSID with RADIUS fields instead of a passphrase', async () => {
    const loadSsids = vi.fn().mockResolvedValue([])
    const configureSsid = vi.fn().mockResolvedValue(undefined)
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={configureSsid} />)
    await screen.findByText(/no ssids/i)
    fireEvent.change(screen.getByPlaceholderText('HK-DEMO'), { target: { value: 'Corp' } })
    fireEvent.change(screen.getByLabelText(/security/i), { target: { value: 'wpa2-aes-8021x' } })
    expect(screen.queryByPlaceholderText('min 8 chars')).not.toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('10.0.0.5'), { target: { value: '10.0.0.5' } })
    fireEvent.change(screen.getByPlaceholderText('shared secret'), { target: { value: 'r4dsecret' } })
    fireEvent.click(screen.getByRole('button', { name: /add ssid/i }))
    expect(configureSsid).toHaveBeenCalledWith(device, {
      name: 'Corp',
      psk: '',
      vlan: null,
      remove: false,
      security: 'wpa2-aes-8021x',
      radiusServer: '10.0.0.5',
      radiusSecret: 'r4dsecret',
    })
  })

  it('applies per-SSID hardening (hide + isolation) via apply-config', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    const onApply = vi.fn()
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} onApply={onApply} />)
    await screen.findByRole('button', { name: /apply hardening/i })
    fireEvent.change(screen.getByLabelText(/hide ssid/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/client isolation/i), { target: { value: 'enable' } })
    fireEvent.click(screen.getByRole('button', { name: /apply hardening/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ssid TESTE hide-ssid', 'no ssid TESTE inter-station-traffic'],
      save: true,
    })
  })

  it('enables PPSK mode for an SSID via apply-config', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    const onApply = vi.fn()
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} onApply={onApply} />)
    await screen.findByRole('button', { name: /^apply ppsk$/i })
    fireEvent.change(screen.getByLabelText(/ppsk mode/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/user-group/i), { target: { value: 'staff' } })
    fireEvent.click(screen.getByRole('button', { name: /^apply ppsk$/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['security-object TESTE security private-psk', 'ssid TESTE user-group staff'],
      save: true,
    })
  })

  it('wires PPSK via RADIUS (server + forward auth) via apply-config', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    const onApply = vi.fn()
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} onApply={onApply} />)
    await screen.findByRole('button', { name: /apply ppsk radius/i })
    fireEvent.change(screen.getByLabelText(/radius server/i), { target: { value: '10.0.0.5' } })
    fireEvent.change(screen.getByLabelText(/shared secret/i), { target: { value: 'topsecret' } })
    fireEvent.change(screen.getByLabelText(/forward auth method/i), { target: { value: 'chap' } })
    fireEvent.click(screen.getByRole('button', { name: /apply ppsk radius/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'aaa ppsk-server radius-server primary 10.0.0.5 shared-secret topsecret',
        'security-object TESTE security private-psk radius-auth chap',
      ],
      save: true,
    })
  })

  it('applies per-SSID QoS (classifier + marker + WMM) via apply-config', async () => {
    const loadSsids = vi.fn().mockResolvedValue(ssids)
    const onApply = vi.fn()
    render(<WifiSection device={device} loadSsids={loadSsids} configureSsid={vi.fn()} onApply={onApply} />)
    await screen.findByRole('button', { name: /apply qos/i })
    fireEvent.change(screen.getByLabelText(/qos classifier profile/i), { target: { value: 'voip-class' } })
    fireEvent.change(screen.getByLabelText(/qos marker profile/i), { target: { value: 'voip-mark' } })
    fireEvent.change(screen.getByLabelText(/^wmm$/i), { target: { value: 'enable' } })
    fireEvent.click(screen.getByRole('button', { name: /apply qos/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ssid TESTE qos-classifier voip-class', 'ssid TESTE qos-marker voip-mark', 'ssid TESTE wmm'],
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
