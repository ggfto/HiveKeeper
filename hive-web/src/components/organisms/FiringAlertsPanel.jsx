import { useCallback, useEffect, useState } from 'react'
import {
  MriButton,
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'

const SEVERITY_VARIANT = { critical: 'destructive', warning: 'warning', info: 'outline' }

/**
 * The alerts the background poller is currently tracking as firing (server-side state, `GET /api/alerts/firing`).
 * Distinct from the on-demand scan above it: this is what the unattended poller has open right now and is
 * delivering on. Read-only; self-loads via the injected `loadFiring` handler so it is unit-testable. On the
 * no-poller (no-`postgres`) stack this is simply empty.
 */
export function FiringAlertsPanel({ loadFiring }) {
  const [alerts, setAlerts] = useState(undefined) // undefined=loading, null=unreachable, 'forbidden', array
  const [refreshing, setRefreshing] = useState(false)

  const reload = useCallback(() => {
    if (!loadFiring) return
    setRefreshing(true)
    loadFiring()
      .then((list) => setAlerts(Array.isArray(list) ? list : (list?.alerts ?? [])))
      .catch((e) => setAlerts(e?.status === 403 ? 'forbidden' : null))
      .finally(() => setRefreshing(false))
  }, [loadFiring])

  useEffect(() => {
    reload()
  }, [reload])

  return (
    <div className="space-y-2 rounded-lg border border-border bg-card p-4">
      <div className="flex items-center justify-between">
        <div>
          <div className="text-sm font-medium">Active alerts (poller)</div>
          <p className="text-xs text-muted-foreground">
            What the background poller is currently delivering on. Cleared automatically when the condition resolves.
          </p>
        </div>
        <MriButton size="sm" variant="secondary" disabled={refreshing} onClick={reload}>
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </MriButton>
      </div>

      {alerts === 'forbidden' && <p className="text-sm text-muted-foreground">Viewing alerts needs a role on this org.</p>}
      {alerts === null && <p className="text-sm text-muted-foreground">Gateway unreachable.</p>}
      {Array.isArray(alerts) && alerts.length === 0 && (
        <p className="text-sm text-muted-foreground">No active alerts — the poller has nothing open.</p>
      )}
      {Array.isArray(alerts) && alerts.length > 0 && (
        <MriTable>
          <MriTableHeader>
            <MriTableRow>
              <MriTableHead>Device</MriTableHead>
              <MriTableHead>Severity</MriTableHead>
              <MriTableHead>Alert</MriTableHead>
              <MriTableHead>Since</MriTableHead>
            </MriTableRow>
          </MriTableHeader>
          <MriTableBody>
            {alerts.map((a) => (
              <MriTableRow key={`${a.deviceId}/${a.alertId}`}>
                <MriTableCell className="font-mono text-xs">{a.deviceId}</MriTableCell>
                <MriTableCell>
                  <MriStatusBadge label={a.severity} variant={SEVERITY_VARIANT[a.severity] || 'outline'} size="xs" />
                </MriTableCell>
                <MriTableCell>
                  <div>{a.alertId}</div>
                  {a.message && <div className="text-xs text-muted-foreground">{a.message}</div>}
                </MriTableCell>
                <MriTableCell className="text-xs text-muted-foreground">
                  {a.firstSeen ? new Date(a.firstSeen).toLocaleString() : '—'}
                </MriTableCell>
              </MriTableRow>
            ))}
          </MriTableBody>
        </MriTable>
      )}
    </div>
  )
}
