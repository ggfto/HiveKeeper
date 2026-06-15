import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Globe, TriangleAlert } from 'lucide-react'
import { mgtIpCommands } from '../../lib/hiveosCli'

/**
 * Guided management-IP (mgt0) config. DANGEROUS: changing the AP's management IP drops the connection this
 * agent uses to reach it — the AP must then be re-adopted at the new address. Gated behind a confirm.
 */
export function NetworkForm({ device, onApply, busy }) {
  const [ip, setIp] = useState('')
  const [netmask, setNetmask] = useState('255.255.255.0')

  const apply = () => {
    const addr = ip.trim()
    if (!addr) return
    const ok = window.confirm(
      `Change ${device.mgmtIp || 'this AP'} management IP to ${addr}? You will lose this connection and must ` +
        `re-adopt the AP at the new address.`,
    )
    if (!ok) return
    onApply(device, { commands: mgtIpCommands(addr, netmask.trim()), save: true })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Globe} title="Network (management IP)" />
      <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-2 text-xs text-destructive">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          Changing the mgt0 IP drops this connection. You will need to re-adopt the AP at the new address.
        </span>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">IP address</span>
          <MriInput value={ip} onChange={(e) => setIp(e.target.value)} placeholder={device.mgmtIp || '192.168.1.100'} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Netmask</span>
          <MriInput value={netmask} onChange={(e) => setNetmask(e.target.value)} placeholder="255.255.255.0" />
        </label>
      </div>
      <MriButton size="sm" variant="destructive" disabled={busy || !ip.trim()} onClick={apply}>
        Change management IP
      </MriButton>
    </div>
  )
}
