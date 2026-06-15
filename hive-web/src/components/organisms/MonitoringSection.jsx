import { useCallback, useEffect, useState } from 'react'
import {
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriButton,
  MriStatusBadge,
  MriSectionHeader,
} from '@mriqbox/ui-kit'
import { Activity, Wifi, Radio as RadioIcon, Send, History } from 'lucide-react'
import { SchemaConfigForm } from './SchemaConfigForm'

// Syslog severity -> badge variant for the recent-log view.
const LEVEL_VARIANT = {
  emerg: 'destructive',
  alert: 'destructive',
  crit: 'destructive',
  err: 'destructive',
  error: 'destructive',
  warning: 'warning',
  warn: 'warning',
  notice: 'warning',
  info: 'outline',
  debug: 'ghost',
}

function Fact({ label, value }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="font-mono text-xs">{value || '—'}</div>
    </div>
  )
}

/**
 * The device's Monitoring tab: a live snapshot pulled from the AP through its agent (connected clients, radios,
 * and system health — the already-parsed `inventory` result, no new backend) on top, and the telemetry the AP
 * pushes out (SNMP identity + a syslog destination) below. Live reads need the agent online; the config below
 * applies whenever. `loadStatus(device)` returns the parsed Device snapshot; keep it stable (useCallback) so the
 * auto-load fires once, not on every render.
 */
