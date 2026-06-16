import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton, MriSwitch } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { InfraMap } from '../components/organisms/InfraMap'
import { buildTopology } from '../lib/topology'

/**
 * The infrastructure map: sites -> APs -> connected clients, built entirely from data we already have — the
 * fleet structure plus a LIVE inventory of every AP (the chosen "always live" mode), which gives each AP its
 * stations (clients), hive, and reachability. The fan-out is one inventory op per AP; an unreachable AP just
 * shows offline. The graph is rebuilt from the cached fleet whenever the "Clients" toggle flips — no re-fetch.
 */
export function MapPage() {
  const { gateway, activeOrg } = useAuth()
  const navigate = useNavigate()
  const [fleet, setFleet] = useState({ sites: [], agents: [], devices: [], statuses: {} })
  const [showClients, setShowClients] = useState(true)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    const [agents, sites, devices] = await Promise.all([
      gateway.agents().catch(() => []),
      gateway.sites().catch(() => []),
      gateway.devices().catch(() => []),
    ])
    const list = Array.isArray(devices) ? devices : []
    const entries = await Promise.all(
      list.map((d) =>
        d.agentId && d.mgmtIp
          ? gateway
              .inventory(d.agentId, d.mgmtIp)
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

  // Rebuild instantly when the Clients toggle flips (the live statuses are already cached in `fleet`).
  const graph = useMemo(() => buildTopology(fleet, { showClients }), [fleet, showClients])
  const apCount = useMemo(() => graph.nodes.filter((n) => n.type === 'ap').length, [graph])

  return (
    <div className="space-y-4">
      <MriPageHeader title="Map" icon={Network} count={apCount} countLabel="APs">
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
        <InfraMap nodes={graph.nodes} edges={graph.edges} onSelectDevice={(id) => navigate(`/devices/${id}`)} />
      )}
    </div>
  )
}
