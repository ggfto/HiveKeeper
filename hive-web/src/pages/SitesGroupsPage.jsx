import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { Layers } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { SitesPanel } from '../components/organisms/SitesPanel'
import { GroupsPanel } from '../components/organisms/GroupsPanel'

/** The organization structure: sites (an agent's physical LAN) and groups (site-pinned or cross-site tags),
 *  each created, renamed, and deleted here. The gateway authorizes every change by scoped role and refuses
 *  deleting a site that still has devices or groups. */
export function SitesGroupsPage() {
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const [groups, setGroups] = useState(null)
  const [sites, setSites] = useState([])
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

  // Run a structural mutation: busy-gate it, toast the outcome (the gateway's message on failure — e.g. a
  // duplicate name or a non-empty site), then reload so the panels reflect the new state.
  const run = async (success, fn) => {
    setBusy(true)
    try {
      await fn()
      toast(success, 'success')
      await load()
    } catch (e) {
      // the gateway's friendly text lives in detail (message is the short error code)
      toast(e.body?.detail || e.message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const onCreateSite = (name) => run(`Created site ${name}.`, () => gateway.createSite(name))
  const onRenameSite = (site, name) => run(`Renamed site to ${name}.`, () => gateway.renameSite(site.siteId, name))
  const onDeleteSite = (site) => run(`Deleted site ${site.name}.`, () => gateway.deleteSite(site.siteId))

  const onCreateGroup = (name, siteId) => run(`Created group ${name}.`, () => gateway.createGroup(name, siteId))
  const onRenameGroup = (group, name) =>
    run(`Renamed group to ${name}.`, () => gateway.renameGroup(group.groupId, name))
  const onDeleteGroup = (group) => run(`Deleted group ${group.name}.`, () => gateway.deleteGroup(group.groupId))

  return (
    <div className="space-y-6">
      <MriPageHeader title="Sites & Groups" icon={Layers} />

      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-muted-foreground">Sites ({sites.length})</h2>
        <SitesPanel
          sites={sites}
          onCreate={onCreateSite}
          onRename={onRenameSite}
          onDelete={onDeleteSite}
          busy={busy}
        />
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-muted-foreground">Groups</h2>
        <GroupsPanel
          groups={groups}
          sites={sites}
          onCreate={onCreateGroup}
          onRename={onRenameGroup}
          onDelete={onDeleteGroup}
          busy={busy}
        />
      </section>
    </div>
  )
}
