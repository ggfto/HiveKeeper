import { useState } from 'react'
import { MriInput, MriButton, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { Boxes, X } from 'lucide-react'
import { groupNamesFor } from '../../lib/fleet'

/**
 * The device's HiveKeeper metadata (NOT AP config): display name (label), pinned site, and group membership.
 * These go to the gateway (PATCH device / tag / untag), not to the AP. Group chips remove on the X; the select
 * adds the device to a group it is not already in.
 */
export function DeviceOverviewForm({ device, sites = [], groups = [], onSave, onTag, onUntag, busy }) {
  const [label, setLabel] = useState(device.label || '')
  const [siteId, setSiteId] = useState(device.siteId || '')
  const [addGroup, setAddGroup] = useState('')

  const siteOptions = [{ label: '(no site)', value: '' }, ...sites.map((s) => ({ label: s.name, value: s.siteId }))]
  const tagged = new Set(device.groups || [])
  const available = groups.filter((g) => !tagged.has(g.groupId)).map((g) => ({ label: g.name, value: g.groupId }))

  const save = () => onSave(device, { label: label.trim() || null, siteId: siteId || null })

  return (
    <div className="space-y-5">
      <div className="space-y-3">
        <MriSectionHeader icon={Boxes} title="Device" />
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">Name (label)</span>
            <MriInput value={label} onChange={(e) => setLabel(e.target.value)} placeholder={device.serial} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">Site</span>
            <MriSelect options={siteOptions} value={siteId} onChange={setSiteId} placeholder="(no site)" />
          </label>
        </div>
        <MriButton size="sm" disabled={busy} onClick={save}>
          Save
        </MriButton>
      </div>

      <div className="space-y-2">
        <span className="text-xs text-muted-foreground">Groups</span>
        <div className="flex flex-wrap items-center gap-1.5">
          {(device.groups || []).map((gid) => {
            const name = groupNamesFor({ groups: [gid] }, groups)[0]
            return (
              <span
                key={gid}
                className="inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-xs"
              >
                {name}
                <button
                  type="button"
                  aria-label={`Remove from ${name}`}
                  className="text-muted-foreground hover:text-destructive"
                  disabled={busy}
                  onClick={() => onUntag(device, gid)}
                >
                  <X className="h-3 w-3" />
                </button>
              </span>
            )
          })}
          {tagged.size === 0 && <span className="text-xs text-muted-foreground">No groups.</span>}
        </div>
        {available.length > 0 && (
          <div className="flex items-end gap-2">
            <div className="w-48">
              <MriSelect options={available} value={addGroup} onChange={setAddGroup} placeholder="Add to group…" size="sm" />
            </div>
            <MriButton
              size="sm"
              variant="outline"
              disabled={busy || !addGroup}
              onClick={() => {
                onTag(device, addGroup)
                setAddGroup('')
              }}
            >
              Add
            </MriButton>
          </div>
        )}
      </div>
    </div>
  )
}
