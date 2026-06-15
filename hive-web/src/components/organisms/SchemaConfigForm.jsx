import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'

/**
 * Renders a declarative config section (see lib/configSchema.js): its fields as inputs + an Apply button that
 * builds the HiveOS CLI via the section's toCli and dispatches it through apply-config. One component drives
 * every schema category, so adding a category is data, not code.
 */
export function SchemaConfigForm({ section, device, onApply, busy }) {
  const [values, setValues] = useState({})

  const apply = () => {
    const commands = section.toCli(values)
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={section.icon} title={section.label} />
      {section.hint && <p className="text-xs text-muted-foreground">{section.hint}</p>}
      <div className="grid gap-2 sm:grid-cols-2">
        {section.fields.map((f) => (
          <label key={f.key} className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">{f.label}</span>
            <MriInput
              value={values[f.key] || ''}
              onChange={(e) => setValues({ ...values, [f.key]: e.target.value })}
              placeholder={f.placeholder}
            />
          </label>
        ))}
      </div>
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply {section.label}
      </MriButton>
    </div>
  )
}
