import { useState, useEffect } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Radio, TriangleAlert } from 'lucide-react'
import { radioProfileCommands } from '../../lib/hiveosCli'
import { radioAdvisories } from '../../lib/radioAdvisories'
import { radioOptions, bandForChannel } from '../../lib/radioInterfaces'

// HiveOS keeps channel width and the density knobs on a NAMED radio profile that the wifiN interfaces reference,
// not on the interface itself. Defaults confirmed live on an AP230: radio_ng0 (2.4 GHz), radio_ac0 (5 GHz).
// 160 MHz and the explicit 40-above/40-below offsets are offered by Wi-Fi 6 hardware (confirmed on an AP630
// and AP410C-1, HiveOS 10.6r6). The AP230 rejects 160 — that is a limit of the AP230's radio, not of HiveOS,
// so the option is listed and the device is left to refuse it rather than hidden from every AP.
const WIDTHS = [
  { label: '(unchanged)', value: '' },
  { label: '20 MHz', value: '20' },
  { label: '40 MHz', value: '40' },
  { label: '40 MHz (above)', value: '40-above' },
  { label: '40 MHz (below)', value: '40-below' },
  { label: '80 MHz', value: '80' },
  { label: '160 MHz (Wi-Fi 6)', value: '160' },
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
  { label: '11ax — 2.4 GHz', value: '11ax-2g' },
  { label: '11ax — 5 GHz', value: '11ax-5g' },
  { label: '11b/g', value: '11b/g' },
  { label: '11na', value: '11na' },
  { label: '11ng', value: '11ng' },
]
// The AP630 and AP410C-1 are 4x4 and report `Range: 1-4, Default: 4` — capping the picker at 3 made it
// impossible to set (or restore) the radio's own default on Wi-Fi 6 hardware.
const CHAINS = [
  { label: '(unchanged)', value: '' },
  { label: '1', value: '1' },
  { label: '2', value: '2' },
  { label: '3', value: '3' },
  { label: '4', value: '4' },
]

