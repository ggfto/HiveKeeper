import { MriCard, MriCardHeader, MriCardTitle, MriCardContent, MriButton, MriStatusBadge } from '@mriqbox/ui-kit'
import { Boxes } from 'lucide-react'

/**
 * The connected on-prem agents for the active organization, each with how many fleet devices it fronts and the
 * site those devices sit in. Anything listed is online (agents dial out to the gateway). View devices jumps to
 * the fleet filtered to that agent; Discover sweeps its LAN. Agents are { id, deviceCount, site }.
 */
export function AgentsList({ agents, onView, onDiscover, busy }) {
  if (agents == null) {
    return <p className="text-sm text-muted-foreground">Gateway unreachable.</p>
  }
  if (agents.length === 0) {
    return <p className="text-sm text-muted-foreground">No agents connected for this organization.</p>
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {agents.map((a) => (
        <MriCard key={a.id}>
          <MriCardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
            <MriCardTitle className="font-mono text-sm">{a.id}</MriCardTitle>
            <MriStatusBadge label="online" variant="success" size="xs" />
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
              <MriButton size="sm" variant="outline" disabled={busy} onClick={() => onDiscover?.(a.id)}>
                Discover
              </MriButton>
            </div>
          </MriCardContent>
        </MriCard>
      ))}
    </div>
  )
}
