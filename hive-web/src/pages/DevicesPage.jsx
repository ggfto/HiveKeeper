import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { Boxes, X } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { DevicesTable } from '../components/organisms/DevicesTable'

/** The registered fleet: live status (is the device's agent connected), filterable by agent, click a row to
 *  open the device's management page. */
export function DevicesPage() {
  const { gateway, activeOrg } = useAuth()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const agentFilter = searchParams.get('agent')

  const [devices, setDevices] = useState(null)
  const [groups, setGroups] = useState([])
  const [sites, setSites] = useState([])
  const [agents, setAgents] = useState([])
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setBusy(true)
    const [d, g, s, a] = await Promise.all([
      gateway.devices().catch((e) => (e.status === 403 ? 'forbidden' : null)),
      gateway.groups().catch(() => []),
      gateway.sites().catch(() => []),
      gateway.agents().catch(() => []),
    ])
    setDevices(d)
    setGroups(Array.isArray(g) ? g : [])
    setSites(Array.isArray(s) ? s : [])
    setAgents(Array.isArray(a) ? a : [])
    setBusy(false)
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const connected = useMemo(() => new Set(agents), [agents])
  const rows = useMemo(() => {
    if (!Array.isArray(devices)) return devices
    return devices
      .filter((d) => !agentFilter || d.agentId === agentFilter)
      .map((d) => ({ ...d, online: connected.has(d.agentId) }))
  }, [devices, agentFilter, connected])

  const count = Array.isArray(rows) ? rows.length : undefined

  return (
    <div className="space-y-4">
      <MriPageHeader title="Devices" icon={Boxes} count={count} countLabel="registered">
        <MriButton size="sm" variant="outline" disabled={busy} onClick={load}>
          Refresh
        </MriButton>
      </MriPageHeader>

      {agentFilter && (
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">Filtered to agent</span>
          <span className="inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 font-mono text-xs">
            {agentFilter}
            <button
              type="button"
              aria-label="Clear agent filter"
              className="text-muted-foreground hover:text-destructive"
              onClick={() => setSearchParams({})}
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        </div>
      )}

      {devices === 'forbidden' ? (
        <p className="text-sm text-muted-foreground">
          The fleet list needs an organization-level viewer or admin role.
        </p>
      ) : (
        <DevicesTable
          devices={Array.isArray(rows) ? rows : []}
          groups={groups}
          sites={sites}
          onOpen={(d) => navigate(`/devices/${d.deviceId}`)}
        />
      )}
    </div>
  )
}
