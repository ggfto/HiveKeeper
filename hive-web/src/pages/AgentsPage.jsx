import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { Server } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { AgentsList } from '../components/organisms/AgentsList'
import { DiscoveredHosts } from '../components/organisms/DiscoveredHosts'

/** Connected agents for the active org + a discover -> adopt flow on an agent's LAN. */
export function AgentsPage() {
  const { gateway, activeOrg } = useAuth()
  const [agents, setAgents] = useState(null)
  const [discovered, setDiscovered] = useState([])
  const [discoverAgent, setDiscoverAgent] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setAgents(await gateway.agents())
    } catch {
      setAgents(null)
    }
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const onDiscover = async (agentId) => {
    setBusy(true)
    setStatus(`Discovering the LAN via ${agentId}…`)
    setDiscoverAgent(agentId)
    setDiscovered([])
    try {
      const r = await gateway.discover(agentId, '192.168.1.0/24')
      setDiscovered(r.hosts || [])
      setStatus(`${r.hosts?.length || 0} host(s) found via ${agentId}.`)
    } catch (e) {
      setStatus(`Discover via ${agentId}: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  const onAdopt = async (host) => {
    if (!discoverAgent) return
    setBusy(true)
    setStatus(`Adopting ${host} via ${discoverAgent}…`)
    try {
      const r = await gateway.adopt(discoverAgent, { host })
      setStatus(`Adopted ${host} as ${r.serial}${r.model ? ` (${r.model})` : ''}.`)
    } catch (e) {
      setStatus(`Adopt ${host}: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <MriPageHeader
        title="Agents"
        icon={Server}
        count={Array.isArray(agents) ? agents.length : undefined}
        countLabel="connected"
      >
        <MriButton size="sm" variant="outline" disabled={busy} onClick={load}>
          Refresh
        </MriButton>
      </MriPageHeader>
      <AgentsList agents={agents} onDiscover={onDiscover} busy={busy} />
      {discovered.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-muted-foreground">Discovered on {discoverAgent}</h2>
          <DiscoveredHosts hosts={discovered} onAdopt={onAdopt} busy={busy} />
        </section>
      )}
      {status && <p className="text-sm text-muted-foreground">{status}</p>}
    </div>
  )
}
