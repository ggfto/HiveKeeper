import { useState } from 'react'
import { MriInput, MriButton } from '@mriqbox/ui-kit'
import { ConfirmButton } from '../molecules/ConfirmButton'

/**
 * The organization's sites (each an agent's physical LAN): list them with inline rename + a guarded delete, and
 * create new ones. Creating, renaming, and deleting a site are org-admin changes (the gateway enforces). A site
 * can only be deleted once empty — the gateway refuses while devices or groups are still pinned to it, which the
 * page surfaces as an error toast. `onRename(site, name)` and `onDelete(site)` carry the whole site.
 */
export function SitesPanel({ sites = [], onCreate, onRename, onDelete, busy }) {
  const [newSite, setNewSite] = useState('')
  const [editingId, setEditingId] = useState(null)
  const [draft, setDraft] = useState('')

  const startRename = (s) => {
    setEditingId(s.siteId)
    setDraft(s.name)
  }
  const saveRename = (s) => {
    const next = draft.trim()
    if (next && next !== s.name) onRename?.(s, next)
    setEditingId(null)
  }
  const create = () => {
    const name = newSite.trim()
    if (!name) return
    onCreate?.(name)
    setNewSite('')
  }

  return (
    <div className="space-y-3">
      {sites.length === 0 ? (
        <p className="text-sm text-muted-foreground">No sites yet.</p>
      ) : (
        <ul className="space-y-1.5">
          {sites.map((s) => (
            <li key={s.siteId} className="flex items-center gap-2 text-sm">
              {editingId === s.siteId ? (
                <>
                  <MriInput
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    aria-label={`Rename ${s.name}`}
                  />
                  <MriButton size="sm" disabled={busy} onClick={() => saveRename(s)}>
                    Save
                  </MriButton>
                  <MriButton size="sm" variant="ghost" onClick={() => setEditingId(null)}>
                    Cancel
                  </MriButton>
                </>
              ) : (
                <>
                  <span className="font-medium">{s.name}</span>
                  <span className="flex-1" />
                  <MriButton size="sm" variant="ghost" disabled={busy} onClick={() => startRename(s)}>
                    Rename
                  </MriButton>
                  <ConfirmButton confirmLabel="Delete?" disabled={busy} onConfirm={() => onDelete?.(s)}>
                    Delete
                  </ConfirmButton>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      <div className="flex items-end gap-2">
        <label className="flex w-48 flex-col gap-1">
          <span className="text-xs text-muted-foreground">New site</span>
          <MriInput value={newSite} onChange={(e) => setNewSite(e.target.value)} placeholder="HQ" />
        </label>
        <MriButton disabled={busy || !newSite.trim()} onClick={create}>
          Create site
        </MriButton>
      </div>
    </div>
  )
}
