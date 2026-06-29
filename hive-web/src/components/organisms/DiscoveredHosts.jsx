import { MriButton, MriStatusBadge } from '@mriqbox/ui-kit'
import { supportLevel, supportBadge } from '../../lib/deviceSupport'

/**
 * Hosts a discover sweep found on an agent's LAN. Each can be IDENTIFIED (inventory it through the agent to
 * learn whether it is a HiveOS AP and which model) and then ADOPTED into the fleet. Identify is a soft probe:
 * it tells the operator whether a host is an adoptable AP — and how well-supported its model is — before
 * adopting, instead of finding out only on failure.
 */
export function DiscoveredHosts({ hosts, onAdopt, onIdentify, identified = {}, busy }) {
  if (!hosts || hosts.length === 0) return null
  return (
    <div className="space-y-2">
      {hosts.map((h) => {
        const info = identified[h.host]
        const badge = info ? supportBadge(supportLevel(info.model, { hiveOs: info.hiveOs })) : null
        return (
          <div
            key={h.host}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border bg-card px-3 py-2"
          >
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-mono">{h.host}</span>
              <span className="text-muted-foreground">{h.sshBanner || '(open)'}</span>
              {h.looksLikeSsh && <MriStatusBadge label="ssh" variant="success" size="xs" />}
              {info && badge && (
                <>
                  {info.model && <span className="font-mono text-xs">{info.model}</span>}
                  <MriStatusBadge label={badge.label} variant={badge.variant} size="xs" />
                </>
              )}
            </div>
            <div className="flex items-center gap-2">
              {onIdentify && (
                <MriButton size="sm" variant="ghost" disabled={busy} onClick={() => onIdentify(h.host)}>
                  Identify
                </MriButton>
              )}
              <MriButton size="sm" variant="outline" disabled={busy} onClick={() => onAdopt?.(h.host)}>
                Adopt
              </MriButton>
            </div>
          </div>
        )
      })}
    </div>
  )
}
