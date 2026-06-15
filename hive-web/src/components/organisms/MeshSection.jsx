import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriSectionHeader, MriSegmentedTabs, MriSelect } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { hiveTuningCommands } from '../../lib/hiveosCli'

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

/** Advanced per-hive RF/mesh tuning: pick a hive, set fragmentation / RTS thresholds and the minimum signal a
 *  neighboring mesh member needs to link. Builds confirmed HiveOS CLI and dispatches via apply-config. */
function HiveTuning({ device, hives, onApply, busy }) {
  const [hive, setHive] = useState(() => hives[0]?.name || '')
  const [frag, setFrag] = useState('')
  const [rts, setRts] = useState('')
  const [conn, setConn] = useState('')

  const apply = () => {
    const commands = hiveTuningCommands(hive, {
      fragThreshold: frag.trim(),
      rtsThreshold: rts.trim(),
      connectingThreshold: conn.trim(),
    })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <span className="text-xs font-medium text-muted-foreground">Advanced tuning</span>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Hive</span>
          <MriSelect
            options={hives.map((h) => ({ label: h.name, value: h.name }))}
            value={hive}
            onChange={setHive}
            placeholder="Select a hive…"
          />
        </label>
        <Field label="Frag threshold (256-2346)" value={frag} onChange={setFrag} placeholder="2346" />
        <Field label="RTS threshold (1-2346)" value={rts} onChange={setRts} placeholder="2346" />
        <Field label="Mesh connecting threshold" value={conn} onChange={setConn} placeholder="high | medium | low or -80" />
      </div>
      <MriButton size="sm" disabled={busy || !hive} onClick={apply}>
        Apply tuning
      </MriButton>
      <p className="text-xs text-muted-foreground">
        Lower frag/RTS thresholds can steady a noisy backhaul; the connecting threshold is the minimum signal
        (dBm, -90 to -55) a neighbor needs before it joins the mesh.
      </p>
    </div>
  )
}

/**
 * Mesh (hive) management: list the hives read from the AP (`show hive`) and create/join one, choosing which
 * interfaces carry it — mgt0 for the control plane, wifi0/wifi1 for the wireless backhaul (the piece the old
 * form hid by always binding mgt0). Applies confirmed HiveOS CLI through apply-config; the list re-reads after.
 * An existing hive can also be tuned (frag/RTS/connecting thresholds).
 */
export function MeshSection({ device, loadHives, applyMesh, onApply, busy }) {
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

      {onApply && hives?.length > 0 && <HiveTuning device={device} hives={hives} onApply={onApply} busy={busy} />}
    </div>
  )
}
