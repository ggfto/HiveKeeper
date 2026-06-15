import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriSectionHeader, MriSegmentedTabs } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'

const INTERFACES = [
  { id: 'mgt0', label: 'mgt0' },
  { id: 'wifi0', label: 'wifi0 (2.4G)' },
  { id: 'wifi1', label: 'wifi1 (5G)' },
]

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

/**
 * Mesh (hive) management: list the hives read from the AP (`show hive`) and create/join one, choosing which
 * interfaces carry it — mgt0 for the control plane, wifi0/wifi1 for the wireless backhaul (the piece the old
 * form hid by always binding mgt0). Applies confirmed HiveOS CLI through apply-config; the list re-reads after.
 */
export function MeshSection({ device, loadHives, applyMesh, busy }) {
  const [hives, setHives] = useState(null)
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [interfaces, setInterfaces] = useState(['mgt0'])

  const refresh = useCallback(() => {
    setHives(null)
    return loadHives(device)
      .then((l) => setHives(Array.isArray(l) ? l : []))
      .catch(() => setHives([]))
  }, [loadHives, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  const apply = () => {
    if (!name.trim()) return
    Promise.resolve(applyMesh(device, { name: name.trim(), password: password.trim(), interfaces })).then(refresh)
    setName('')
    setPassword('')
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <MriSectionHeader icon={Network} title="Mesh (hive)" />
        <MriButton size="sm" variant="ghost" disabled={busy} onClick={refresh}>
          Refresh
        </MriButton>
      </div>

      {hives === null ? (
        <p className="text-sm text-muted-foreground">Loading hives from the AP…</p>
      ) : hives.length === 0 ? (
        <p className="text-sm text-muted-foreground">No hives configured.</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {hives.map((h) => (
            <li
              key={h.name}
              className="flex items-center gap-1.5 rounded-md border border-border bg-card px-2 py-1 text-sm"
            >
              <Network className="h-3.5 w-3.5 text-primary" />
              <span className="font-medium">{h.name}</span>
              {h.nativeVlan != null && <span className="text-xs text-muted-foreground">VLAN {h.nativeVlan}</span>}
            </li>
          ))}
        </ul>
      )}

      <div className="space-y-3 rounded-md border border-border p-3">
        <span className="text-xs font-medium text-muted-foreground">Create / join a hive</span>
        <div className="grid gap-2 sm:grid-cols-2">
          <Field label="Hive name" value={name} onChange={setName} placeholder="hk-mesh" />
          <Field label="Password" value={password} onChange={setPassword} placeholder="shared key" />
        </div>
        <div className="space-y-1">
          <span className="text-xs text-muted-foreground">
            Interfaces — mgt0 carries control; wifi0/wifi1 carry the wireless backhaul
          </span>
          <MriSegmentedTabs type="multiple" items={INTERFACES} value={interfaces} onChange={setInterfaces} />
        </div>
        <MriButton size="sm" disabled={busy || !name.trim()} onClick={apply}>
          Apply hive
        </MriButton>
        <p className="text-xs text-muted-foreground">
          For a wireless backhaul, also set the chosen radio&apos;s mode to backhaul or dual in the Radio tab.
        </p>
      </div>
    </div>
  )
}
