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
// Phase 4 advanced RF tuning, all confirmed live on the AP230. Beamforming and phymode are not plain toggles.
const BEAMFORMING = [
  { label: '(unchanged)', value: '' },
  { label: 'Auto', value: 'auto' },
  { label: 'Explicit only', value: 'explicit-only' },
  { label: 'Disable', value: 'disable' },
]
const PHYMODES = [
  { label: '(unchanged)', value: '' },
  { label: '11a', value: '11a' },
  { label: '11ac', value: '11ac' },
  { label: '11b/g', value: '11b/g' },
  { label: '11na', value: '11na' },
  { label: '11ng', value: '11ng' },
]
const CHAINS = [
  { label: '(unchanged)', value: '' },
  { label: '1', value: '1' },
  { label: '2', value: '2' },
  { label: '3', value: '3' },
]

// Infer the band from the profile name so the channel-width advisory knows which band it is judging. radio_ng0
// is 2.4 GHz (wifi0), radio_ac0 / radio_ax0 are 5 GHz (wifi1). Unknown names get no advisory rather than a guess.
function ifaceForProfile(name) {
  const p = (name || '').toLowerCase()
  if (p.includes('ng')) return 'wifi0'
  if (p.includes('ac') || p.includes('ax')) return 'wifi1'
  return null
}

// HiveOS REFUSES to edit a default radio profile ("can't configure default radio profile radio_ac0!" —
// confirmed live on the AP230). The factory profiles follow a radio_<phy><digit> naming (radio_ac0, radio_ng0,
// radio_ax0, …); a knob applied to one is rejected. The operator must use a custom profile name instead — the
// first knob line auto-creates it. This is a heuristic, non-blocking warning.
function isDefaultProfile(name) {
  return /^radio_[a-z]+\d+$/i.test((name || '').trim())
}

// A profile takes effect on a radio only once it is BOUND to the interface (`interface wifiN radio profile
// <name>`). Offer the two radios (2.4 GHz = wifi0, 5 GHz = wifi1) plus "don't bind".
const BIND_TARGETS = [
  { label: "(don't bind)", value: '' },
  { label: 'wifi0 (2.4 GHz)', value: 'wifi0' },
  { label: 'wifi1 (5 GHz)', value: 'wifi1' },
]

