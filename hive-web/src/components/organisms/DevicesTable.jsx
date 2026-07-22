import {
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { groupNamesFor, siteName } from '../../lib/fleet'

/**
 * The fleet table: one registered device per row, with a live status (whether ANY agent that can reach it is
 * currently connected), its reachable agents and site, and its group tags. Clicking a row opens the device's
 * management page. `online` is computed by the page from the connected-agents list.
 */
export function DevicesTable({ devices, groups = [], sites = [], onOpen, loading }) {
  if (!devices || devices.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        {loading
          ? 'Loading devices…'
          : 'No devices yet. Open an agent, discover its LAN, then adopt a host into the fleet.'}
      </p>
    )
  }
  return (
    <MriTable>
      <MriTableHeader>
        <MriTableRow>
          <MriTableHead>Status</MriTableHead>
          <MriTableHead>Name</MriTableHead>
          <MriTableHead>Serial</MriTableHead>
          <MriTableHead>Model</MriTableHead>
          <MriTableHead>Mgmt IP</MriTableHead>
          <MriTableHead>Agents</MriTableHead>
          <MriTableHead>Site</MriTableHead>
          <MriTableHead>Groups</MriTableHead>
        </MriTableRow>
      </MriTableHeader>
      <MriTableBody>
        {devices.map((d) => (
          <MriTableRow
            key={d.deviceId}
            onClick={() => onOpen?.(d)}
            className="cursor-pointer transition-colors hover:bg-muted"
          >
            <MriTableCell>
              <MriStatusBadge
                label={d.online ? 'online' : 'offline'}
                variant={d.online ? 'success' : 'outline'}
                size="xs"
              />
            </MriTableCell>
            <MriTableCell>{d.label || '—'}</MriTableCell>
            <MriTableCell className="font-mono text-xs">{d.serial}</MriTableCell>
            <MriTableCell>{d.model || '—'}</MriTableCell>
            <MriTableCell className="font-mono text-xs">{d.mgmtIp || '—'}</MriTableCell>
            <MriTableCell className="font-mono text-xs">
              {(d.reachableAgents || []).length ? d.reachableAgents.join(', ') : '—'}
            </MriTableCell>
            <MriTableCell>{siteName(d.siteId, sites) || '—'}</MriTableCell>
            <MriTableCell>
              <div className="flex flex-wrap gap-1">
                {groupNamesFor(d, groups).map((name) => (
                  <MriStatusBadge key={name} label={name} variant="outline" size="xs" />
                ))}
                {(d.groups || []).length === 0 && <span className="text-muted-foreground">—</span>}
              </div>
            </MriTableCell>
          </MriTableRow>
        ))}
      </MriTableBody>
    </MriTable>
  )
}
