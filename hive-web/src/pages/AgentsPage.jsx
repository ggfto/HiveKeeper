import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton, MriInput } from '@mriqbox/ui-kit'
import { Server } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { AgentsList } from '../components/organisms/AgentsList'
import { AddAgentForm } from '../components/organisms/AddAgentForm'
import { BackupDestinationForm } from '../components/organisms/BackupDestinationForm'
import { DiscoveredHosts } from '../components/organisms/DiscoveredHosts'
import { siteName } from '../lib/fleet'

/** Hand a downloaded blob to the browser as a file. A no-op outside a browser (tests), where there is nothing
 *  to save to and the caller's own assertions cover the request. */
function saveBlob(blob, filename) {
  if (typeof URL.createObjectURL !== 'function') return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** Enrolled agents for the active org — each shown online/offline, with how many fleet devices it can reach and
 *  their site, a jump into the filtered device list, and a discover -> adopt flow on the agent's LAN. */
export function AgentsPage() {
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const navigate = useNavigate()
  const [agents, setAgents] = useState(null)          // durable identities (agentsAll)
  const [connectedAgents, setConnectedAgents] = useState([])
  const [devices, setDevices] = useState([])
  const [sites, setSites] = useState([])
  const [discovered, setDiscovered] = useState([])
  const [discoverAgent, setDiscoverAgent] = useState('')
  // Blank = let the agent auto-detect its own primary subnet (it is the node on the AP LAN). An explicit CIDR
  // overrides it — needed when the agent has several interfaces, or to sweep a subnet other than its own.
  const [discoverCidr, setDiscoverCidr] = useState('')
  const [identified, setIdentified] = useState({})
  const [adoptCred, setAdoptCred] = useState({ username: 'admin', password: '' })
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  // Where agents dial in, per the gateway's own certificate — prefills the enrollment form. Null on a gateway
  // that cannot tell (or an older one that has no such endpoint), in which case the form asks as it used to.
  const [agentEndpoint, setAgentEndpoint] = useState(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [all, conn, d, s] = await Promise.all([
        gateway.agentsAll().catch(() => null),
        gateway.agents().catch(() => []),
        gateway.devices().catch(() => []),
        gateway.sites().catch(() => []),
      ])
      setAgents(Array.isArray(all) ? all : null)
      setConnectedAgents(Array.isArray(conn) ? conn : [])
      setDevices(Array.isArray(d) ? d : [])
      setSites(Array.isArray(s) ? s : [])
    } finally {
      setLoading(false)
    }
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  // A deployment fact, not org state: fetched once and left alone. A failure is not worth a toast — the form
  // just falls back to asking for the hostname.
  useEffect(() => {
    let live = true
    gateway
      .agentEndpoint?.()
      .then((e) => live && setAgentEndpoint(e))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [gateway])

  // Roll up per enrolled agent: online (is it currently connected), how many fleet devices it can reach, and
  // its site (from the agent record). null stays null so the list can show "gateway unreachable".
  const enriched = useMemo(() => {
    if (!Array.isArray(agents)) return agents
    const connectedSet = new Set(connectedAgents)
    return agents.map((a) => ({
      id: a.agentId,
      online: connectedSet.has(a.agentId),
      deviceCount: devices.filter((d) => (d.reachableAgents || []).includes(a.agentId)).length,
      site: siteName(a.siteId, sites),
      // Only meaningful while it is offline, which is where the card shows it. Null = enrolled but never
      // dialed in.
      lastSeen: a.lastSeen,
    }))
  }, [agents, connectedAgents, devices, sites])

  const onDiscover = async (agentId) => {
    setBusy(true)
    setDiscoverAgent(agentId)
    setDiscovered([])
    setIdentified({})
    try {
      const cidr = discoverCidr.trim() || undefined
      const r = await gateway.discover(agentId, cidr)
      setDiscovered(r.hosts || [])
      const where = cidr ? cidr : 'auto-detected subnet'
      toast(`${r.hosts?.length || 0} host(s) found via ${agentId} (${where}).`, 'success')
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

  // Delete an agent outright, freeing its id for a clean re-install. Irreversible, and it takes the agent's
  // device reachability with it — the confirm step in the list is what states that cost.
  const onRemove = async (agentId) => {
    setBusy(true)
    try {
      await gateway.deleteAgent(agentId)
      // Any discover results on screen belong to an agent that no longer exists — clear them rather than leave
      // an adopt flow pointing at a deleted agent.
      if (discoverAgent === agentId) {
        setDiscovered([])
        setIdentified({})
        setDiscoverAgent('')
      }
      toast(`Removed ${agentId}. Enroll it again to re-install.`, 'success')
      await load()
    } catch (e) {
      toast(`Remove ${agentId}: ${e.message}`, 'error')
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

  // Package that enrollment as the install bundle and save it. The gateway names the file; the fallback name
  // only matters against an older gateway that sends no Content-Disposition.
  const downloadBundle = async (agentId, token, domain) => {
    const { blob, filename } = await gateway.agentBundle({ agentId, token, domain: domain || null })
    saveBlob(blob, filename || `${agentId}-agent-install.zip`)
    toast(`Downloaded the install bundle for ${agentId}.`, 'success')
  }

  const onAdopt = async (host) => {
    if (!discoverAgent) return
    setBusy(true)
    try {
      const r = await gateway.adopt(discoverAgent, { host })
      const baseline = r.baselineCaptured
        ? ' Its current config was captured as the baseline.'
        : ' Its config baseline could not be captured — back it up manually.'
      toast(`Adopted ${host} as ${r.serial}${r.model ? ` (${r.model})` : ''}.${baseline}`,
        r.baselineCaptured ? 'success' : 'warning')
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
        countLabel="enrolled"
        className="flex-wrap gap-y-3"
      >
        <MriButton size="sm" variant="outline" disabled={busy} onClick={load}>
          Refresh
        </MriButton>
      </MriPageHeader>
      <label className="flex flex-col gap-1">
        <span className="text-xs text-muted-foreground">
          Discover subnet — CIDR (leave blank to auto-detect the agent&apos;s own subnet)
        </span>
        <MriInput
          value={discoverCidr}
          onChange={(e) => setDiscoverCidr(e.target.value)}
          placeholder="auto-detect — or e.g. 192.168.68.0/24"
          className="max-w-xs"
        />
      </label>
      <AgentsList
        agents={enriched}
        loading={loading}
        onView={(id) => navigate(`/devices?agent=${id}`)}
        onDiscover={onDiscover}
        onRemove={onRemove}
        busy={busy}
      />
      <AddAgentForm
        sites={sites}
        createEnrollment={createEnrollment}
        downloadBundle={downloadBundle}
        agentEndpoint={agentEndpoint}
        busy={busy}
      />
      {/* Org-wide, so it lives beside the agent list rather than on any one agent. */}
      <section className="rounded-md border border-border bg-card p-3">
        <BackupDestinationForm gateway={gateway} busy={busy} />
      </section>
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
