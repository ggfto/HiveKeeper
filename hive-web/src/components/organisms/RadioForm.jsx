import { useState } from 'react'
import { MriSelect, MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Radio, TriangleAlert } from 'lucide-react'
import { radioCommands } from '../../lib/hiveosCli'
import { radioAdvisories } from '../../lib/radioAdvisories'

const RADIOS = [
  { label: 'wifi0 (2.4 GHz)', value: 'wifi0' },
  { label: 'wifi1 (5 GHz)', value: 'wifi1' },
]
// Per-radio operational mode (interface wifiN mode), confirmed via `?` on the AP230.
const MODES = [
  { label: '(unchanged)', value: '' },
  { label: 'access (serve clients)', value: 'access' },
  { label: 'backhaul (mesh uplink)', value: 'backhaul' },
  { label: 'dual (access + backhaul)', value: 'dual' },
  { label: 'sensor (WIPS)', value: 'sensor' },
  { label: 'wan-client (L3 WAN)', value: 'wan-client' },
]

/** Guided radio config (per radio): channel, transmit power, operational mode. Confirmed HiveOS syntax. */
export function RadioForm({ device, onApply, busy }) {
  const [iface, setIface] = useState('wifi0')
  const [channel, setChannel] = useState('')
  const [power, setPower] = useState('')
  const [mode, setMode] = useState('')

  const apply = () => {
    const commands = radioCommands(iface, { channel: channel.trim(), power: power.trim(), mode })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  // Best-practice advisories for what's typed (channel/power on the selected band). Non-blocking: they explain
  // the trade-off but never stop an apply.
  const advisories = radioAdvisories({ iface, channel: channel.trim(), power: power.trim() })

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Radio} title="Radio" />
      <p className="text-xs text-muted-foreground">
        Channel: a number or <code className="font-mono">auto</code>. Power: 1–20 dBm or{' '}
        <code className="font-mono">auto</code>. Leave a field blank to keep it.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Radio</span>
          <MriSelect options={RADIOS} value={iface} onChange={setIface} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Mode</span>
          <MriSelect options={MODES} value={mode} onChange={setMode} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Channel</span>
          <MriInput value={channel} onChange={(e) => setChannel(e.target.value)} placeholder="auto or 36" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Power (dBm)</span>
          <MriInput value={power} onChange={(e) => setPower(e.target.value)} placeholder="auto or 12" />
        </label>
      </div>
      {advisories.length > 0 && (
        <ul className="space-y-2" data-testid="radio-advisories">
          {advisories.map((a) => (
            <li
              key={a.code}
              className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400"
            >
              <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{a.message}</span>
            </li>
          ))}
        </ul>
      )}
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply radio
      </MriButton>
    </div>
  )
}
