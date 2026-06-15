import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Tag } from 'lucide-react'
import { hostnameCommands } from '../../lib/hiveosCli'

/** Guided identity config: the AP hostname (1-32 chars). Confirmed HiveOS syntax. */
export function IdentityForm({ device, onApply, busy }) {
  const [hostname, setHostname] = useState('')

  const apply = () => {
    const name = hostname.trim()
    if (!name) return
    onApply(device, { commands: hostnameCommands(name), save: true })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Tag} title="Identity" />
      <label className="flex max-w-xs flex-col gap-1">
        <span className="text-xs text-muted-foreground">Hostname (1–32 chars)</span>
        <MriInput
          value={hostname}
          maxLength={32}
          onChange={(e) => setHostname(e.target.value)}
          placeholder={device.label || 'lab-ap-01'}
        />
      </label>
      <MriButton size="sm" disabled={busy || !hostname.trim()} onClick={apply}>
        Set hostname
      </MriButton>
    </div>
  )
}