// Infer the band a phymode belongs to so we can warn on a band mismatch when binding (e.g. an 11ac profile bound
// to the 2.4 GHz radio). 5 GHz: 11a / 11ac / 11na. 2.4 GHz: 11b/g / 11ng. Unknown → no opinion.
function ifaceForPhymode(phymode) {
  if (phymode === '11a' || phymode === '11ac' || phymode === '11na') return 'wifi1'
  if (phymode === '11b/g' || phymode === '11ng') return 'wifi0'
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
  // Phase 4 advanced RF tuning (disclosed below; all default to unchanged).
  const [dfs, setDfs] = useState('')
  const [shortGuardInterval, setShortGuardInterval] = useState('')
  const [ampdu, setAmpdu] = useState('')
  const [amsdu, setAmsdu] = useState('')
  const [frameburst, setFrameburst] = useState('')
  const [highDensity, setHighDensity] = useState('')
  const [weakSnrSuppress, setWeakSnrSuppress] = useState('')
  const [txBeamforming, setTxBeamforming] = useState('')
  const [phymode, setPhymode] = useState('')
  const [receiveChain, setReceiveChain] = useState('')
  const [transmitChain, setTransmitChain] = useState('')
  const [bindInterface, setBindInterface] = useState('')

  const apply = () => {
    const commands = radioProfileCommands(profile.trim(), {
      channelWidth,
      bandSteering,
      clientLoadBalance,
      maxClient: maxClient.trim(),
      dfs,
      shortGuardInterval,
      ampdu,
      amsdu,
      frameburst,
      highDensity,
      weakSnrSuppress,
      txBeamforming,
      phymode,
      receiveChain,
      transmitChain,
      bindInterface,
    })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  // Surface the channel-width best-practice advisory right where the fix lives (the profile that owns the width).
  const advisories = channelWidth
    ? radioAdvisories({ iface: ifaceForProfile(profile), width: channelWidth })
    : []
  const defaultProfile = isDefaultProfile(profile)
  // Warn if binding an N-band profile to the other band's radio (a likely mistake). Only when both are known.
  const bandForPhymode = ifaceForPhymode(phymode)
  const bandMismatch = bindInterface && bandForPhymode && bindInterface !== bandForPhymode

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
        <label className="flex flex-col gap-1" htmlFor="rp-bind">
          <span className="text-xs text-muted-foreground">Bind to radio (apply this profile)</span>
          <select
            id="rp-bind"
            className={selectClass}
            value={bindInterface}
            onChange={(e) => setBindInterface(e.target.value)}
          >
            {BIND_TARGETS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
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
      {defaultProfile && (
        <div
          data-testid="default-profile-warning"
          className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400"
        >
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            <code className="font-mono">{profile.trim()}</code> looks like a factory <strong>default</strong>{' '}
            radio profile — HiveOS rejects edits to it (<em>“can&apos;t configure default radio profile”</em>).
            Use a custom profile name (e.g. <code className="font-mono">hk_5g_dense</code>); the first setting
            auto-creates it, and you then bind it to the radio with{' '}
            <code className="font-mono">interface wifiN radio profile &lt;name&gt;</code> (Advanced).
          </span>
        </div>
      )}
      {bindInterface && (
        <div
          data-testid="bind-warning"
          className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400"
        >
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            Binding swaps the entire profile on <code className="font-mono">{bindInterface}</code> and briefly
            disrupts its wireless clients (the management path on <code className="font-mono">mgt0</code> is
            unaffected).
            {bandMismatch && (
              <>
                {' '}
                <strong>Band mismatch:</strong> a <code className="font-mono">{phymode}</code> profile targets the{' '}
                {bandForPhymode === 'wifi1' ? '5 GHz' : '2.4 GHz'} radio, not{' '}
                <code className="font-mono">{bindInterface}</code> — double-check the PHY mode.
              </>
            )}
          </span>
        </div>
      )}
      <details className="rounded-md border border-border">
        <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-muted-foreground">
          Advanced RF tuning
        </summary>
        <div className="space-y-3 px-3 pb-3">
          <p className="text-xs text-muted-foreground">
            High-density and dense-RF knobs. Defaults are sensible — change these only when tuning a busy
            environment. All confirmed on HiveOS; leave a control on <em>(unchanged)</em> to keep the current value.
          </p>
          <div className="grid gap-2 sm:grid-cols-2">
            <label className="flex flex-col gap-1" htmlFor="rp-dfs">
              <span className="text-xs text-muted-foreground">DFS (radar avoidance, 5 GHz)</span>
              <select id="rp-dfs" className={selectClass} value={dfs} onChange={(e) => setDfs(e.target.value)}>
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-high-density">
              <span className="text-xs text-muted-foreground">High-density optimizations</span>
              <select
                id="rp-high-density"
                className={selectClass}
                value={highDensity}
                onChange={(e) => setHighDensity(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-weak-snr">
              <span className="text-xs text-muted-foreground">Weak-SNR suppression</span>
              <select
                id="rp-weak-snr"
                className={selectClass}
                value={weakSnrSuppress}
                onChange={(e) => setWeakSnrSuppress(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-sgi">
              <span className="text-xs text-muted-foreground">Short guard interval (400ns, 40 MHz)</span>
              <select
                id="rp-sgi"
                className={selectClass}
                value={shortGuardInterval}
                onChange={(e) => setShortGuardInterval(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-ampdu">
              <span className="text-xs text-muted-foreground">AMPDU aggregation</span>
              <select id="rp-ampdu" className={selectClass} value={ampdu} onChange={(e) => setAmpdu(e.target.value)}>
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-amsdu">
              <span className="text-xs text-muted-foreground">AMSDU aggregation</span>
              <select id="rp-amsdu" className={selectClass} value={amsdu} onChange={(e) => setAmsdu(e.target.value)}>
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-frameburst">
              <span className="text-xs text-muted-foreground">Frame bursting</span>
              <select
                id="rp-frameburst"
                className={selectClass}
                value={frameburst}
                onChange={(e) => setFrameburst(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-beamforming">
              <span className="text-xs text-muted-foreground">TX beamforming</span>
              <select
                id="rp-beamforming"
                className={selectClass}
                value={txBeamforming}
                onChange={(e) => setTxBeamforming(e.target.value)}
              >
                {BEAMFORMING.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-phymode">
              <span className="text-xs text-muted-foreground">PHY mode</span>
              <select
                id="rp-phymode"
                className={selectClass}
                value={phymode}
                onChange={(e) => setPhymode(e.target.value)}
              >
                {PHYMODES.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-rx-chain">
              <span className="text-xs text-muted-foreground">Receive chains</span>
              <select
                id="rp-rx-chain"
                className={selectClass}
                value={receiveChain}
                onChange={(e) => setReceiveChain(e.target.value)}
              >
                {CHAINS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-tx-chain">
              <span className="text-xs text-muted-foreground">Transmit chains</span>
              <select
                id="rp-tx-chain"
                className={selectClass}
                value={transmitChain}
                onChange={(e) => setTransmitChain(e.target.value)}
              >
                {CHAINS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </div>
      </details>
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply profile
      </MriButton>
    </div>
  )
}
