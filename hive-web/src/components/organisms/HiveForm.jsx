import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

/** Guided hive/mesh config. The gateway's driver generates the HiveOS CLI (typed path). */
export function HiveForm({ device, onConfigureHive, busy }) {
  const [hive, setHive] = useState({ name: '', password: '' })
  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Network} title="Hive (mesh)" />
      <div className="grid gap-2 sm:grid-cols-2">
        <Field label="Hive name" value={hive.name} onChange={(v) => setHive({ ...hive, name: v })} placeholder="hk-hive" />
        <Field
          label="Hive password"
          value={hive.password}
          onChange={(v) => setHive({ ...hive, password: v })}
          placeholder="shared key"
        />
      </div>
      <MriButton
        size="sm"
        disabled={busy || !hive.name || !hive.password}
        onClick={() => onConfigureHive(device, { name: hive.name, password: hive.password })}
      >
        Join hive
      </MriButton>
    </div>
  )
}
