import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
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
    expect(await screen.findByText('phone')).toBeInTheDocument() // a connected client
    expect(screen.getByText('10.0.0.9')).toBeInTheDocument()
    expect(screen.getByText('Wifi0')).toBeInTheDocument() // a radio
    expect(screen.getByText('11')).toBeInTheDocument() // its channel (from show acsp)
    expect(screen.getByText('18')).toBeInTheDocument() // its Tx power, which inventory leaves null
    expect(screen.getByText('10.6r1a')).toBeInTheDocument() // system health
    expect(screen.getByText(/standalone/i)).toBeInTheDocument() // cloud state: not phoning home
    // the merged telemetry config (SNMP + Syslog) is on the same tab
    expect(screen.getByRole('button', { name: /apply snmp/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /apply syslog/i })).toBeInTheDocument()
  })

  it('shows an empty-clients note when nothing is associated', async () => {
    const loadStatus = vi.fn().mockResolvedValue({ stations: [], radios: [] })
    renderSection({ online: true, loadStatus })
    expect(await screen.findByText(/no clients associated/i)).toBeInTheDocument()
  })

  it('does not pull live status when the agent is offline', () => {
    const loadStatus = vi.fn()
    renderSection({ online: false, loadStatus })
    expect(loadStatus).not.toHaveBeenCalled()
    expect(screen.getByText(/agent offline/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /refresh/i })).toBeDisabled()
    // config still editable even when the AP is unreachable (it is queued/applied when back)
    expect(screen.getByRole('button', { name: /apply snmp/i })).toBeInTheDocument()
  })
})
