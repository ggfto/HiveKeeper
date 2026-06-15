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
  it('auto-loads the live snapshot and lists connected clients + radios when online', async () => {
    const loadStatus = vi.fn().mockResolvedValue({
      firmwareVersion: '10.6r7',
      uptime: '5d 2h',
      hiveName: 'hive0',
      hostname: 'AH-827200',
      stations: [{ mac: 'aa:bb:cc', ipAddress: '10.0.0.9', hostname: 'phone', ssid: 'HK', osType: 'iOS', rssi: -55 }],
      radios: [{ name: 'wifi0', mode: 'access', channel: 36, power: '12' }],
    })
    renderSection({ online: true, loadStatus })
    expect(await screen.findByText('phone')).toBeInTheDocument() // a connected client
    expect(screen.getByText('10.0.0.9')).toBeInTheDocument()
    expect(screen.getByText('wifi0')).toBeInTheDocument() // a radio
    expect(screen.getByText('36')).toBeInTheDocument() // its channel
    expect(screen.getByText('10.6r7')).toBeInTheDocument() // system health
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
