import {
  MriButton,
  MriInput,
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { evaluateAlerts, worstSeverity } from '../../lib/alerts'

const SEVERITY_VARIANT = { critical: 'destructive', warning: 'warning', info: 'outline' }

/**
 * Fleet alerts: an on-demand scan of every device (its agent online-state + live inventory snapshot) evaluated
 * against the threshold rules, listing only the devices with active alerts. The raw snapshots are held by the
 * page; this panel re-evaluates them against the current thresholds on every render, so editing a threshold
 * updates the view live without re-scanning. `scan` is null before the first scan, then
 * [{ device, online, snapshot, error }].
 */
export function FleetAlertsPanel({ scan, scanning, scannedAt, onScan, thresholds, onThresholds, busy }) {
  const rows = (scan || []).map((r) => ({
    ...r,
    alerts: r.error
      ? [{ id: 'scan-failed', severity: 'warning', message: `Scan failed: ${r.error}` }]
      : evaluateAlerts({ online: r.online, snapshot: r.snapshot }, thresholds),
  }))
  const alerting = rows.filter((r) => r.alerts.length > 0)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1" htmlFor="alert-max-clients">
          <span className="text-xs text-muted-foreground">Max clients per AP (warn above)</span>
          <MriInput
            id="alert-max-clients"
            className="w-32"
            value={String(thresholds?.maxStations ?? '')}
            onChange={(e) => onThresholds?.({ ...thresholds, maxStations: Number(e.target.value) || 0 })}
            placeholder="30"
          />
        </label>
        <MriButton disabled={busy || scanning} onClick={onScan}>
          {scanning ? 'Scanning…' : 'Scan fleet'}
        </MriButton>
        {scannedAt && <span className="text-xs text-muted-foreground">Scanned {scannedAt.toLocaleTimeString()}</span>}
      </div>

      {scan == null ? (
        <p className="text-sm text-muted-foreground">Scan the fleet to evaluate each AP against the alert thresholds.</p>
      ) : alerting.length === 0 ? (
        <p className="text-sm text-muted-foreground">No active alerts — all {rows.length} scanned device(s) are healthy.</p>
      ) : (
        <div className="space-y-2" data-testid="fleet-alerts">
          <p className="text-sm text-muted-foreground">
            {alerting.length} of {rows.length} devices have active alerts.
          </p>
          <MriTable>
            <MriTableHeader>
              <MriTableRow>
                <MriTableHead>Device</MriTableHead>
                <MriTableHead>Severity</MriTableHead>
                <MriTableHead>Alerts</MriTableHead>
              </MriTableRow>
            </MriTableHeader>
            <MriTableBody>
              {alerting.map((r) => {
                const worst = worstSeverity(r.alerts)
                return (
                  <MriTableRow key={r.device.deviceId}>
                    <MriTableCell>
                      <div className="font-medium">{r.device.label || r.device.serial}</div>
                      <div className="font-mono text-xs text-muted-foreground">{r.device.mgmtIp}</div>
                    </MriTableCell>
                    <MriTableCell>
                      <MriStatusBadge label={worst} variant={SEVERITY_VARIANT[worst] || 'outline'} size="xs" />
                    </MriTableCell>
                    <MriTableCell>
                      <ul className="space-y-0.5 text-xs">
                        {r.alerts.map((a) => (
                          <li key={a.id}>{a.message}</li>
                        ))}
                      </ul>
                    </MriTableCell>
                  </MriTableRow>
                )
              })}
            </MriTableBody>
          </MriTable>
        </div>
      )}
    </div>
  )
}
