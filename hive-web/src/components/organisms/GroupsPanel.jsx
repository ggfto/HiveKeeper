import { useState } from 'react'
import { MriInput, MriButton, MriSelect, MriStatusBadge } from '@mriqbox/ui-kit'
import { ConfirmButton } from '../molecules/ConfirmButton'
import { siteName } from '../../lib/fleet'

/**
 * Lists the organization's groups (site-pinned or cross-site tag), and creates, edits, or deletes them.
 * Editing covers both the name and the site, so a group can be moved between sites or turned into a cross-site
 * tag; a site-pinned group is an admin-on-that-site change and a cross-site tag is an org-level one — the
 * gateway enforces which (and, for a move, admin on both the old and new lineage). Deleting a group drops its
 * device tags but never the devices. `onUpdate(group, { name, siteId })` and `onDelete(group)` carry the whole
 * group so the page can phrase its toast and resolve the id.
 */
export function GroupsPanel({ groups, sites = [], onCreate, onUpdate, onDelete, busy }) {
  const [name, setName] = useState('')
  const [siteId, setSiteId] = useState('')
  const [editingId, setEditingId] = useState(null)
  const [draftName, setDraftName] = useState('')
  const [draftSite, setDraftSite] = useState('')

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

  const startEdit = (g) => {
    setEditingId(g.groupId)
    setDraftName(g.name)
    setDraftSite(g.siteId || '')
  }
  const saveEdit = (g) => {
    const next = draftName.trim()
    if (!next) return
    onUpdate?.(g, { name: next, siteId: draftSite || null })
    setEditingId(null)
  }

  return (
    <div className="space-y-4">
      {Array.isArray(groups) && groups.length > 0 ? (
        <ul className="space-y-1.5">
          {groups.map((g) => (
            <li key={g.groupId} className="flex flex-wrap items-center gap-2 text-sm">
              {editingId === g.groupId ? (
                <>
                  <MriInput
                    value={draftName}
                    onChange={(e) => setDraftName(e.target.value)}
                    aria-label={`Rename ${g.name}`}
                    className="w-40"
                  />
                  <div className="w-44">
                    <MriSelect options={siteOptions} value={draftSite} onChange={setDraftSite} size="sm" />
                  </div>
                  <MriButton size="sm" disabled={busy} onClick={() => saveEdit(g)}>
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
                  <MriButton size="sm" variant="ghost" disabled={busy} onClick={() => startEdit(g)}>
                    Edit
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
        <label className="flex w-full flex-col gap-1 sm:w-48">
          <span className="text-xs text-muted-foreground">Name</span>
          <MriInput value={name} onChange={(e) => setName(e.target.value)} placeholder="Floor 3" />
        </label>
        <label className="flex w-full flex-col gap-1 sm:w-48">
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
