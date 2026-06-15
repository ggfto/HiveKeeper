import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader, MriButton, MriInput } from '@mriqbox/ui-kit'
import { Layers } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { GroupsPanel } from '../components/organisms/GroupsPanel'

/** The organization structure: sites (an agent's physical LAN) and groups (site-pinned or cross-site tags). */
export function SitesGroupsPage() {
  const { gateway, activeOrg } = useAuth()
  const [groups, setGroups] = useState(null)
  const [sites, setSites] = useState([])
  const [newSite, setNewSite] = useState('')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const g = await gateway.groups().catch((e) => (e.status === 403 ? 'forbidden' : null))
    const s = await gateway.sites().catch(() => [])
    setGroups(g)
    setSites(Array.isArray(s) ? s : [])
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const onCreateGroup = async (name, siteId) => {
    setBusy(true)
    setStatus(`Creating group ${name}…`)
    try {
      await gateway.createGroup(name, siteId)
      setStatus(`Created group ${name}.`)
      await load()
    } catch (e) {
      setStatus(`Create group: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  const onCreateSite = async () => {
    const name = newSite.trim()
    if (!name) return
    setBusy(true)
    setStatus(`Creating site ${name}…`)
    try {
      await gateway.createSite(name)
      setNewSite('')
      setStatus(`Created site ${name}.`)
      await load()
    } catch (e) {
      setStatus(`Create site: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      <MriPageHeader title="Sites & Groups" icon={Layers} />

      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-muted-foreground">Sites ({sites.length})</h2>
        <ul className="space-y-1 text-sm">
          {sites.map((s) => (
            <li key={s.siteId}>{s.name}</li>
          ))}
          {sites.length === 0 && <li className="text-muted-foreground">No sites yet.</li>}
        </ul>
        <div className="flex items-end gap-2">
          <label className="flex w-48 flex-col gap-1">
            <span className="text-xs text-muted-foreground">New site</span>
            <MriInput value={newSite} onChange={(e) => setNewSite(e.target.value)} placeholder="HQ" />
          </label>
          <MriButton disabled={busy || !newSite.trim()} onClick={onCreateSite}>
            Create site
          </MriButton>
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-muted-foreground">Groups</h2>
        <GroupsPanel groups={groups} sites={sites} onCreate={onCreateGroup} busy={busy} />
      </section>

      {status && <p className="text-sm text-muted-foreground">{status}</p>}
    </div>
  )
}
