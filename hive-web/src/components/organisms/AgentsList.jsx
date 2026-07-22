import { MriCard, MriCardHeader, MriCardTitle, MriCardContent, MriButton, MriStatusBadge } from '@mriqbox/ui-kit'
import { Boxes } from 'lucide-react'

/**
 * The enrolled on-prem agents for the active organization, each shown online/offline (an agent is online while
 * it holds a connection to the gateway), with how many fleet devices it can reach and its site. View devices
 * jumps to the fleet filtered to that agent; Discover sweeps its LAN (only while it is online). Agents are
 * { id, online, deviceCount, site }.
 */
export function AgentsList({ agents, onView, onDiscover, busy, loading }) {
  if (loading && agents == null) {
    return <p className="text-sm text-muted-foreground">Loading agents…</p>
  }
  if (agents == null) {
    return <p className="text-sm text-muted-foreground">Gateway unreachable.</p>
  }
  if (agents.length === 0) {
    return <p className="text-sm text-muted-foreground">No agents enrolled for this organization.</p>
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {agents.map((a) => (
        <MriCard key={a.id}>
          <MriCardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
            <MriCardTitle className="font-mono text-sm">{a.id}</MriCardTitle>
            <MriStatusBadge
              label={a.online ? 'online' : 'offline'}
              variant={a.online ? 'success' : 'outline'}
              size="xs"
            />
          </MriCardHeader>
          <MriCardContent className="space-y-3">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Boxes className="h-4 w-4 shrink-0" />
              <span>
                {a.deviceCount} device{a.deviceCount === 1 ? '' : 's'}
              </span>
              {a.site && <span>· {a.site}</span>}
            </div>
            <div className="flex gap-2">
              <MriButton size="sm" disabled={busy} onClick={() => onView?.(a.id)}>
                View devices
              </MriButton>
              <MriButton
                size="sm"
                variant="outline"
                disabled={busy || !a.online}
                title={a.online ? undefined : 'The agent is offline'}
                onClick={() => onDiscover?.(a.id)}
              >
                Discover
              </MriButton>
            </div>
          </MriCardContent>
        </MriCard>
      ))}
    </div>
  )
}
