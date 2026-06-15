import { MriButton, MriStatusBadge } from '@mriqbox/ui-kit'

/**
 * Hosts a discover sweep found on an agent's LAN. Each can be adopted into the fleet: the gateway inventories
 * it through the agent to learn its real serial, then registers it on the agent's site.
 */
export function DiscoveredHosts({ hosts, onAdopt, busy }) {
  if (!hosts || hosts.length === 0) return null
  return (
    <div className="space-y-2">
      {hosts.map((h) => (
        <div
          key={h.host}
          className="flex items-center justify-between rounded-md border border-border bg-card px-3 py-2"
        >
          <div className="flex items-center gap-2 text-sm">
            <span className="font-mono">{h.host}</span>
            <span className="text-muted-foreground">{h.sshBanner || '(open)'}</span>
            {h.looksLikeSsh && <MriStatusBadge label="ssh" variant="success" size="xs" />}
          </div>
          <MriButton size="sm" variant="outline" disabled={busy} onClick={() => onAdopt?.(h.host)}>
            Adopt
          </MriButton>
        </div>
      ))}
    </div>
  )
}