export function MonitoringSection({ device, online, loadStatus, loadLog, snmpSection, syslogSection, onApply, busy }) {
  const [live, setLive] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [log, setLog] = useState(null)
  const [logBusy, setLogBusy] = useState(false)
  const [logError, setLogError] = useState('')

  const refresh = useCallback(async () => {
    if (!online) return
    setLoading(true)
    setError('')
    try {
      setLive(await loadStatus(device))
    } catch (e) {
      setError(e.message)
      setLive(null)
    } finally {
      setLoading(false)
    }
  }, [online, loadStatus, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  // The log is an on-demand read (it pulls the whole buffer, ~0.5 MB), so it is loaded by a button, not auto.
  const fetchLog = async () => {
    setLogBusy(true)
    setLogError('')
    try {
      setLog(await loadLog(device))
    } catch (e) {
      setLogError(e.message)
    } finally {
      setLogBusy(false)
    }
  }

  const stations = live?.stations || []
  const radios = live?.radios || []

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <MriSectionHeader icon={Activity} title="Live status" />
            {live?.cloud?.known &&
              (live.cloud.managed ? (
                <MriStatusBadge label="Managed (cloud)" variant="warning" size="xs" />
              ) : (
                <MriStatusBadge label="Standalone" variant="success" size="xs" />
              ))}
          </div>
          <MriButton size="sm" variant="ghost" disabled={!online || loading} onClick={refresh}>
            Refresh
          </MriButton>
        </div>

        {!online ? (
          <p className="text-sm text-muted-foreground">Agent offline — live status is unavailable.</p>
        ) : loading ? (
          <p className="text-sm text-muted-foreground">Reading live status from the AP…</p>
        ) : error ? (
          <p className="text-sm text-destructive">{error}</p>
        ) : live ? (
          <div className="space-y-4">
            <div className="grid gap-3 rounded-lg border border-border bg-card p-4 sm:grid-cols-2 lg:grid-cols-4">
              <Fact label="Firmware" value={live.firmwareVersion} />
              <Fact label="Uptime" value={live.uptime} />
              <Fact label="Hive" value={live.hiveName} />
              <Fact label="Hostname" value={live.hostname} />
            </div>

            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <Wifi className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-medium">Connected clients</span>
                <MriStatusBadge label={String(stations.length)} variant="outline" size="xs" />
              </div>
              {stations.length === 0 ? (
                <p className="text-sm text-muted-foreground">No clients associated.</p>
              ) : (
                <MriTable>
                  <MriTableHeader>
                    <MriTableRow>
                      <MriTableHead>Host</MriTableHead>
                      <MriTableHead>MAC</MriTableHead>
                      <MriTableHead>IP</MriTableHead>
                      <MriTableHead>SSID</MriTableHead>
                      <MriTableHead>OS</MriTableHead>
                      <MriTableHead>Signal (RSSI)</MriTableHead>
                    </MriTableRow>
                  </MriTableHeader>
                  <MriTableBody>
                    {stations.map((s, i) => (
                      <MriTableRow key={s.mac || i}>
                        <MriTableCell>{s.hostname || '—'}</MriTableCell>
                        <MriTableCell className="font-mono text-xs">{s.mac || '—'}</MriTableCell>
                        <MriTableCell className="font-mono text-xs">{s.ipAddress || '—'}</MriTableCell>
                        <MriTableCell>{s.ssid || '—'}</MriTableCell>
                        <MriTableCell>{s.osType || '—'}</MriTableCell>
                        <MriTableCell className="font-mono text-xs">{s.rssi ?? '—'}</MriTableCell>
                      </MriTableRow>
                    ))}
                  </MriTableBody>
                </MriTable>
              )}
            </div>

            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <RadioIcon className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-medium">Radios</span>
              </div>
              {radios.length === 0 ? (
                <p className="text-sm text-muted-foreground">No radio data.</p>
              ) : (
                <MriTable>
                  <MriTableHeader>
                    <MriTableRow>
                      <MriTableHead>Radio</MriTableHead>
                      <MriTableHead>Mode</MriTableHead>
                      <MriTableHead>Channel</MriTableHead>
                      <MriTableHead>Width</MriTableHead>
                      <MriTableHead>Tx power (dBm)</MriTableHead>
                      <MriTableHead>Auto-select</MriTableHead>
                    </MriTableRow>
                  </MriTableHeader>
                  <MriTableBody>
                    {radios.map((r, i) => (
                      <MriTableRow key={r.name || i}>
                        <MriTableCell className="font-mono text-xs">{r.name || '—'}</MriTableCell>
                        <MriTableCell>{r.mode || '—'}</MriTableCell>
                        <MriTableCell>{r.channel ?? '—'}</MriTableCell>
                        <MriTableCell>{r.width != null ? `${r.width} MHz` : '—'}</MriTableCell>
                        <MriTableCell>{r.txPower ?? r.power ?? '—'}</MriTableCell>
                        <MriTableCell>{r.auto || '—'}</MriTableCell>
                      </MriTableRow>
                    ))}
                  </MriTableBody>
                </MriTable>
              )}
            </div>
          </div>
        ) : null}
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <div className="flex items-center justify-between">
          <MriSectionHeader icon={History} title="Recent log" />
          <MriButton size="sm" variant="ghost" disabled={!online || logBusy} onClick={fetchLog}>
            {log ? 'Refresh' : 'Load'}
          </MriButton>
        </div>
        {!online ? (
          <p className="text-sm text-muted-foreground">Agent offline — the log is unavailable.</p>
        ) : logBusy ? (
          <p className="text-sm text-muted-foreground">Reading the AP log…</p>
        ) : logError ? (
          <p className="text-sm text-destructive">{logError}</p>
        ) : log == null ? (
          <p className="text-sm text-muted-foreground">Load the AP&apos;s most recent log entries.</p>
        ) : log.length === 0 ? (
          <p className="text-sm text-muted-foreground">No log entries.</p>
        ) : (
          <div className="max-h-96 overflow-y-auto rounded-md border border-border">
            <table className="w-full text-xs">
              <tbody>
                {log.map((e, i) => (
                  <tr key={i} className="border-b border-border/50 align-top last:border-0">
                    <td className="whitespace-nowrap px-2 py-1 font-mono text-muted-foreground">{e.time}</td>
                    <td className="px-2 py-1">
                      <MriStatusBadge
                        label={e.level}
                        variant={LEVEL_VARIANT[e.level?.toLowerCase()] || 'outline'}
                        size="xs"
                      />
                    </td>
                    <td className="px-2 py-1 font-mono">{e.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="space-y-4 border-t border-border pt-5">
        <MriSectionHeader icon={Send} title="Telemetry & log forwarding" />
        <p className="text-xs text-muted-foreground">
          Where the AP pushes its own telemetry: an SNMP identity for your NMS, and a syslog destination for its
          logs.
        </p>
        <div className="grid items-start gap-8 xl:grid-cols-2">
          <SchemaConfigForm section={snmpSection} device={device} onApply={onApply} busy={busy} />
          <SchemaConfigForm section={syslogSection} device={device} onApply={onApply} busy={busy} />
        </div>
      </div>
    </div>
  )
}
