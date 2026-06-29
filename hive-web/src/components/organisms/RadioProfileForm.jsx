import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Radio, TriangleAlert } from 'lucide-react'
import { radioProfileCommands } from '../../lib/hiveosCli'
import { radioAdvisories } from '../../lib/radioAdvisories'

// HiveOS keeps channel width and the density knobs on a NAMED radio profile that the wifiN interfaces reference,
// not on the interface itself. Defaults confirmed live on an AP230: radio_ng0 (2.4 GHz), radio_ac0 (5 GHz).
const WIDTHS = [
  { label: '(unchanged)', value: '' },
  { label: '20 MHz', value: '20' },
  { label: '40 MHz', value: '40' },
  { label: '80 MHz', value: '80' },
]
const TOGGLE = [
  { label: '(unchanged)', value: '' },
  { label: 'Enable', value: 'enable' },
  { label: 'Disable', value: 'disable' },
]

// Infer the band from the profile name so the channel-width advisory knows which band it is judging. radio_ng0
// is 2.4 GHz (wifi0), radio_ac0 / radio_ax0 are 5 GHz (wifi1). Unknown names get no advisory rather than a guess.
function ifaceForProfile(name) {
  const p = (name || '').toLowerCase()
  if (p.includes('ng')) return 'wifi0'
  if (p.includes('ac') || p.includes('ax')) return 'wifi1'
  return null
}

/**
 * Guided radio-PROFILE config: channel width, band-steering, client load-balancing and a per-profile client cap.
 * BLAST RADIUS: a profile can be shared across interfaces and across APs in a hive, so a change here is wider
 * than the per-interface channel/power in the Radio form. Confirmed HiveOS grammar (see radioProfileCommands).
 */
export function RadioProfileForm({ device, onApply, busy }) {
  const [profile, setProfile] = useState('radio_ac0')
  const [channelWidth, setChannelWidth] = useState('')
  const [bandSteering, setBandSteering] = useState('')
  const [clientLoadBalance, setClientLoadBalance] = useState('')
  const [maxClient, setMaxClient] = useState('')

  const apply = () => {
    const commands = radioProfileCommands(profile.trim(), {
      channelWidth,
      bandSteering,
      clientLoadBalance,
      maxClient: maxClient.trim(),
    })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  // Surface the channel-width best-practice advisory right where the fix lives (the profile that owns the width).
  const advisories = channelWidth
    ? radioAdvisories({ iface: ifaceForProfile(profile), width: channelWidth })
    : []

  const selectClass =
    'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Radio} title="Radio profile" />
      <p className="text-xs text-muted-foreground">
        Channel width and the density knobs live on the named radio profile your interfaces reference (defaults{' '}
        <code className="font-mono">radio_ng0</code> for 2.4 GHz, <code className="font-mono">radio_ac0</code> for
        5 GHz). A profile can be shared across interfaces and APs, so a change here is wider than a per-radio tweak.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1" htmlFor="rp-profile">
          <span className="text-xs text-muted-foreground">Radio profile</span>
          <MriInput id="rp-profile" value={profile} onChange={(e) => setProfile(e.target.value)} placeholder="radio_ac0" />
        </label>
        <label className="flex flex-col gap-1" htmlFor="rp-width">
          <span className="text-xs text-muted-foreground">Channel width</span>
          <select id="rp-width" className={selectClass} value={channelWidth} onChange={(e) => setChannelWidth(e.target.value)}>
            {WIDTHS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1" htmlFor="rp-band-steering">
          <span className="text-xs text-muted-foreground">Band steering</span>
          <select
            id="rp-band-steering"
            className={selectClass}
            value={bandSteering}
            onChange={(e) => setBandSteering(e.target.value)}
          >
            {TOGGLE.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1" htmlFor="rp-load-balance">
          <span className="text-xs text-muted-foreground">Client load-balancing</span>
          <select
            id="rp-load-balance"
            className={selectClass}
            value={clientLoadBalance}
            onChange={(e) => setClientLoadBalance(e.target.value)}
          >
            {TOGGLE.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1" htmlFor="rp-max-client">
          <span className="text-xs text-muted-foreground">Max clients (per profile)</span>
          <MriInput
            id="rp-max-client"
            value={maxClient}
            onChange={(e) => setMaxClient(e.target.value)}
            placeholder="e.g. 60"
          />
        </label>
      </div>
      {advisories.length > 0 && (
        <ul className="space-y-2" data-testid="profile-advisories">
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
        Apply profile
      </MriButton>
    </div>
  )
}
