import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { PolicySection } from './PolicySection'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }
const profiles = [
  { name: 'default-profile', attribute: 0, vlanId: 1, vlanGroup: null, qosPolicy: null, schedule: null, boundTo: [] },
  { name: 'HK-JOB', attribute: 7, vlanId: 7, vlanGroup: null, qosPolicy: 'def-user-qos', schedule: null, boundTo: ['HK-JOB'] },
]
const ssids = [{ name: 'HK-JOB' }, { name: 'TESTE' }]

const loadOk = (data = { profiles, ssids }) => vi.fn().mockResolvedValue(data)

describe('PolicySection', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the user profiles loaded from the AP with their attribute, VLAN and binding', async () => {
    const loadPolicy = loadOk()
    render(<PolicySection device={device} loadPolicy={loadPolicy} onApply={vi.fn()} />)
    const list = within(await screen.findByTestId('profile-list'))
    expect(list.getByText('HK-JOB')).toBeInTheDocument()
    expect(list.getByText('default-profile')).toBeInTheDocument()
    expect(list.getByText('attr 7')).toBeInTheDocument()
    expect(list.getByText('VLAN 7')).toBeInTheDocument()
    expect(list.getByText(/↔ HK-JOB/)).toBeInTheDocument()
    expect(loadPolicy).toHaveBeenCalledWith(device)
  })

  it('shows an empty state when there are no user profiles', async () => {
    render(<PolicySection device={device} loadPolicy={loadOk({ profiles: [], ssids: [] })} onApply={vi.fn()} />)
    expect(await screen.findByText(/no user profiles/i)).toBeInTheDocument()
  })

  it('applies a new profile as separate lines (attribute first), then VLAN id', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk({ profiles: [], ssids: [] })} onApply={onApply} />)
    await screen.findByText(/no user profiles/i)
    fireEvent.change(screen.getByPlaceholderText('staff'), { target: { value: 'staff' } })
    fireEvent.change(screen.getByPlaceholderText('e.g. 7'), { target: { value: '5' } })
    fireEvent.change(screen.getByPlaceholderText('e.g. 10'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['user-profile staff attribute 5', 'user-profile staff vlan-id 5'],
      save: true,
    })
  })

  it('uses a VLAN group when that type is picked', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk({ profiles: [], ssids: [] })} onApply={onApply} />)
    await screen.findByText(/no user profiles/i)
    fireEvent.change(screen.getByPlaceholderText('staff'), { target: { value: 'guest' } })
    fireEvent.change(screen.getByPlaceholderText('e.g. 7'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText(/VLAN type/i), { target: { value: 'group' } })
    fireEvent.change(screen.getByPlaceholderText('guest-vlans'), { target: { value: 'guest-vlans' } })
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['user-profile guest attribute 3', 'user-profile guest vlan-group guest-vlans'],
      save: true,
    })
  })

  it('binds a profile to an SSID by its attribute', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk()} onApply={onApply} />)
    await screen.findByTestId('profile-list')
    // default selections: first SSID (HK-JOB) + first profile (default-profile, attr 0)
    fireEvent.click(screen.getByRole('button', { name: /bind profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['security-object HK-JOB default-user-profile-attr 0'],
      save: true,
    })
  })

  it('removes a profile after confirmation', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<PolicySection device={device} loadPolicy={loadOk()} onApply={onApply} />)
    await screen.findByTestId('profile-list')
    fireEvent.click(screen.getAllByRole('button', { name: /^remove$/i })[1])
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['no user-profile HK-JOB'], save: true })
  })

  it('does not remove when the confirmation is dismissed', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<PolicySection device={device} loadPolicy={loadOk()} onApply={onApply} />)
    await screen.findByTestId('profile-list')
    fireEvent.click(within(screen.getByTestId('profile-list')).getAllByRole('button', { name: /^remove$/i })[0])
    expect(onApply).not.toHaveBeenCalled()
  })

  it('applies advanced policy (performance sentinel) to the selected profile', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk()} onApply={onApply} />)
    await screen.findByTestId('profile-list')
    // Profile select defaults to the first profile (default-profile).
    fireEvent.change(screen.getByLabelText('Performance sentinel'), { target: { value: 'enable' } })
    fireEvent.click(screen.getByRole('button', { name: /apply policy/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['user-profile default-profile performance-sentinel enable'],
      save: true,
    })
  })

  it('binds a named IP firewall policy to a profile direction', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    const data = { profiles, ssids, firewall: { ip: ['block-smb'], mac: [] }, qos: [] }
    render(<PolicySection device={device} loadPolicy={loadOk(data)} onApply={onApply} />)
    await screen.findByTestId('profile-list')
    fireEvent.change(screen.getByLabelText('IP policy (from client)'), { target: { value: 'block-smb' } })
    fireEvent.click(screen.getByRole('button', { name: /apply policy/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['user-profile default-profile security ip-policy from-access block-smb'],
      save: true,
    })
  })

  it('creates an IP policy with a rule (blanks default to any)', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk({ profiles: [], ssids: [] })} onApply={onApply} />)
    await screen.findByText(/no user profiles/i)
    fireEvent.change(screen.getByPlaceholderText('block-smb'), { target: { value: 'web' } })
    fireEvent.change(screen.getByPlaceholderText('1'), { target: { value: '1' } })
    // action defaults to deny
    fireEvent.click(screen.getByRole('button', { name: /create ip policy/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ip-policy web', 'ip-policy web id 1 from any to any service any action deny'],
      save: true,
    })
  })

  it('creates a MAC policy', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk({ profiles: [], ssids: [] })} onApply={onApply} />)
    await screen.findByText(/no user profiles/i)
    fireEvent.change(screen.getByPlaceholderText('mac-allow'), { target: { value: 'guests' } })
    fireEvent.click(screen.getByRole('button', { name: /create mac policy/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['mac-policy guests'], save: true })
  })

  it('removes a firewall policy from the list after confirmation', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const data = { profiles: [], ssids: [], firewall: { ip: ['block-smb'], mac: [] }, qos: [] }
    render(<PolicySection device={device} loadPolicy={loadOk(data)} onApply={onApply} />)
    const list = within(await screen.findByTestId('firewall-list'))
    fireEvent.click(list.getByRole('button', { name: /^remove$/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['no ip-policy block-smb'], save: true })
  })

  it('creates a QoS policy with a rate limit and enables QoS globally', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<PolicySection device={device} loadPolicy={loadOk({ profiles: [], ssids: [] })} onApply={onApply} />)
    await screen.findByText(/no user profiles/i)
    fireEvent.change(screen.getByPlaceholderText('voip'), { target: { value: 'voip' } })
    fireEvent.change(screen.getByPlaceholderText('0-2000000'), { target: { value: '5000' } })
    fireEvent.click(screen.getByLabelText(/enable qos globally/i))
    fireEvent.click(screen.getByRole('button', { name: /apply qos policy/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['qos enable', 'qos policy voip', 'qos policy voip user-profile 5000 10'],
      save: true,
    })
  })
})
