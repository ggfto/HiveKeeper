import { useState } from 'react'
import { MriInput, MriButton, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { Globe, TriangleAlert } from 'lucide-react'
import { managementCommands } from '../../lib/hiveosCli'

const DHCP_OPTIONS = [
  { label: '(unchanged)', value: '' },
  { label: 'Enable DHCP client', value: 'enable' },
  { label: 'Disable (use static IP)', value: 'disable' },
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
 * Management (mgt0) network config: static IP/netmask, default gateway, management + native VLAN, and the DHCP
 * client. DANGEROUS — changing the IP, VLAN or DHCP can drop the connection this agent uses, so apply is
 * confirm-gated and warned. Only changed fields are sent.
 */
export function NetworkForm({ device, onApply, busy }) {
  const [v, setV] = useState({ ip: '', netmask: '255.255.255.0', gateway: '', vlan: '', nativeVlan: '', dhcp: '' })
  const set = (k) => (val) => setV((prev) => ({ ...prev, [k]: val }))

  const apply = () => {
    const commands = managementCommands({
      ip: v.ip.trim(),
      netmask: v.netmask.trim(),
      gateway: v.gateway.trim(),
      vlan: v.vlan.trim(),
      nativeVlan: v.nativeVlan.trim(),
      dhcp: v.dhcp,
    })
    if (commands.length === 0) return
    const ok = window.confirm(
      `Apply management changes to ${device.mgmtIp || 'this AP'}? Changing the IP, VLAN or DHCP can drop this ` +
        `connection — you may need to re-adopt the AP at the new address.`,
    )
    if (!ok) return
    onApply(device, { commands, save: true })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Globe} title="Management (mgt0)" />
      <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-2 text-xs text-destructive">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          These change the AP&apos;s management interface. The IP, VLAN or DHCP can drop this connection — you
          may need to re-adopt at the new address.
        </span>
      </div>
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="IP address" value={v.ip} onChange={set('ip')} placeholder={device.mgmtIp || '192.168.1.100'} />
        <Field label="Netmask" value={v.netmask} onChange={set('netmask')} placeholder="255.255.255.0" />
        <Field label="Default gateway" value={v.gateway} onChange={set('gateway')} placeholder="192.168.1.1" />
        <Field label="Management VLAN" value={v.vlan} onChange={set('vlan')} placeholder="10" />
        <Field label="Native VLAN" value={v.nativeVlan} onChange={set('nativeVlan')} placeholder="1" />
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">DHCP client</span>
          <MriSelect options={DHCP_OPTIONS} value={v.dhcp} onChange={set('dhcp')} />
        </label>
      </div>
      <MriButton size="sm" variant="destructive" disabled={busy} onClick={apply}>
        Apply management
      </MriButton>
    </div>
  )
}
