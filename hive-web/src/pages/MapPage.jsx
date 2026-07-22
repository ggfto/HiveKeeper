import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton, MriSwitch, MriSelect } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { InfraMap } from '../components/organisms/InfraMap'
import { buildTopology } from '../lib/topology'

/**
 * The infrastructure map: sites -> APs -> connected clients, built entirely from data we already have — the
 * fleet structure plus a LIVE inventory of every AP (the chosen "always live" mode), which gives each AP its
 * stations (clients), hive, and reachability. The fan-out is one inventory op per AP; an unreachable AP just
 * shows offline. The graph is rebuilt from the cached fleet whenever a control changes (Clients toggle, the
 * site filter, or expanding a busy AP) — no re-fetch.
 */
export function MapPage() {
  const { gateway, activeOrg } = useAuth()
  const navigate = useNavigate()
  const [fleet, setFleet] = useState({ sites: [], agents: [], devices: [], statuses: {} })
  const [showClients, setShowClients] = useState(true)
  const [siteFilter, setSiteFilter] = useState('all')
  const [expanded, setExpanded] = useState(() => new Set())
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    const [agents, sites, devices] = await Promise.all([
      gateway.agents().catch(() => []),
      gateway.sites().catch(() => []),
      gateway.devices().catch(() => []),
    ])
    const list = Array.isArray(devices) ? devices : []
    // Inventory each AP through its serving agent — the first reachable agent that is connected.
    const connected = new Set(Array.isArray(agents) ? agents : [])
    const serving = (d) => (d.reachableAgents || []).find((a) => connected.has(a))
    const entries = await Promise.all(
      list.map((d) =>
        serving(d) && d.mgmtIp
          ? gateway
              .inventory(serving(d), d.mgmtIp)
              .then((r) => {
                const dev = r?.device || {}
                const stations = dev.stations || []
                return [d.deviceId, { online: true, clientCount: stations.length, hive: dev.hiveName || null, stations }]
              })
              .catch(() => [d.deviceId, { online: false }])
          : Promise.resolve([d.deviceId, { online: false }]),
      ),
    )
    setFleet({
      sites: Array.isArray(sites) ? sites : [],
      agents: Array.isArray(agents) ? agents : [],
      devices: list,
      statuses: Object.fromEntries(entries),
    })
    setLoading(false)
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  // Narrow to one site (when chosen), then build — all from the cached fleet, so controls are instant.
  const visible = useMemo(() => {
    if (siteFilter === 'all') return fleet
    return {
      ...fleet,
      sites: fleet.sites.filter((s) => s.siteId === siteFilter),
      devices: fleet.devices.filter((d) => d.siteId === siteFilter),
    }
  }, [fleet, siteFilter])

  const graph = useMemo(
    () => buildTopology(visible, { showClients, expanded: [...expanded] }),
    [visible, showClients, expanded],
  )
  const apCount = useMemo(() => graph.nodes.filter((n) => n.type === 'ap').length, [graph])

  const siteOptions = [{ label: 'All sites', value: 'all' }, ...fleet.sites.map((s) => ({ label: s.name, value: s.siteId }))]

  return (
    <div className="space-y-4">
      <MriPageHeader title="Map" icon={Network} count={apCount} countLabel="APs" className="flex-wrap gap-y-3">
        {fleet.sites.length > 1 && (
          <div className="w-40">
            <MriSelect options={siteOptions} value={siteFilter} onChange={setSiteFilter} size="sm" />
          </div>
        )}
        <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <MriSwitch checked={showClients} onCheckedChange={setShowClients} aria-label="Show clients" />
          Clients
        </label>
        <MriButton size="sm" variant="outline" disabled={loading} onClick={load}>
          {loading ? 'Reading APs…' : 'Refresh'}
        </MriButton>
      </MriPageHeader>
      {loading && graph.nodes.length === 0 ? (
        <p className="text-sm text-muted-foreground">Building the map (reading each AP live)…</p>
      ) : graph.nodes.length === 0 ? (
        <p className="text-sm text-muted-foreground">No sites or devices to map yet.</p>
      ) : (
        <InfraMap
          nodes={graph.nodes}
          edges={graph.edges}
          onSelectDevice={(id) => navigate(`/devices/${id}`)}
          onExpand={(id) => setExpanded((prev) => new Set(prev).add(id))}
        />
      )}
    </div>
  )
}
