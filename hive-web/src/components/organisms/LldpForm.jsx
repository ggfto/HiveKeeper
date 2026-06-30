import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { RadioTower } from 'lucide-react'
import { lldpCommands } from '../../lib/hiveosCli'

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'
const TOGGLE = [
  { value: '', label: '(keep)' },
  { value: 'enable', label: 'Enable' },
  { value: 'disable', label: 'Disable' },
]

/**
 * LLDP / CDP neighbor discovery (device-wide on HiveOS — it is not a per-interface setting). Confirmed grammar on
 * an AP230: `lldp` / `no lldp`, `lldp timer <5-65534>` (advertise interval), `lldp holdtime <0-65535>`,
 * `lldp receive-only` (cache neighbors but don't advertise), `lldp max-entries <1-128>`. Only changed fields are
 * applied. Dispatched through apply-config.
 */
export function LldpForm({ device, onApply, busy }) {
  const empty = { enable: '', timer: '', holdtime: '', receiveOnly: '', maxEntries: '' }
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = lldpCommands(form)
    if (commands.length === 0 || !onApply) return
    onApply(device, { commands, save: true })
    setForm(empty)
  }

  const Toggle = ({ id, label, value, onChange }) => (
    <label className="flex flex-col gap-1" htmlFor={id}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(e) => onChange(e.target.value)}>
        {TOGGLE.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
  const Field = ({ label, value, onChange, placeholder }) => (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={RadioTower} title="LLDP / CDP discovery" />
      <p className="text-xs text-muted-foreground">
        Advertise to and learn neighboring switches/APs (link-layer discovery). Device-wide, not per-interface.
        Only the fields you change are applied.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <Toggle id="lldp-enable" label="LLDP" value={form.enable} onChange={set('enable')} />
        <Toggle id="lldp-receive-only" label="Receive only" value={form.receiveOnly} onChange={set('receiveOnly')} />
        <Field label="Advertise interval (s, 5-65534)" value={form.timer} onChange={set('timer')} placeholder="30" />
        <Field label="Hold time (s, 0-65535)" value={form.holdtime} onChange={set('holdtime')} placeholder="90" />
        <Field label="Max cached entries (1-128)" value={form.maxEntries} onChange={set('maxEntries')} placeholder="64" />
      </div>
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply LLDP
      </MriButton>
    </div>
  )
}
