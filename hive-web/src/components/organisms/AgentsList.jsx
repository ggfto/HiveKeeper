import { MriCard, MriCardHeader, MriCardTitle, MriCardContent, MriButton, MriStatusBadge } from '@mriqbox/ui-kit'
import { Boxes, Clock } from 'lucide-react'
import { ConfirmButton } from '../molecules/ConfirmButton'
import { lastSeenLabel } from '../../lib/fleet'

/**
 * The enrolled on-prem agents for the active organization, each shown online/offline (an agent is online while
 * it holds a connection to the gateway), with how many fleet devices it can reach and its site. View devices
 * jumps to the fleet filtered to that agent; Discover sweeps its LAN (only while it is online). Agents are
 * { id, online, deviceCount, site }.
 *
 * `onRemove(agentId)` deletes the agent outright — the teardown half of a re-install, and irreversible. It is
 * omitted entirely when the caller cannot delete, and its confirm step names the devices that lose their way to
 * the fleet: the reachability goes with the agent, so an AP only this agent could drive is stranded until
 * another one is pointed at it. That number is the whole cost of the click, so it is on the button.
 */
export function AgentsList({ agents, onView, onDiscover, onRemove, busy, loading }) {
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
            {/* Only while it is offline: next to an "online" badge a last-seen time reads as a contradiction,
                and the answer there is just "now". */}
            {!a.online && (
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <Clock className="h-3.5 w-3.5 shrink-0" />
                <span>{a.lastSeen ? `Last seen ${lastSeenLabel(a.lastSeen)}` : 'Never connected'}</span>
              </div>
            )}
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
              {onRemove && (
                <ConfirmButton
                  disabled={busy}
                  confirmLabel={
                    a.deviceCount > 0
                      ? `Remove agent + ${a.deviceCount} device${a.deviceCount === 1 ? '' : 's'} unreachable`
                      : 'Remove agent'
                  }
                  onConfirm={() => onRemove(a.id)}
                >
                  Remove
                </ConfirmButton>
              )}
            </div>
          </MriCardContent>
        </MriCard>
      ))}
    </div>
  )
}
