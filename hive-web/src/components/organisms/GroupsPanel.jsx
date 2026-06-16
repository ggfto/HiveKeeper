import { useState } from 'react'
import { MriInput, MriButton, MriSelect, MriStatusBadge } from '@mriqbox/ui-kit'
import { ConfirmButton } from '../molecules/ConfirmButton'
import { siteName } from '../../lib/fleet'

/**
 * Lists the organization's groups (site-pinned or cross-site tag), and creates, renames, or deletes them. A
 * site-pinned group is an admin-on-that-site change; a cross-site tag (no site) is an org-level change — the
 * gateway enforces which. Deleting a group drops its device tags but never the devices. `onRename(group, name)`
 * and `onDelete(group)` carry the whole group so the page can phrase its toast and resolve the id.
 */
export function GroupsPanel({ groups, sites = [], onCreate, onRename, onDelete, busy }) {
  const [name, setName] = useState('')
  const [siteId, setSiteId] = useState('')
  const [editingId, setEditingId] = useState(null)
  const [draft, setDraft] = useState('')

  if (groups === 'forbidden') {
    return (
      <p className="text-sm text-muted-foreground">
        Listing groups needs an organization-level viewer or admin role.
      </p>
    )
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

  const startRename = (g) => {
    setEditingId(g.groupId)
    setDraft(g.name)
  }
  const saveRename = (g) => {
    const next = draft.trim()
    if (next && next !== g.name) onRename?.(g, next)
    setEditingId(null)
  }

  return (
    <div className="space-y-4">
      {Array.isArray(groups) && groups.length > 0 ? (
        <ul className="space-y-1.5">
          {groups.map((g) => (
            <li key={g.groupId} className="flex items-center gap-2 text-sm">
              {editingId === g.groupId ? (
                <>
                  <MriInput
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    aria-label={`Rename ${g.name}`}
                  />
                  <MriButton size="sm" disabled={busy} onClick={() => saveRename(g)}>
                    Save
                  </MriButton>
                  <MriButton size="sm" variant="ghost" onClick={() => setEditingId(null)}>
                    Cancel
                  </MriButton>
                </>
              ) : (
                <>
                  <MriStatusBadge label={g.name} variant="default" size="xs" />
                  <span className="text-muted-foreground">
                    {g.siteId ? siteName(g.siteId, sites) : 'cross-site tag'}
                  </span>
                  <span className="flex-1" />
                  <MriButton size="sm" variant="ghost" disabled={busy} onClick={() => startRename(g)}>
                    Rename
                  </MriButton>
                  <ConfirmButton confirmLabel="Delete?" disabled={busy} onConfirm={() => onDelete?.(g)}>
                    Delete
                  </ConfirmButton>
                </>
              )}
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
