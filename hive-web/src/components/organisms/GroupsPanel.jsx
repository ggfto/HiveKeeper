import { useState } from 'react'
import { MriInput, MriButton, MriSelect, MriStatusBadge } from '@mriqbox/ui-kit'
import { siteName } from '../../lib/fleet'

/**
 * Lists the organization's groups (site-pinned or cross-site tag) and creates new ones. A site-pinned group is
 * an admin-on-that-site change; a cross-site tag (no site) is an org-level change — the gateway enforces which.
 */
export function GroupsPanel({ groups, sites = [], onCreate, busy }) {
  const [name, setName] = useState('')
  const [siteId, setSiteId] = useState('')

  if (groups === 'forbidden') {
    return <p className="text-sm text-muted-foreground">Listing groups needs an organization-level viewer or admin role.</p>
  }

  const siteOptions = [
    { label: 'Cross-site tag', value: '' },
    ...sites.map((s) => ({ label: s.name, value: s.siteId })),
  ]

  const submit = () => {
    const trimmed = name.trim()
    if (!trimmed) return
    onCreate?.(trimmed, siteId || null)
    setName('')
  }

  return (
    <div className="space-y-4">
      {Array.isArray(groups) && groups.length > 0 ? (
        <ul className="space-y-1.5">
          {groups.map((g) => (
            <li key={g.groupId} className="flex items-center gap-2 text-sm">
              <MriStatusBadge label={g.name} variant="default" size="xs" />
              <span className="text-muted-foreground">
                {g.siteId ? siteName(g.siteId, sites) : 'cross-site tag'}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">No groups yet.</p>
      )}

      <div className="flex flex-wrap items-end gap-2">
        <label className="flex w-48 flex-col gap-1">
          <span className="text-xs text-muted-foreground">Name</span>
          <MriInput value={name} onChange={(e) => setName(e.target.value)} placeholder="Floor 3" />
        </label>
        <label className="flex w-48 flex-col gap-1">
          <span className="text-xs text-muted-foreground">Site</span>
          <MriSelect options={siteOptions} value={siteId} onChange={setSiteId} placeholder="Cross-site tag" />
        </label>
        <MriButton disabled={busy || !name.trim()} onClick={submit}>
          Create group
        </MriButton>
      </div>
    </div>
  )
}
