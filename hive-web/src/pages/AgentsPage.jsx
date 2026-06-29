import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton, MriInput } from '@mriqbox/ui-kit'
import { Server } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { AgentsList } from '../components/organisms/AgentsList'
import { AddAgentForm } from '../components/organisms/AddAgentForm'
import { DiscoveredHosts } from '../components/organisms/DiscoveredHosts'
import { siteName } from '../lib/fleet'

/** Connected agents for the active org — each shown with how many fleet devices it fronts and their site, a
 *  jump into the filtered device list, and a discover -> adopt flow on the agent's LAN. */
export function AgentsPage() {
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const navigate = useNavigate()
  const [agents, setAgents] = useState(null)
  const [devices, setDevices] = useState([])
  const [sites, setSites] = useState([])
  const [discovered, setDiscovered] = useState([])
  const [discoverAgent, setDiscoverAgent] = useState('')
  const [identified, setIdentified] = useState({})
  const [adoptCred, setAdoptCred] = useState({ username: 'admin', password: '' })
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [a, d, s] = await Promise.all([
        gateway.agents().catch(() => null),
        gateway.devices().catch(() => []),
        gateway.sites().catch(() => []),
      ])
      setAgents(Array.isArray(a) ? a : null)
      setDevices(Array.isArray(d) ? d : [])
      setSites(Array.isArray(s) ? s : [])
    } finally {
      setLoading(false)
    }
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
    setDiscoverAgent(agentId)
    setDiscovered([])
    setIdentified({})
    try {
      const r = await gateway.discover(agentId, '192.168.1.0/24')
      setDiscovered(r.hosts || [])
      toast(`${r.hosts?.length || 0} host(s) found via ${agentId}.`, 'success')
    } catch (e) {
      toast(`Discover via ${agentId}: ${e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  // Probe a discovered host through the agent: a successful inventory means it is a reachable HiveOS AP, and we
  // learn its model (-> a support badge). A failure means it did not identify as an AP (wrong creds, or not one).
  const onIdentify = async (host) => {
    if (!discoverAgent) return
    setBusy(true)
    try {
      const r = await gateway.inventory(discoverAgent, host)
      const dev = r?.device || {}
      setIdentified((m) => ({ ...m, [host]: { model: dev.model, serial: dev.serial, hiveOs: !!dev.model } }))
      toast(`${host}: ${dev.model || 'identified'}${dev.serial ? ` (${dev.serial})` : ''}.`, 'success')
    } catch (e) {
      setIdentified((m) => ({ ...m, [host]: { hiveOs: false, error: e.message } }))
      toast(`Identify ${host}: ${e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  // Register a new agent -> its one-time enrollment token. Refresh the list (it shows once the agent connects).
  const createEnrollment = (agentId, siteId) =>
    gateway.createEnrollment({ agentId, siteId }).then((r) => {
      load()
      return r
    })

  const onAdopt = async (host) => {
    if (!discoverAgent) return
    setBusy(true)
    try {
      const r = await gateway.adopt(discoverAgent, { host })
      toast(`Adopted ${host} as ${r.serial}${r.model ? ` (${r.model})` : ''}.`, 'success')
      // If the operator supplied a credential for adoption, set it now (sealed to the agent) so the new device
      // resolves the right secret. A failure here does not undo the adoption — report it separately.
      if (adoptCred.password) {
        try {
          await gateway.setCredential(discoverAgent, {
            host,
            deviceId: r.deviceId,
            username: adoptCred.username.trim() || 'admin',
            password: adoptCred.password,
          })
          toast(`Credential set for ${host}.`, 'success')
        } catch (e) {
          toast(`Adopted ${host}, but setting its credential failed: ${e.message}`, 'error')
        }
      }
      await load()
    } catch (e) {
      toast(`Adopt ${host}: ${e.message}`, 'error')
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
        className="flex-wrap gap-y-3"
      >
        <MriButton size="sm" variant="outline" disabled={busy} onClick={load}>
          Refresh
        </MriButton>
      </MriPageHeader>
      <AgentsList
        agents={enriched}
        loading={loading}
        onView={(id) => navigate(`/devices?agent=${id}`)}
        onDiscover={onDiscover}
        busy={busy}
      />
      <AddAgentForm sites={sites} createEnrollment={createEnrollment} busy={busy} />
      {discovered.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-muted-foreground">Discovered on {discoverAgent}</h2>
          <div className="flex flex-wrap items-end gap-2 rounded-md border border-border bg-card p-3">
            <label className="flex flex-col gap-1">
              <span className="text-xs text-muted-foreground">Adopt credential — username (optional)</span>
              <MriInput
                value={adoptCred.username}
                onChange={(e) => setAdoptCred((c) => ({ ...c, username: e.target.value }))}
                placeholder="admin"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-xs text-muted-foreground">password (optional)</span>
              <MriInput
                type="password"
                value={adoptCred.password}
                onChange={(e) => setAdoptCred((c) => ({ ...c, password: e.target.value }))}
                placeholder="leave blank to use the agent default"
              />
            </label>
          </div>
          <DiscoveredHosts
            hosts={discovered}
            onAdopt={onAdopt}
            onIdentify={onIdentify}
            identified={identified}
            busy={busy}
          />
        </section>
      )}
    </div>
  )
}
