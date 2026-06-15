import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { ListChecks } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { BulkOpsPanel } from '../components/organisms/BulkOpsPanel'

/** Fan a read op (backup|inventory) across a scope (org/site/group); the gateway re-authorizes each device. */
export function BulkPage() {
  const { gateway, activeOrg } = useAuth()
  const [sites, setSites] = useState([])
  const [groups, setGroups] = useState([])
  const [result, setResult] = useState(null)
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const s = await gateway.sites().catch(() => [])
    const g = await gateway.groups().catch(() => [])
    setSites(Array.isArray(s) ? s : [])
    setGroups(Array.isArray(g) ? g : [])
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const onRun = async (op, target) => {
    setBusy(true)
    setStatus(`Running bulk ${op}…`)
    setResult(null)
    try {
      const r = await gateway.bulk(op, target)
      setResult(r)
      setStatus('')
    } catch (e) {
      setStatus(`Bulk ${op}: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      <MriPageHeader title="Bulk ops" icon={ListChecks} />
      <BulkOpsPanel sites={sites} groups={groups} onRun={onRun} result={result} busy={busy} />
      {status && <p className="text-sm text-muted-foreground">{status}</p>}
    </div>
  )
}
