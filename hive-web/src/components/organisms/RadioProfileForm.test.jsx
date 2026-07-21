import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { RadioProfileForm } from './RadioProfileForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('RadioProfileForm', () => {
  it('applies channel width, band-steering and load-balance for the default 5 GHz profile', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/channel width/i), { target: { value: '40' } })
    fireEvent.change(screen.getByLabelText(/band steering/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/client load.?balanc/i), { target: { value: 'enable' } })
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'radio profile radio_ac0 channel-width 40',
        'radio profile radio_ac0 band-steering',
        'radio profile radio_ac0 client-load-balance',
      ],
      save: true,
    })
  })

  it('does nothing when no field is set', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).not.toHaveBeenCalled()
  })

  it('warns (but still applies) a wide channel on the 2.4 GHz profile', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'radio_ng0' } })
    fireEvent.change(screen.getByLabelText(/channel width/i), { target: { value: '40' } })
    expect(screen.getByTestId('profile-advisories')).toHaveTextContent(/2\.4 GHz/i)
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['radio profile radio_ng0 channel-width 40'],
      save: true,
    })
  })

  it('applies the advanced RF tuning knobs from the disclosure, phymode first', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    // Use a custom profile name: HiveOS rejects edits to the default radio_ac0 (confirmed live).
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'hk_5g_dense' } })
    fireEvent.change(screen.getByLabelText(/^DFS/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/high-density optimizations/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/tx beamforming/i), { target: { value: 'explicit-only' } })
    fireEvent.change(screen.getByLabelText(/phy mode/i), { target: { value: '11ac' } })
    fireEvent.change(screen.getByLabelText(/receive chains/i), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'radio profile hk_5g_dense phymode 11ac',
        'radio profile hk_5g_dense dfs',
        'radio profile hk_5g_dense high-density enable',
        'radio profile hk_5g_dense tx-beamforming explicit-only',
        'radio profile hk_5g_dense receive-chain 2',
      ],
      save: true,
    })
  })

  it('warns when the profile is a factory default (radio_ac0), and clears the warning for a custom name', () => {
    render(<RadioProfileForm device={device} onApply={vi.fn()} />)
    // Default profile pre-filled → warning shown.
    expect(screen.getByTestId('default-profile-warning')).toHaveTextContent(/default/i)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'hk_5g_dense' } })
    expect(screen.queryByTestId('default-profile-warning')).not.toBeInTheDocument()
  })

  it('binds a configured custom profile to a radio (bind line emitted last) with a disruption warning', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'hk_5g_dense' } })
    fireEvent.change(screen.getByLabelText(/phy mode/i), { target: { value: '11ac' } })
    fireEvent.change(screen.getByLabelText(/channel width/i), { target: { value: '80' } })
    fireEvent.change(screen.getByLabelText(/bind to radio/i), { target: { value: 'wifi1' } })
    expect(screen.getByTestId('bind-warning')).toHaveTextContent(/disrupts its wireless clients/i)
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'radio profile hk_5g_dense phymode 11ac',
        'radio profile hk_5g_dense channel-width 80',
        'interface wifi1 radio profile hk_5g_dense',
      ],
      save: true,
    })
  })

  it('flags a band mismatch (an 11ac profile bound to the 2.4 GHz radio)', () => {
    render(<RadioProfileForm device={device} onApply={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'hk_dense' } })
    fireEvent.change(screen.getByLabelText(/phy mode/i), { target: { value: '11ac' } })
    fireEvent.change(screen.getByLabelText(/bind to radio/i), { target: { value: 'wifi0' } })
    expect(screen.getByTestId('bind-warning')).toHaveTextContent(/band mismatch/i)
  })

  it('judges a Wi-Fi 6 2.4 GHz profile by 2.4 GHz rules', () => {
    // radio_axg0 is the factory 2.4 GHz profile on Wi-Fi 6 hardware (g = 2.4, a = 5). Matching a bare `ax`
    // substring used to read it as 5 GHz, which silently dropped the one width warning that matters most:
    // anything wider than 20 MHz on 2.4 GHz overlaps the band.
    render(<RadioProfileForm device={device} onApply={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'radio_axg0' } })
    fireEvent.change(screen.getByLabelText(/channel width/i), { target: { value: '40' } })
    expect(screen.getByTestId('profile-advisories')).toHaveTextContent(/2\.4 GHz/i)
  })

  it('applies the Wi-Fi 6 knobs from the 802.11ax disclosure', () => {
    const onApply = vi.fn()
    render(<RadioProfileForm device={device} onApply={onApply} />)
    fireEvent.change(screen.getByLabelText(/radio profile/i), { target: { value: 'hk_ax' } })
    fireEvent.change(screen.getByLabelText(/OFDMA downlink/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/TWT/i), { target: { value: 'enable' } })
    fireEvent.change(screen.getByLabelText(/BSS color/i), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: [
        'radio profile hk_ax 11ax bss-color 12',
        'radio profile hk_ax 11ax ofdma-dl',
        'radio profile hk_ax 11ax twt',
      ],
      save: true,
    })
  })

  it('preloads an existing profile from the AP so you adjust from its current values', async () => {
    const onApply = vi.fn()
    const loadProfiles = vi.fn().mockResolvedValue([
      { name: 'hk_ax_5g', phymode: '11ax-5g', channelWidth: '80', bandSteering: 'enable',
        bssColor: '12', twt: 'enable', boundInterfaces: ['wifi1'] },
    ])
    render(<RadioProfileForm device={device} onApply={onApply} loadProfiles={loadProfiles} />)

    // The existing profile appears in the loader dropdown once loadProfiles resolves.
    await waitFor(() => expect(screen.getByLabelText(/carregar um perfil existente/i)).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText(/carregar um perfil existente/i), { target: { value: 'hk_ax_5g' } })

    // The form now holds the AP's current values; applying re-emits them (idempotent) plus any change.
    expect(screen.getByLabelText(/radio profile/i)).toHaveValue('hk_ax_5g')
    fireEvent.click(screen.getByRole('button', { name: /apply profile/i }))
    const cmds = onApply.mock.calls[0][1].commands
    expect(cmds).toContain('radio profile hk_ax_5g phymode 11ax-5g')
    expect(cmds).toContain('radio profile hk_ax_5g channel-width 80')
    expect(cmds).toContain('radio profile hk_ax_5g 11ax bss-color 12')
    expect(cmds).toContain('radio profile hk_ax_5g 11ax twt')
    expect(cmds).toContain('interface wifi1 radio profile hk_ax_5g')
  })

  it('is create-only (no loader) when no loadProfiles is given', () => {
    render(<RadioProfileForm device={device} onApply={vi.fn()} />)
    expect(screen.queryByLabelText(/carregar um perfil existente/i)).not.toBeInTheDocument()
  })
})
