import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { ScrollText } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { OperationsTable } from '../components/organisms/OperationsTable'

/** The per-tenant audit log of operations dispatched to agents (RLS-scoped on the gateway). */
export function AuditPage() {
  const { gateway, activeOrg } = useAuth()
  const [operations, setOperations] = useState([])
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setBusy(true)
    try {
      const ops = await gateway.operations()
      setOperations(Array.isArray(ops) ? ops : [])
    } catch {
      setOperations([])
    } finally {
      setBusy(false)
    }
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  return (
    <div className="space-y-4">
      <MriPageHeader title="Audit log" icon={ScrollText} count={operations.length}>
        <MriButton size="sm" variant="outline" disabled={busy} onClick={load}>
          Refresh
        </MriButton>
      </MriPageHeader>
      <OperationsTable operations={operations} />
    </div>
  )
}
