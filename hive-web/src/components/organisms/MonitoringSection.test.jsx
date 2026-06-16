import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MonitoringSection } from './MonitoringSection'
import { MONITORING_SECTIONS } from '../../lib/configSchema'

const [snmpSection, syslogSection] = MONITORING_SECTIONS
const device = { deviceId: 'd1', agentId: 'a1', mgmtIp: '10.0.0.1' }

function renderSection(props) {
  return render(
    <MonitoringSection
      device={device}
      snmpSection={snmpSection}
      syslogSection={syslogSection}
      onApply={vi.fn()}
      {...props}
    />,
  )
}

describe('MonitoringSection', () => {
  it('auto-loads the live snapshot and lists clients, radios, cloud state when online', async () => {
    const loadStatus = vi.fn().mockResolvedValue({
      firmwareVersion: '10.6r1a',
      uptime: '5d 2h',
      hiveName: 'hk-mesh',
      hostname: 'AH-827200',
      cloud: { known: true, managed: false },
      stations: [{ mac: 'aa:bb:cc', ipAddress: '10.0.0.9', hostname: 'phone', ssid: 'HK', osType: 'iOS', rssi: -55 }],
      radios: [{ name: 'Wifi0', mode: 'access', channel: 11, power: null, width: 20, txPower: 18, auto: 'Enable' }],
    })
    renderSection({ online: true, loadStatus })
    expect(await screen.findByText('aa:bb:cc')).toBeInTheDocument() // a connected client (its MAC)
    expect(screen.getByText('10.0.0.9')).toBeInTheDocument()
    expect(screen.getByText('Wifi0')).toBeInTheDocument() // a radio
    expect(screen.getByText('11')).toBeInTheDocument() // its channel (from show acsp)
    expect(screen.getByText('18')).toBeInTheDocument() // its Tx power, which inventory leaves null
    expect(screen.getByText('10.6r1a')).toBeInTheDocument() // system health
    expect(screen.getByText(/standalone/i)).toBeInTheDocument() // cloud state: not phoning home
    expect(screen.getByText(/updated/i)).toBeInTheDocument() // last-updated stamp after the snapshot loads
    // the merged telemetry config (SNMP + Syslog) is on the same tab
    expect(screen.getByRole('button', { name: /apply snmp/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /apply syslog/i })).toBeInTheDocument()
  })

  it('shows an empty-clients note when nothing is associated', async () => {
    const loadStatus = vi.fn().mockResolvedValue({ stations: [], radios: [] })
    renderSection({ online: true, loadStatus })
    expect(await screen.findByText(/no clients associated/i)).toBeInTheDocument()
  })

  it('loads the recent log on demand (not automatically)', async () => {
    const loadStatus = vi.fn().mockResolvedValue({ stations: [], radios: [] })
    const loadLog = vi.fn().mockResolvedValue([
      { time: '2026-06-15 20:19:32', level: 'error', message: 'kernel: something failed' },
    ])
    renderSection({ online: true, loadStatus, loadLog })
    await screen.findByText(/no clients associated/i)
    expect(loadLog).not.toHaveBeenCalled() // on-demand, not auto
    fireEvent.click(screen.getByRole('button', { name: /^load$/i }))
    expect(await screen.findByText(/kernel: something failed/)).toBeInTheDocument()
    expect(screen.getByText('error')).toBeInTheDocument() // severity badge

    // the search box filters the loaded entries client-side
    const search = screen.getByPlaceholderText(/search log/i)
    fireEvent.change(search, { target: { value: 'zzz-nomatch' } })
    expect(screen.getByText(/no entries match/i)).toBeInTheDocument()
    expect(screen.queryByText(/kernel: something failed/)).not.toBeInTheDocument()
    fireEvent.change(search, { target: { value: 'kernel' } })
    expect(screen.getByText(/kernel: something failed/)).toBeInTheDocument()
  })

  it('does not pull live status when the agent is offline', () => {
    const loadStatus = vi.fn()
    renderSection({ online: false, loadStatus })
    expect(loadStatus).not.toHaveBeenCalled()
    expect(screen.getByText(/live status is unavailable/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /refresh/i })).toBeDisabled()
    // config still editable even when the AP is unreachable (it is queued/applied when back)
    expect(screen.getByRole('button', { name: /apply snmp/i })).toBeInTheDocument()
  })
})
