import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { InfraMap } from '../components/organisms/InfraMap'
import { buildTopology } from '../lib/topology'

/**
 * The infrastructure map: sites -> APs (grouped by hive), built entirely from data we already have — the fleet
 * structure plus a LIVE inventory of every AP (the chosen "always live" mode), which gives each AP its client
 * count, hive, and reachability. The fan-out is one inventory op per AP; an unreachable AP just shows offline.
 */
export function MapPage() {
  const { gateway, activeOrg } = useAuth()
  const navigate = useNavigate()
  const [graph, setGraph] = useState({ nodes: [], edges: [] })
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    const [agents, sites, devices] = await Promise.all([
      gateway.agents().catch(() => []),
      gateway.sites().catch(() => []),
      gateway.devices().catch(() => []),
    ])
    const list = Array.isArray(devices) ? devices : []
    // Always live: read each AP through its agent for the client count + hive. Offline/unreachable -> no status.
    const entries = await Promise.all(
      list.map((d) =>
        d.agentId && d.mgmtIp
          ? gateway
              .inventory(d.agentId, d.mgmtIp)
              .then((r) => {
                const dev = r?.device || {}
                return [d.deviceId, { online: true, clientCount: (dev.stations || []).length, hive: dev.hiveName || null }]
              })
              .catch(() => [d.deviceId, { online: false }])
          : Promise.resolve([d.deviceId, { online: false }]),
      ),
    )
    setGraph(
      buildTopology({
        sites: Array.isArray(sites) ? sites : [],
        agents: Array.isArray(agents) ? agents : [],
        devices: list,
        statuses: Object.fromEntries(entries),
      }),
    )
    setLoading(false)
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const apCount = graph.nodes.filter((n) => n.type === 'ap').length

  return (
    <div className="space-y-4">
      <MriPageHeader title="Map" icon={Network} count={apCount} countLabel="APs">
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
