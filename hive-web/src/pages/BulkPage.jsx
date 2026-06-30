import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader, MriSectionHeader } from '@mriqbox/ui-kit'
import { ListChecks, FileCog } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { BulkOpsPanel } from '../components/organisms/BulkOpsPanel'
import { ConfigTemplatePanel } from '../components/organisms/ConfigTemplatePanel'

/** Fan a read op (backup|inventory) across a scope (org/site/group); the gateway re-authorizes each device. */
export function BulkPage() {
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const [sites, setSites] = useState([])
  const [groups, setGroups] = useState([])
  const [result, setResult] = useState(null)
  const [configResult, setConfigResult] = useState(null)
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
    setResult(null)
    try {
      setResult(await gateway.bulk(op, target))
    } catch (e) {
      toast(`Bulk ${op}: ${e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  // Fan a config template (CLI lines) across a scope as a single bulk WRITE; the gateway re-authorizes each
  // device at operator level. A separate result so it does not clobber the read-op table above.
  const onApplyTemplate = async (target, commands, save) => {
    setBusy(true)
    setConfigResult(null)
    try {
      setConfigResult(await gateway.bulkApplyConfig(target, commands, save))
    } catch (e) {
      toast(`Bulk apply-config: ${e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-8">
      <div className="space-y-4">
        <MriPageHeader title="Bulk ops" icon={ListChecks} />
        <BulkOpsPanel sites={sites} groups={groups} onRun={onRun} result={result} busy={busy} />
      </div>
      <div className="space-y-4">
        <MriSectionHeader icon={FileCog} title="Config templates" />
        <p className="text-xs text-muted-foreground">
          Apply a set of HiveOS CLI lines to every device in a scope at once. Each AP is reached through its own
          agent and credential, and re-authorized server-side. This writes to many devices — review before applying.
        </p>
        <ConfigTemplatePanel sites={sites} groups={groups} onApply={onApplyTemplate} result={configResult} busy={busy} />
      </div>
    </div>
  )
}
