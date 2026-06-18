import { useState } from 'react'
import {
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriInput,
  MriButton,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { Smartphone, ArrowUpDown } from 'lucide-react'
import { filterStations, sortBySignal, countBySsid } from '../../lib/stations'

/**
 * The AP's connected wireless clients (stations): a free-text search, a sort-by-signal toggle, and a per-SSID
 * count. Pure presentation over the already-parsed `stations` from the live inventory; the data work
 * (filter/sort/count) lives in ../../lib/stations so it is unit-tested without the DOM. Columns are MAC, IP,
 * SSID and signal — all a standalone AP actually reports. There is no Host/OS column on purpose: confirmed via
 * the live CLI, `show station` carries no hostname and the AP has no host table (in bridge mode a client's
 * hostname lives on the upstream DHCP server, not the AP). The search still matches hostname for the day a
 * non-AP source can supply one.
 */
export function ClientsTable({ stations = [] }) {
  const [query, setQuery] = useState('')
  const [dir, setDir] = useState('desc')

  const total = stations.length
  const perSsid = countBySsid(stations)
  const rows = sortBySignal(filterStations(stations, query), dir)

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <Smartphone className="h-4 w-4 text-muted-foreground" />
        <span className="text-sm font-medium">Connected clients</span>
        <MriStatusBadge label={String(total)} variant="outline" size="xs" />
        {perSsid.length > 1 &&
          perSsid.map((p) => (
            <MriStatusBadge key={p.ssid} label={`${p.ssid}: ${p.count}`} variant="ghost" size="xs" />
          ))}
        {total > 0 && (
          <>
            <span className="flex-1" />
            <MriInput
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search clients…"
              className="h-8 w-full max-w-xs text-xs"
            />
            <MriButton
              size="sm"
              variant="ghost"
              onClick={() => setDir(dir === 'desc' ? 'asc' : 'desc')}
              title="Sort by signal"
            >
              <ArrowUpDown className="mr-1 h-4 w-4" />
              Signal {dir === 'desc' ? '↓' : '↑'}
            </MriButton>
          </>
        )}
      </div>

      {total === 0 ? (
        <p className="text-sm text-muted-foreground">No clients associated.</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">No clients match the search.</p>
      ) : (
        <MriTable>
          <MriTableHeader>
            <MriTableRow>
              <MriTableHead>MAC</MriTableHead>
              <MriTableHead>IP</MriTableHead>
              <MriTableHead>SSID</MriTableHead>
              <MriTableHead>Signal (RSSI)</MriTableHead>
            </MriTableRow>
          </MriTableHeader>
          <MriTableBody>
            {rows.map((s, i) => (
              <MriTableRow key={s.mac || i}>
                <MriTableCell className="font-mono text-xs">{s.mac || '—'}</MriTableCell>
                <MriTableCell className="font-mono text-xs">{s.ipAddress || '—'}</MriTableCell>
                <MriTableCell>{s.ssid || '—'}</MriTableCell>
                <MriTableCell className="font-mono text-xs">{s.rssi ?? '—'}</MriTableCell>
              </MriTableRow>
            ))}
          </MriTableBody>
        </MriTable>
      )}
    </div>
  )
}
