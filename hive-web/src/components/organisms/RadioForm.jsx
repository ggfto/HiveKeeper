import { useState } from 'react'
import { MriSelect, MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Radio } from 'lucide-react'
import { radioCommands } from '../../lib/hiveosCli'

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
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply radio
      </MriButton>
    </div>
  )
}
