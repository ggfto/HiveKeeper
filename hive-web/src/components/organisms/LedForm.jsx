import { useState } from 'react'
import { MriButton, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { Lightbulb } from 'lucide-react'
import { ledCommands } from '../../lib/hiveosCli'

const BRIGHTNESS = [
  { label: 'Bright (default)', value: 'bright' },
  { label: 'Off', value: 'off' },
]
const POWER_SAVING = [
  { label: 'Unchanged', value: '' },
  { label: 'Enabled', value: 'enable' },
  { label: 'Disabled', value: 'disable' },
]

/**
 * Status LED control. On Aerohive APs the LED colour also signals cloud-management state — white = cloud-managed,
 * amber = standalone — so amber is normal under HiveKeeper (the AP is not phoning home). This sets the LED
 * brightness (or turns the LEDs off) and the power-saving dimming. Confirmed HiveOS CLI via apply-config.
 */
export function LedForm({ device, onApply, busy }) {
  const [brightness, setBrightness] = useState('bright')
  const [powerSaving, setPowerSaving] = useState('')

  const apply = () => {
    const commands = ledCommands({ brightness, powerSaving })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Lightbulb} title="Status LED" />
      <p className="text-xs text-muted-foreground">
        Set the AP&apos;s LED brightness (or turn it off). Amber means standalone, not a fault — white would mean
        the AP is cloud-managed.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Brightness</span>
          <MriSelect options={BRIGHTNESS} value={brightness} onChange={setBrightness} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Power saving</span>
          <MriSelect options={POWER_SAVING} value={powerSaving} onChange={setPowerSaving} placeholder="Unchanged" />
        </label>
      </div>
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply LED settings
      </MriButton>
    </div>
  )
}
