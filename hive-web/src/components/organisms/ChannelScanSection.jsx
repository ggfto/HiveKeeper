import { useState } from 'react'
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
import { Radar, TriangleAlert } from 'lucide-react'
import { rankedChannels, channelCrowding } from '../../lib/hiveosParse'
import { bandForChannel } from '../../lib/radioInterfaces'

/**
 * What each radio can see of the air, and which channel it should be on.
 *
 * The costs shown are the AP's own. HiveOS runs ACSP, which scans and scores every permitted channel
 * continuously — so the honest thing is to surface that measurement and explain what drove it, not to
 * re-derive a score in the browser from a neighbour list. The AP has the radio; we do not.
 *
 * Read-only: nothing here changes a channel. Picking one is a decision with consequences for every client
 * on that radio, so it belongs to the operator, in the radio form, with the disruption warning attached.
 */

// A neighbour this loud is close enough to matter regardless of how many there are.
const LOUD_DBM = -60

function signalLabel(dbm) {
  if (dbm === null || dbm === undefined) return '—'
  if (dbm >= LOUD_DBM) return `${dbm} dBm (loud)`
  return `${dbm} dBm`
}

/** Why a channel scores the way it does, in words rather than a number. */
function explain(scan, channel, cost, isBest) {
  const { count, loudestDbm, ourFleet } = channelCrowding(scan, channel)
  const parts = []
  if (count === 0) {
    parts.push('nothing else heard here')
  } else {
    const others = count - ourFleet
    parts.push(`${count} AP${count === 1 ? '' : 's'} heard`)
    if (ourFleet > 0) {
      // Worth separating: your own APs coordinate over ACSP, a stranger's does not.
      parts.push(`${ourFleet} of them yours`)
    }
    if (others > 0 && loudestDbm !== null && loudestDbm >= LOUD_DBM) {
      parts.push(`loudest at ${loudestDbm} dBm`)
    }
  }
  if (isBest && cost === 0) parts.push('the AP found it completely clear')
  return parts.join(', ')
}

