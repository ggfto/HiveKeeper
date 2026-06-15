import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { Server } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { AgentsList } from '../components/organisms/AgentsList'
import { DiscoveredHosts } from '../components/organisms/DiscoveredHosts'
import { siteName } from '../lib/fleet'

/** Connected agents for the active org — each shown with how many fleet devices it fronts and their site, a
 *  jump into the filtered device list, and a discover -> adopt flow on the agent's LAN. */
export function AgentsPage() {
  const { gateway, activeOrg } = useAuth()
  const navigate = useNavigate()
  const [agents, setAgents] = useState(null)
  const [devices, setDevices] = useState([])
  const [sites, setSites] = useState([])
  const [discovered, setDiscovered] = useState([])
  const [discoverAgent, setDiscoverAgent] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const [a, d, s] = await Promise.all([
      gateway.agents().catch(() => null),
      gateway.devices().catch(() => []),
      gateway.sites().catch(() => []),
    ])
    setAgents(Array.isArray(a) ? a : null)
    setDevices(Array.isArray(d) ? d : [])
    setSites(Array.isArray(s) ? s : [])
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  // Roll the fleet up per agent: device count + the site its devices sit in (first one wins; agents are
  // single-site in practice). null stays null so the list can show "gateway unreachable".
  const enriched = useMemo(() => {
    if (!Array.isArray(agents)) return agents
    return agents.map((id) => {
      const own = devices.filter((d) => d.agentId === id)
      const siteId = own.find((d) => d.siteId)?.siteId
      return { id, deviceCount: own.length, site: siteName(siteId, sites) }
    })
  }, [agents, devices, sites])

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
      await load()
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
      <AgentsList
        agents={enriched}
        onView={(id) => navigate(`/devices?agent=${id}`)}
        onDiscover={onDiscover}
        busy={busy}
      />
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