// Infer the band from the profile name so the channel-width advisory knows which band it is judging.
//
// HiveOS names its factory profiles radio_<phy><band-letter><n>, where the band letter follows the 802.11
// convention: `g` = 2.4 GHz, `a` = 5 GHz. On Wi-Fi 6 hardware that yields radio_axg0 (2.4 GHz) and radio_axa0
// (5 GHz) — confirmed live on an AP630 and AP410C-1. Matching on a bare `ax` substring therefore read the
// 2.4 GHz profile as 5 GHz and judged it by the wrong rules (notably, it stopped warning about 40 MHz on
// 2.4 GHz, which is the one width advisory that matters most). Read the band letter instead of guessing.
function ifaceForProfile(name) {
  const p = (name || '').toLowerCase()
  const m = /^radio_([a-z]+?)([ag])\d*$/.exec(p)
  if (m) return m[2] === 'g' ? 'wifi0' : 'wifi1'
  // Legacy/short names with no band letter: radio_ng0 is 2.4 GHz, radio_ac0 is 5 GHz.
  if (p.includes('ng')) return 'wifi0'
  if (p.includes('ac')) return 'wifi1'
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
// <name>`). The radios come from the device, plus "don't bind".
function bindTargets(device) {
  return [{ label: "(don't bind)", value: '' }, ...radioOptions(device)]
}

// Infer the band a phymode belongs to so we can warn on a band mismatch when binding (e.g. an 11ac profile
// bound to the 2.4 GHz radio). Returns a BAND rather than an interface name: with three radios, two of which
// are 5 GHz, "which interface does this phymode want" has no single answer, but "which band" always does.
function bandForPhymode(phymode) {
  if (phymode === '11a' || phymode === '11ac' || phymode === '11na' || phymode === '11ax-5g') return '5'
  if (phymode === '11b/g' || phymode === '11ng' || phymode === '11ax-2g') return '2.4'
  return null
}

// The band of the radio being bound to. The channel the device reported decides it; failing that (the radio is
// down, or the device has not been inventoried) fall back to the wifi0 = 2.4 / wifi1 = 5 convention. The
// fallback is deliberately silent about anything beyond wifi1: on an AP410C-1 wifi2 is a second 5 GHz radio,
// and guessing from its name would be wrong as often as right.
function bandOfRadio(device, iface) {
  const radio = (device?.radios ?? []).find((r) => String(r.name || '').toLowerCase() === iface)
  const label = bandForChannel(radio?.channel)
  if (label) return label === '2.4 GHz' ? '2.4' : '5'
  return iface === 'wifi0' ? '2.4' : iface === 'wifi1' ? '5' : null
}

/**
 * Guided radio-PROFILE config: channel width, band-steering, client load-balancing and a per-profile client cap.
 * BLAST RADIUS: a profile can be shared across interfaces and across APs in a hive, so a change here is wider
 * than the per-interface channel/power in the Radio form. Confirmed HiveOS grammar (see radioProfileCommands).
 */
export function RadioProfileForm({ device, onApply, busy, loadProfiles }) {
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
  // Wi-Fi 6 (802.11ax). All ship disabled on HiveOS; blank leaves them alone.
  const [bssColor, setBssColor] = useState('')
  const [ofdmaDl, setOfdmaDl] = useState('')
  const [ofdmaUl, setOfdmaUl] = useState('')
  const [twt, setTwt] = useState('')
  const [muMimo, setMuMimo] = useState('')
  const [muMimoStationReceiveChain, setMuMimoStationReceiveChain] = useState('')
  const [bindInterface, setBindInterface] = useState('')

  // The AP's existing profiles, read from its running-config, so an adopted AP can be adjusted from what it
  // already has rather than blind. Optional: without a loadProfiles prop the form is create-only as before.
  const [existing, setExisting] = useState([])
  useEffect(() => {
    if (!loadProfiles || !device) return
    let alive = true
    loadProfiles(device)
      .then((list) => alive && setExisting(Array.isArray(list) ? list : []))
      .catch(() => alive && setExisting([]))
    return () => {
      alive = false
    }
  }, [loadProfiles, device])

  // Load an existing profile's current values into the form — "see what is there, change what you want".
  const preload = (p) => {
    if (!p) return
    setProfile(p.name)
    setChannelWidth(p.channelWidth || '')
    setBandSteering(p.bandSteering || '')
    setClientLoadBalance(p.clientLoadBalance || '')
    setMaxClient(p.maxClient || '')
    setDfs(p.dfs || '')
    setShortGuardInterval(p.shortGuardInterval || '')
    setAmpdu(p.ampdu || '')
    setAmsdu(p.amsdu || '')
    setFrameburst(p.frameburst || '')
    setHighDensity(p.highDensity || '')
    setWeakSnrSuppress(p.weakSnrSuppress || '')
    setTxBeamforming(p.txBeamforming || '')
    setPhymode(p.phymode || '')
    setReceiveChain(p.receiveChain || '')
    setTransmitChain(p.transmitChain || '')
    setBssColor(p.bssColor || '')
    setOfdmaDl(p.ofdmaDl || '')
    setOfdmaUl(p.ofdmaUl || '')
    setTwt(p.twt || '')
    setMuMimo(p.muMimo || '')
    setBindInterface((p.boundInterfaces && p.boundInterfaces[0]) || '')
  }

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
      bssColor: bssColor.trim(),
      ofdmaDl,
      ofdmaUl,
      twt,
      muMimo,
      muMimoStationReceiveChain: muMimoStationReceiveChain.trim(),
      bindInterface,
    })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  // Surface the channel-width best-practice advisory right where the fix lives (the profile that owns the width).
  // The phymode, when set, names the band outright and beats guessing it from the profile name.
  const advisories = channelWidth
    ? radioAdvisories({
        band: bandForPhymode(phymode) ?? undefined,
        iface: ifaceForProfile(profile),
        width: channelWidth,
      })
    : []
  const defaultProfile = isDefaultProfile(profile)
  // Warn if binding an N-band profile to the other band's radio (a likely mistake). Only when both are known —
  // and the target's band comes from its channel, so a second 5 GHz radio is judged correctly.
  const wantedBand = bandForPhymode(phymode)
  const targetBand = bindInterface ? bandOfRadio(device, bindInterface) : null
  const bandMismatch = Boolean(wantedBand && targetBand && wantedBand !== targetBand)

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
      {existing.length > 0 && (
        <label className="flex flex-col gap-1" htmlFor="rp-load">
          <span className="text-xs text-muted-foreground">
            Carregar um perfil existente do AP (vê os valores atuais e ajusta)
          </span>
          <select
            id="rp-load"
            className={selectClass}
            defaultValue=""
            onChange={(e) => preload(existing.find((p) => p.name === e.target.value))}
          >
            <option value="">— escolher —</option>
            {existing.map((p) => (
              <option key={p.name} value={p.name}>
                {p.name}
                {p.phymode ? ` (${p.phymode})` : ''}
              </option>
            ))}
          </select>
        </label>
      )}
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
            {bindTargets(device).map((o) => (
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
                {wantedBand === '5' ? '5 GHz' : '2.4 GHz'} radio, not{' '}
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
      <details className="rounded-md border border-border">
        <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-muted-foreground">
          Wi-Fi 6 (802.11ax)
        </summary>
        <div className="space-y-3 px-3 pb-3">
          <p className="text-xs text-muted-foreground">
            Only on Wi-Fi 6 hardware (AP410C, AP630 and newer), and only once the PHY mode above is{' '}
            <code className="font-mono">11ax-2g</code> or <code className="font-mono">11ax-5g</code>. HiveOS
            ships every one of these <strong>disabled</strong>, so a Wi-Fi 6 AP runs without them until you
            turn them on. An older AP will simply reject the line.
          </p>
          <div className="grid gap-2 sm:grid-cols-2">
            <label className="flex flex-col gap-1" htmlFor="rp-ofdma-dl">
              <span className="text-xs text-muted-foreground">OFDMA downlink</span>
              <select
                id="rp-ofdma-dl"
                className={selectClass}
                value={ofdmaDl}
                onChange={(e) => setOfdmaDl(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-ofdma-ul">
              <span className="text-xs text-muted-foreground">OFDMA uplink</span>
              <select
                id="rp-ofdma-ul"
                className={selectClass}
                value={ofdmaUl}
                onChange={(e) => setOfdmaUl(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-twt">
              <span className="text-xs text-muted-foreground">TWT (target wake time)</span>
              <select id="rp-twt" className={selectClass} value={twt} onChange={(e) => setTwt(e.target.value)}>
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-mu-mimo">
              <span className="text-xs text-muted-foreground">MU-MIMO</span>
              <select
                id="rp-mu-mimo"
                className={selectClass}
                value={muMimo}
                onChange={(e) => setMuMimo(e.target.value)}
              >
                {TOGGLE.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-bss-color">
              <span className="text-xs text-muted-foreground">BSS color (blank = unchanged)</span>
              <MriInput
                id="rp-bss-color"
                value={bssColor}
                onChange={(e) => setBssColor(e.target.value)}
                placeholder="1-63"
              />
            </label>
            <label className="flex flex-col gap-1" htmlFor="rp-mu-mimo-chain">
              <span className="text-xs text-muted-foreground">MU-MIMO min. station chains</span>
              <MriInput
                id="rp-mu-mimo-chain"
                value={muMimoStationReceiveChain}
                onChange={(e) => setMuMimoStationReceiveChain(e.target.value)}
                placeholder="e.g. 2"
              />
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
