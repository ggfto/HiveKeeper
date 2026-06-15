import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Router, TriangleAlert } from 'lucide-react'
import { clientModeConnectCommands, clientModeDisconnectCommands } from '../../lib/hiveosCli'

/**
 * Device-level CLIENT mode: the AP stops serving clients and associates with another AP as a station, using an
 * existing SSID profile (created in the Wi-Fi tab with the upstream AP's SSID + passphrase). DANGEROUS — the
 * AP may drop off the LAN, so both actions are confirm-gated and clearly warned.
 */
export function ClientModeForm({ device, onApply, busy }) {
  const [profile, setProfile] = useState('')

  const connect = () => {
    const p = profile.trim()
    if (!p) return
    const ok = window.confirm(
      `Switch ${device.mgmtIp || 'this AP'} to CLIENT mode using SSID profile "${p}"? The AP will stop ` +
        `serving clients and try to associate with another AP — you may LOSE management access and need ` +
        `physical access to revert.`,
    )
    if (!ok) return
    onApply(device, { commands: clientModeConnectCommands(p), save: true })
  }

  const disconnect = () => {
    if (!window.confirm(`Return ${device.mgmtIp || 'this AP'} to normal AP mode (disconnect client mode)?`)) return
    onApply(device, { commands: clientModeDisconnectCommands(), save: true })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Router} title="Client mode (device as a wireless client)" />
      <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-2 text-xs text-destructive">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          In client mode the AP stops serving clients and associates with another AP as a station. It may drop
          off the LAN — you could lose management access and need physical access to revert. Create the SSID
          profile (with the upstream AP&apos;s SSID + passphrase) in the Wi-Fi tab first.
        </span>
      </div>
      <label className="flex max-w-xs flex-col gap-1">
        <span className="text-xs text-muted-foreground">SSID profile to connect to</span>
        <MriInput value={profile} onChange={(e) => setProfile(e.target.value)} placeholder="upstream-ssid" />
      </label>
      <div className="flex flex-wrap gap-2">
        <MriButton size="sm" variant="destructive" disabled={busy || !profile.trim()} onClick={connect}>
          Connect as client
        </MriButton>
        <MriButton size="sm" variant="outline" disabled={busy} onClick={disconnect}>
          Back to AP mode
        </MriButton>
      </div>
    </div>
  )
}