function RadioScan({ scan }) {
  const [showNeighbors, setShowNeighbors] = useState(false)
  const ranked = rankedChannels(scan)
  const best = ranked[0]
  const current = scan.currentChannel
  const band = bandForChannel(current) || bandForChannel(best?.channel)
  const alreadyBest = best && current === best.channel
  const unusable = scan.channels.filter((c) => !c.usable)

  return (
    <div className="space-y-3 rounded-md border border-border p-3" data-testid={`scan-${scan.iface}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm font-medium">{scan.iface}</span>
        {band && <MriStatusBadge label={band} variant="outline" />}
        {scan.state && <MriStatusBadge label={scan.state} variant="ghost" />}
        <span className="text-xs text-muted-foreground">
          on channel <strong className="font-mono">{current ?? '—'}</strong>
        </span>
      </div>

      {!best && (
        <p className="text-xs text-muted-foreground">
          No usable channel was reported. The radio may still be scanning — try again in a moment.
        </p>
      )}

      {best && (
        <div
          className={`flex items-start gap-2 rounded-md p-2 text-xs ${
            alreadyBest ? 'bg-muted text-muted-foreground' : 'border border-warning/40 bg-warning/10'
          }`}
          data-testid={`recommendation-${scan.iface}`}
        >
          {!alreadyBest && <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />}
          <span>
            {alreadyBest ? (
              <>
                Already on the cheapest channel the AP found (<strong className="font-mono">{best.channel}</strong>,
                cost {best.cost}). Nothing to change.
              </>
            ) : (
              <>
                <strong>Suggested: channel {best.channel}</strong> (cost {best.cost}, against{' '}
                {scan.channels.find((c) => c.channel === current)?.cost ?? '—'} where it sits now) —{' '}
                {explain(scan, best.channel, best.cost, true)}. Change it in the radio form; every client on
                this radio reconnects.
              </>
            )}
          </span>
        </div>
      )}

      {ranked.length > 0 && (
        <div className="overflow-x-auto">
          <MriTable>
            <MriTableHeader>
              <MriTableRow>
                <MriTableHead>Channel</MriTableHead>
                <MriTableHead>Cost</MriTableHead>
                <MriTableHead>Neighbours</MriTableHead>
                <MriTableHead>Loudest</MriTableHead>
              </MriTableRow>
            </MriTableHeader>
            <MriTableBody>
              {ranked.map((c) => {
                const crowd = channelCrowding(scan, c.channel)
                return (
                  <MriTableRow key={c.channel}>
                    <MriTableCell className="font-mono tabular-nums">
                      {c.channel}
                      {c.channel === current && (
                        <span className="ml-2 text-xs text-muted-foreground">current</span>
                      )}
                    </MriTableCell>
                    <MriTableCell className="tabular-nums">{c.cost}</MriTableCell>
                    <MriTableCell className="tabular-nums">{crowd.count}</MriTableCell>
                    <MriTableCell className="tabular-nums">{signalLabel(crowd.loudestDbm)}</MriTableCell>
                  </MriTableRow>
                )
              })}
            </MriTableBody>
          </MriTable>
        </div>
      )}

      {unusable.length > 0 && (
        <p className="text-xs text-muted-foreground">
          {unusable.length} channel{unusable.length === 1 ? '' : 's'} excluded by the AP (
          {[...new Set(unusable.map((c) => c.reason).filter(Boolean))].join(', ') || 'no reason given'}).
          Those are not expensive channels — they are channels this radio cannot centre on at its configured
          width.
        </p>
      )}

      {scan.neighbors.length > 0 && (
        <div>
          <MriButton size="sm" variant="ghost" onClick={() => setShowNeighbors((v) => !v)}>
            {showNeighbors ? 'Hide' : `Show ${scan.neighbors.length} neighbouring AP(s)`}
          </MriButton>
          {showNeighbors && (
            <div className="mt-2 max-h-72 overflow-auto">
              <MriTable>
                <MriTableHeader>
                  <MriTableRow>
                    <MriTableHead>SSID</MriTableHead>
                    <MriTableHead>BSSID</MriTableHead>
                    <MriTableHead>Ch</MriTableHead>
                    <MriTableHead>Signal</MriTableHead>
                    <MriTableHead>Width</MriTableHead>
                  </MriTableRow>
                </MriTableHeader>
                <MriTableBody>
                  {[...scan.neighbors]
                    .sort((a, b) => b.rssiDbm - a.rssiDbm)
                    .map((n) => (
                      <MriTableRow key={n.bssid}>
                        <MriTableCell>
                          {n.ssid || <span className="text-muted-foreground">(hidden)</span>}
                          {n.ourFleet && <MriStatusBadge label="yours" variant="success" />}
                        </MriTableCell>
                        <MriTableCell className="font-mono text-xs">{n.bssid}</MriTableCell>
                        <MriTableCell className="tabular-nums">{n.channel}</MriTableCell>
                        <MriTableCell className="tabular-nums">{signalLabel(n.rssiDbm)}</MriTableCell>
                        <MriTableCell className="tabular-nums">{n.channelWidth}</MriTableCell>
                      </MriTableRow>
                    ))}
                </MriTableBody>
              </MriTable>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export function ChannelScanSection({ scans, onScan, busy }) {
  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Radar} title="Channel scan" />
      <p className="text-xs text-muted-foreground">
        What each radio hears around it, and the channel the AP itself scores cheapest. The costs are the
        AP&apos;s own — it scans the spectrum continuously, so this reports its measurement rather than
        guessing from a neighbour list.
      </p>
      <div className="flex items-center gap-2">
        <MriButton size="sm" disabled={busy} onClick={onScan}>
          {busy ? 'Scanning…' : 'Scan the air'}
        </MriButton>
        <span className="text-xs text-muted-foreground">Read-only — this changes nothing on the AP.</span>
      </div>
      {(!scans || scans.length === 0) && (
        <p className="text-xs text-muted-foreground" data-testid="scan-empty">
          No scan yet.
        </p>
      )}
      {(scans || []).map((scan) => (
        <RadioScan key={scan.iface} scan={scan} />
      ))}
    </div>
  )
}
