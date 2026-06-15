import { useEffect, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { LayoutDashboard } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { OverviewStats } from '../components/organisms/OverviewStats'
import { OperationsTable } from '../components/organisms/OperationsTable'

const len = (x) => (Array.isArray(x) ? x.length : 0)

/** The dashboard: fleet counts for the active organization + the most recent operations. */
export function OverviewPage() {
  const { gateway, activeOrg } = useAuth()
  const [counts, setCounts] = useState({})
  const [operations, setOperations] = useState([])

  useEffect(() => {
    let cancelled = false
    Promise.all([
      gateway.agents().catch(() => []),
      gateway.devices().catch(() => []),
      gateway.sites().catch(() => []),
      gateway.groups().catch(() => []),
      gateway.operations().catch(() => []),
    ]).then(([agents, devices, sites, groups, ops]) => {
      if (cancelled) return
      setCounts({ agents: len(agents), devices: len(devices), sites: len(sites), groups: len(groups) })
      setOperations(Array.isArray(ops) ? ops.slice(0, 8) : [])
    })
    return () => {
      cancelled = true
    }
  }, [gateway, activeOrg])

  return (
    <div className="space-y-6">
      <MriPageHeader title="Overview" icon={LayoutDashboard} />
      <OverviewStats counts={counts} />
      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-muted-foreground">Recent operations</h2>
        <OperationsTable operations={operations} />
      </section>
    </div>
  )
}
