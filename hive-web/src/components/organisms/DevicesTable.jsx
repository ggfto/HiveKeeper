import {
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriButton,
  MriStatusBadge,
  MriSelect,
} from '@mriqbox/ui-kit'
import { groupNamesFor, siteName } from '../../lib/fleet'

/**
 * The fleet table: one registered device per row with its identity, site, group tags, and per-device read
 * actions (inventory/backup, dispatched through the device's own agent + credential). A per-row select tags
 * the device into a group it is not already in.
 */
export function DevicesTable({ devices, groups = [], sites = [], onInventory, onBackup, onTag, onConfigure, busy }) {
  if (!devices || devices.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No devices yet. Open an agent, discover its LAN, then adopt a host into the fleet.
      </p>
    )
  }
  return (
    <MriTable>
      <MriTableHeader>
        <MriTableRow>
          <MriTableHead>Label</MriTableHead>
          <MriTableHead>Serial</MriTableHead>
          <MriTableHead>Model</MriTableHead>
          <MriTableHead>Mgmt IP</MriTableHead>
          <MriTableHead>Site</MriTableHead>
          <MriTableHead>Groups</MriTableHead>
          <MriTableHead className="text-right">Actions</MriTableHead>
        </MriTableRow>
      </MriTableHeader>
      <MriTableBody>
        {devices.map((d) => {
          const tagged = new Set(d.groups || [])
          const available = groups
            .filter((g) => !tagged.has(g.groupId))
            .map((g) => ({ label: g.name, value: g.groupId }))
          return (
            <MriTableRow key={d.deviceId}>
              <MriTableCell>{d.label || '—'}</MriTableCell>
              <MriTableCell className="font-mono text-xs">{d.serial}</MriTableCell>
              <MriTableCell>{d.model || '—'}</MriTableCell>
              <MriTableCell className="font-mono text-xs">{d.mgmtIp || '—'}</MriTableCell>
              <MriTableCell>{siteName(d.siteId, sites) || '—'}</MriTableCell>
              <MriTableCell>
                <div className="flex flex-wrap items-center gap-1">
                  {groupNamesFor(d, groups).map((name) => (
                    <MriStatusBadge key={name} label={name} variant="outline" size="xs" />
                  ))}
                  {tagged.size === 0 && <span className="text-muted-foreground">—</span>}
                </div>
              </MriTableCell>
              <MriTableCell>
                <div className="flex items-center justify-end gap-2">
                  {onTag && available.length > 0 && (
                    <div className="w-36">
                      <MriSelect
                        options={available}
                        value=""
                        placeholder="Tag into…"
                        size="sm"
                        onChange={(groupId) => groupId && onTag(d.deviceId, groupId)}
                      />
                    </div>
                  )}
                  <MriButton size="sm" variant="ghost" disabled={busy} onClick={() => onInventory?.(d)}>
                    Inventory
                  </MriButton>
                  <MriButton size="sm" variant="ghost" disabled={busy} onClick={() => onBackup?.(d)}>
                    Backup
                  </MriButton>
                  {onConfigure && (
                    <MriButton size="sm" variant="outline" disabled={busy} onClick={() => onConfigure(d)}>
                      Configure
                    </MriButton>
                  )}
                </div>
              </MriTableCell>
            </MriTableRow>
          )
        })}
      </MriTableBody>
    </MriTable>
  )
}
