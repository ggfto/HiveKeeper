import { MriCard, MriCardHeader, MriCardTitle, MriCardContent, MriButton, MriStatusBadge } from '@mriqbox/ui-kit'

/**
 * The connected on-prem agents for the active organization. Each agent dials out to the gateway, so anything
 * listed here is online; its action is to discover hosts on its own LAN (which can then be adopted). Agent ids
 * are the strings returned by GET /api/agents.
 */
export function AgentsList({ agents, onDiscover, busy }) {
  if (agents == null) {
    return <p className="text-sm text-muted-foreground">Gateway unreachable.</p>
  }
  if (agents.length === 0) {
    return <p className="text-sm text-muted-foreground">No agents connected for this organization.</p>
  }
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {agents.map((id) => (
        <MriCard key={id}>
          <MriCardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
            <MriCardTitle className="font-mono text-sm">{id}</MriCardTitle>
            <MriStatusBadge label="online" variant="success" size="xs" />
          </MriCardHeader>
          <MriCardContent>
            <MriButton size="sm" variant="outline" disabled={busy} onClick={() => onDiscover?.(id)}>
              Discover LAN
            </MriButton>
          </MriCardContent>
        </MriCard>
      ))}
    </div>
  )
}
