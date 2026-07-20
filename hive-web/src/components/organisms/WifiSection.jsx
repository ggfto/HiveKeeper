import { useCallback, useEffect, useState } from 'react'
import {
  MriAccordion,
  MriAccordionItem,
  MriAccordionTrigger,
  MriAccordionContent,
  MriInput,
  MriButton,
  MriStatusBadge,
  MriSectionHeader,
} from '@mriqbox/ui-kit'
import { Wifi, Lock, LockOpen, ShieldCheck, KeyRound, Gauge } from 'lucide-react'
import {
  minRateCommands,
  ssidHardeningCommands,
  ppskCommands,
  ppskRadiusCommands,
  ssidQosCommands,
} from '../../lib/hiveosCli'

// Data-rate ladders (Mbps) per band, for the minimum-data-rate picker. 2.4 GHz (11g) carries the slow 802.11b
// rates that hog airtime; 5 GHz (11a) starts at 6.
const RATE_OPTS = {
  '2.4': ['1', '2', '5.5', '6', '9', '11', '12', '18', '24', '36', '48', '54'],
  5: ['6', '9', '12', '18', '24', '36', '48', '54'],
}

// Security suites the guided form offers. `open` and `owe` carry no key; WPA2-PSK and WPA3-SAE take a
// passphrase; the enterprise 802.1X suites bind a RADIUS server (IP + shared secret) instead.
//
// OWE and WPA3-Enterprise 192-bit were confirmed on an AP630 and AP410C-1 (HiveOS 10.6r6); the rest on an
// AP230 (10.6r1a), which does not offer OWE. Offering them everywhere and letting an older AP refuse the line
// beats hiding a capability the operator's newer hardware has.
const SUITES = [
  { value: 'wpa2-aes-psk', label: 'WPA2-PSK (AES)' },
  { value: 'wpa3-sae', label: 'WPA3-SAE' },
  { value: 'wpa2-aes-8021x', label: 'WPA2-Enterprise (802.1X)' },
  { value: 'wpa3-aes-8021x-std', label: 'WPA3-Enterprise (802.1X)' },
  { value: 'wpa3-aes-8021x-suite-b-192', label: 'WPA3-Enterprise 192-bit (Suite B)' },
  { value: 'owe', label: 'Enhanced Open / OWE (encrypted, no password)' },
  { value: 'open', label: 'Open (no auth, unencrypted)' },
]
const ENTERPRISE_SUITES = new Set(['wpa2-aes-8021x', 'wpa3-aes-8021x-std', 'wpa3-aes-8021x-suite-b-192'])
// Keyless: asks the user for nothing. OWE still encrypts — it negotiates a per-client key instead of sharing
// one — so it is a drop-in upgrade for a guest SSID rather than a different flow.
const KEYLESS_SUITES = new Set(['open', 'owe'])
const isEnterprise = (suite) => ENTERPRISE_SUITES.has(suite)
const isKeyed = (suite) => !KEYLESS_SUITES.has(suite) && !isEnterprise(suite)

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

function Info({ label, value }) {
  return (
    <div>
      <span className="text-xs text-muted-foreground">{label}: </span>
      <span className="font-mono text-xs">{value}</span>
    </div>
  )
}

/** Edit an existing SSID: re-apply its passphrase/VLAN (HiveOS overwrites by name). The current passphrase is
 *  masked in the config, so editing means setting a new one. The SSID's existing security suite is preserved so
 *  an edit never silently downgrades (e.g. WPA3-SAE back to WPA2-PSK); an open SSID has no passphrase to set. */
function SsidEditRow({ ssid, onUpdate, onRemove, busy }) {
  const [psk, setPsk] = useState('')
  const [vlan, setVlan] = useState(ssid.vlan != null ? String(ssid.vlan) : '')
  const security = ssid.security || 'wpa2-aes-psk'
  const keyed = isKeyed(security)
  // Enterprise SSIDs carry RADIUS settings that the inline editor does not collect, so editing them in place
  // would drop the server binding. Offer only removal; the operator re-creates to change RADIUS.
  if (isEnterprise(security)) {
    return (
      <div className="space-y-2">
        <p className="text-xs text-muted-foreground">
          802.1X SSID — edit its RADIUS settings by removing and re-adding it.
        </p>
        <MriButton size="sm" variant="destructive" disabled={busy} onClick={onRemove}>
          Remove SSID
        </MriButton>
      </div>
    )
  }
  return (
    <div className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-2">
        {keyed && <Field label="New passphrase" value={psk} onChange={setPsk} placeholder="leave blank to keep" />}
        <Field label="VLAN" value={vlan} onChange={setVlan} placeholder="—" />
      </div>
      <div className="flex gap-2">
        <MriButton
          size="sm"
          disabled={busy || (keyed && !psk)}
          onClick={() =>
            onUpdate({
              name: ssid.name,
              psk: keyed ? psk : '',
              vlan: vlan ? Number(vlan) : null,
              remove: false,
              security,
            })
          }
        >
          Update
        </MriButton>
        <MriButton size="sm" variant="destructive" disabled={busy} onClick={onRemove}>
          Remove SSID
        </MriButton>
      </div>
    </div>
  )
}

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'

/**
 * Minimum data rate (airtime) for an SSID: drop the slow basic rates so 802.11b clients can't hog the channel.
 * Picks an SSID + band + minimum rate and applies a `11g-rate-set` / `11a-rate-set` line (confirmed live on an
 * AP230). The lowest kept rate becomes the only basic (mandatory) rate; everything below it is removed from the
 * air. Lives outside the per-SSID accordion so it is always reachable.
 */
function MinRateBlock({ ssids, onApply, busy }) {
  const [name, setName] = useState(ssids[0]?.name || '')
  const [band, setBand] = useState('2.4')
  const [minRate, setMinRate] = useState('')

  const changeBand = (b) => {
    setBand(b)
    if (!RATE_OPTS[b].includes(minRate)) setMinRate('')
  }
  const apply = () => {
    const commands = minRateCommands(name, { band, minRate })
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="text-xs font-medium text-muted-foreground">Minimum data rate (airtime)</span>
      <p className="text-xs text-muted-foreground">
        Drops every rate below the minimum from the air — slow 802.11b clients (1/2/5.5/11 Mbps) can no longer
        hold the channel. The lowest kept rate becomes the basic (mandatory) rate.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1" htmlFor="mr-ssid">
          <span className="text-xs text-muted-foreground">SSID</span>
          <select id="mr-ssid" className={SELECT_CLASS} value={name} onChange={(e) => setName(e.target.value)}>
            {ssids.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1" htmlFor="mr-band">
          <span className="text-xs text-muted-foreground">Band</span>
          <select id="mr-band" className={SELECT_CLASS} value={band} onChange={(e) => changeBand(e.target.value)}>
            <option value="2.4">2.4 GHz (11g)</option>
            <option value="5">5 GHz (11a)</option>
          </select>
        </label>
        <label className="flex flex-col gap-1" htmlFor="mr-rate">
          <span className="text-xs text-muted-foreground">Minimum rate (Mbps)</span>
          <select id="mr-rate" className={SELECT_CLASS} value={minRate} onChange={(e) => setMinRate(e.target.value)}>
            <option value="">(keep)</option>
            {RATE_OPTS[band].map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </label>
      </div>
      <MriButton size="sm" disabled={busy || !name || !minRate} onClick={apply}>
        Apply minimum rate
      </MriButton>
    </div>
  )
}

// Tri-state options for the hardening toggles: leave unchanged, turn on, or turn off.
const TOGGLE_OPTS = [
  { value: '', label: '(keep)' },
  { value: 'enable', label: 'Enable' },
  { value: 'disable', label: 'Disable' },
]

function ToggleSelect({ id, label, value, onChange, hint }) {
  return (
    <label className="flex flex-col gap-1" htmlFor={id}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(e) => onChange(e.target.value)}>
        {TOGGLE_OPTS.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {hint && <span className="text-[10px] text-muted-foreground">{hint}</span>}
    </label>
  )
}

/**
 * Per-SSID hardening / tuning (hide-ssid, client cap, client isolation, DTIM, schedule, 802.11k/v). Pure
 * builder confirmed live on an AP230; dispatched through apply-config like the minimum-data-rate block. Picks an
 * SSID, then only the changed fields emit a line. Client isolation is surfaced as its own switch (the CLI models
 * it as the negation of inter-station-traffic) so the operator does not have to reason about the inversion.
 */
function HardeningBlock({ ssids, onApply, busy }) {
  const empty = {
    hideSsid: '', maxClient: '', clientIsolation: '', dtimPeriod: '', schedule: '',
    rrm: '', wnm: '', ft: '', ftMobilityDomainId: '',
  }
  const [name, setName] = useState(ssids[0]?.name || '')
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = ssidHardeningCommands(name, {
      ...form,
      maxClient: form.maxClient ? Number(form.maxClient) : undefined,
      dtimPeriod: form.dtimPeriod ? Number(form.dtimPeriod) : undefined,
    })
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <ShieldCheck className="h-3.5 w-3.5" /> Hardening &amp; tuning
      </span>
      <p className="text-xs text-muted-foreground">
        Per-SSID controls. Only the fields you change are applied. Client isolation blocks traffic between clients
        on the SSID (guest networks); 802.11k/v/r help clients roam. Give every AP a client should roam between
        the same mobility domain id, or 802.11r cannot hand the client over without a full reauth.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1" htmlFor="hd-ssid">
          <span className="text-xs text-muted-foreground">Apply to SSID</span>
          <select id="hd-ssid" className={SELECT_CLASS} value={name} onChange={(e) => setName(e.target.value)}>
            {ssids.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <ToggleSelect id="hd-hide" label="Hide SSID" value={form.hideSsid} onChange={set('hideSsid')} />
        <ToggleSelect id="hd-isolation" label="Client isolation" value={form.clientIsolation} onChange={set('clientIsolation')} />
        <Field label="Max clients (1-255)" value={form.maxClient} onChange={set('maxClient')} placeholder="—" />
        <Field label="DTIM period (1-255)" value={form.dtimPeriod} onChange={set('dtimPeriod')} placeholder="—" />
        <Field label="Schedule name" value={form.schedule} onChange={set('schedule')} placeholder="—" />
        <ToggleSelect id="hd-rrm" label="802.11k (RRM)" value={form.rrm} onChange={set('rrm')} />
        <ToggleSelect id="hd-wnm" label="802.11v (WNM)" value={form.wnm} onChange={set('wnm')} />
        <ToggleSelect id="hd-ft" label="802.11r (fast roaming)" value={form.ft} onChange={set('ft')} />
        <Field
          label="Mobility domain id"
          value={form.ftMobilityDomainId}
          onChange={set('ftMobilityDomainId')}
          placeholder="—"
        />
      </div>
      <MriButton size="sm" disabled={busy || !name} onClick={apply}>
        Apply hardening
      </MriButton>
    </div>
  )
}

/**
 * Private PSK (PPSK). Configures the self-registration model: enable PPSK mode on an SSID's security object, point
 * it at the HiveAP that serves PPSK (its mgt0 IP), and host the self-registration portal so users enrol and the AP
 * issues the per-user key locally (optionally authenticating registrants against RADIUS). HiveKeeper does NOT mint
 * individual keys — HiveOS has no running-config grammar for that; keys come from user self-registration.
 */
function PpskBlock({ ssids, onApply, busy }) {
  const empty = {
    enable: '',
    externalServer: '',
    defaultPskDisabled: '',
    ppskServer: '',
    webServer: '',
    webHttps: '',
    webDirectory: '',
    authUser: '',
    userGroup: '',
  }
  const [name, setName] = useState(ssids[0]?.name || '')
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = ppskCommands(name, form)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <KeyRound className="h-3.5 w-3.5" /> Private PSK (PPSK)
      </span>
      <p className="text-xs text-muted-foreground">
        Per-user PSK with self-registration: a HiveAP serves PPSK and hosts the enrolment portal, so users register
        and the AP issues the key locally. HiveKeeper configures this model — it does not mint individual keys (those
        come from user self-registration or an external server).
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1" htmlFor="ppsk-ssid">
          <span className="text-xs text-muted-foreground">Apply to SSID</span>
          <select id="ppsk-ssid" className={SELECT_CLASS} value={name} onChange={(e) => setName(e.target.value)}>
            {ssids.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <ToggleSelect id="ppsk-enable" label="PPSK mode" value={form.enable} onChange={set('enable')} />
        <ToggleSelect
          id="ppsk-default-off"
          label="Disable default PSK"
          value={form.defaultPskDisabled}
          onChange={set('defaultPskDisabled')}
        />
        <Field
          label="PPSK server IP (mgt0)"
          value={form.ppskServer}
          onChange={set('ppskServer')}
          placeholder="this AP's IP"
        />
        <ToggleSelect id="ppsk-ext" label="External server" value={form.externalServer} onChange={set('externalServer')} />
        <ToggleSelect id="ppsk-web" label="Registration portal" value={form.webServer} onChange={set('webServer')} />
        <ToggleSelect id="ppsk-https" label="Portal HTTPS" value={form.webHttps} onChange={set('webHttps')} />
        <Field label="Portal web directory" value={form.webDirectory} onChange={set('webDirectory')} placeholder="—" />
        <ToggleSelect id="ppsk-authuser" label="Auth registrants (RADIUS)" value={form.authUser} onChange={set('authUser')} />
        <Field label="User-group" value={form.userGroup} onChange={set('userGroup')} placeholder="staff" />
      </div>
      <MriButton size="sm" disabled={busy || !name} onClick={apply}>
        Apply PPSK
      </MriButton>
    </div>
  )
}

// radius-auth method options (confirmed live on the AP230). A bare `radius-auth` enables it with the default PAP
// method; the explicit tokens pick CHAP / MS-CHAP-v2. 'disable' emits the `no` form.
const PPSK_RADIUS_AUTH_OPTS = [
  { value: '', label: 'Leave unchanged' },
  { value: 'pap', label: 'PAP (default)' },
  { value: 'chap', label: 'CHAP' },
  { value: 'ms-chap-v2', label: 'MS-CHAP-v2' },
  { value: 'disable', label: 'Disable' },
]

/**
 * PPSK via RADIUS — the AP→RADIUS wiring ("Caminho B"). Points the local HiveAP's PPSK server at an external
 * RADIUS backend (device-wide) and forwards a chosen security object's private-PSK auth to it. This wires the AP;
 * minting per-user keys needs the RADIUS runtime + a key store, a future phase (docs/ppsk-radius-design.md).
 * Grammar confirmed live on an AP230; the shared secret is masked server-side and encrypted at rest in jobs.
 */
function PpskRadiusBlock({ ssids, onApply, busy }) {
  const empty = { radiusServer: '', sharedSecret: '', authPort: '', autoSaveInterval: '', radiusAuth: '' }
  const [name, setName] = useState(ssids[0]?.name || '')
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = ppskRadiusCommands(name, form)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <KeyRound className="h-3.5 w-3.5" /> PPSK via RADIUS
      </span>
      <p className="text-xs text-muted-foreground">
        Point the AP&apos;s local PPSK server at an external RADIUS backend and forward a security object&apos;s
        private-PSK authentication to it. This wires the AP only — minting per-user keys needs the RADIUS runtime
        (a future phase). The RADIUS server setting is device-wide.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1" htmlFor="ppsk-radius-ssid">
          <span className="text-xs text-muted-foreground">Forward SSID</span>
          <select
            id="ppsk-radius-ssid"
            className={SELECT_CLASS}
            value={name}
            onChange={(e) => setName(e.target.value)}
          >
            {ssids.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <Field
          label="RADIUS server (IP/host)"
          value={form.radiusServer}
          onChange={set('radiusServer')}
          placeholder="10.0.0.5"
        />
        <Field
          label="Shared secret"
          value={form.sharedSecret}
          onChange={set('sharedSecret')}
          placeholder="—"
        />
        <Field label="Auth port" value={form.authPort} onChange={set('authPort')} placeholder="1812" />
        <Field
          label="Auto-save interval (s)"
          value={form.autoSaveInterval}
          onChange={set('autoSaveInterval')}
          placeholder="60–3600"
        />
        <label className="flex flex-col gap-1" htmlFor="ppsk-radius-auth">
          <span className="text-xs text-muted-foreground">Forward auth method</span>
          <select
            id="ppsk-radius-auth"
            className={SELECT_CLASS}
            value={form.radiusAuth}
            onChange={(e) => set('radiusAuth')(e.target.value)}
          >
            {PPSK_RADIUS_AUTH_OPTS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <MriButton size="sm" disabled={busy} onClick={apply}>
        Apply PPSK RADIUS
      </MriButton>
    </div>
  )
}

/**
 * Per-SSID QoS: bind a QoS classifier profile (classify incoming traffic) and a marker profile (mark outgoing),
 * and toggle WMM (Wi-Fi Multimedia — on by default; needed for voice/video priority). Grammar confirmed live on
 * an AP230: `ssid <n> qos-classifier <profile>`, `ssid <n> qos-marker <profile>`, `[no] ssid <n> wmm`. The
 * classifier/marker reference `qos classifier-profile` / `qos marker-profile` objects by name.
 */
function QosBlock({ ssids, onApply, busy }) {
  const empty = { qosClassifier: '', qosMarker: '', wmm: '' }
  const [name, setName] = useState(ssids[0]?.name || '')
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = ssidQosCommands(name, form)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Gauge className="h-3.5 w-3.5" /> QoS (voice / video priority)
      </span>
      <p className="text-xs text-muted-foreground">
        Bind QoS classifier / marker profiles to prioritise traffic, and toggle WMM. Profiles are referenced by
        name (define them on the AP); WMM is on by default and is required for voice/video QoS.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1" htmlFor="qos-ssid">
          <span className="text-xs text-muted-foreground">Apply to SSID</span>
          <select id="qos-ssid" className={SELECT_CLASS} value={name} onChange={(e) => setName(e.target.value)}>
            {ssids.map((s) => (
              <option key={s.name} value={s.name}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
        <Field label="QoS classifier profile" value={form.qosClassifier} onChange={set('qosClassifier')} placeholder="—" />
        <Field label="QoS marker profile" value={form.qosMarker} onChange={set('qosMarker')} placeholder="—" />
        <ToggleSelect id="qos-wmm" label="WMM" value={form.wmm} onChange={set('wmm')} />
      </div>
      <MriButton size="sm" disabled={busy || !name} onClick={apply}>
        Apply QoS
      </MriButton>
    </div>
  )
}

/**
 * Wi-Fi management for a device: list the SSIDs read from the AP's running-config (each in an accordion with
 * its security/VLAN/radios + edit + remove), and add new ones. SSID writes use the typed configure-ssid path
 * (the driver generates the CLI); the list re-reads after each change. onApply (apply-config) drives the raw
 * lines that have no typed command yet (the minimum-data-rate / rate-set tuning).
 */
export function WifiSection({ device, loadSsids, configureSsid, onApply, busy }) {
  const [ssids, setSsids] = useState(null)
  const emptyAdd = { name: '', psk: '', vlan: '', security: 'wpa2-aes-psk', radiusServer: '', radiusSecret: '' }
  const [add, setAdd] = useState(emptyAdd)

  const refresh = useCallback(() => {
    setSsids(null)
    return loadSsids(device)
      .then((list) => setSsids(Array.isArray(list) ? list : []))
      .catch(() => setSsids([]))
  }, [loadSsids, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  const doConfigure = (body) => Promise.resolve(configureSsid(device, body)).then(refresh)
  const addKeyed = isKeyed(add.security)
  const addEnterprise = isEnterprise(add.security)
  const addReady =
    add.name && (!addKeyed || add.psk) && (!addEnterprise || (add.radiusServer && add.radiusSecret))
  const addSsid = () => {
    if (!addReady) return
    const body = {
      name: add.name,
      psk: addKeyed ? add.psk : '',
      vlan: add.vlan ? Number(add.vlan) : null,
      remove: false,
      security: add.security,
    }
    if (addEnterprise) {
      body.radiusServer = add.radiusServer
      body.radiusSecret = add.radiusSecret
    }
    doConfigure(body)
    setAdd(emptyAdd)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <MriSectionHeader icon={Wifi} title="Wi-Fi (SSIDs)" />
        <MriButton size="sm" variant="ghost" disabled={busy} onClick={refresh}>
          Refresh
        </MriButton>
      </div>

      {ssids === null ? (
        <p className="text-sm text-muted-foreground">Loading SSIDs from the AP…</p>
      ) : ssids.length === 0 ? (
        <p className="text-sm text-muted-foreground">No SSIDs configured.</p>
      ) : (
        <MriAccordion type="single" collapsible className="w-full">
          {ssids.map((s) => (
            <MriAccordionItem key={s.name} value={s.name}>
              <MriAccordionTrigger>
                <span className="flex items-center gap-2">
                  {s.security ? (
                    <span title={`Secured (${s.security})`} className="text-primary">
                      <Lock className="h-3.5 w-3.5" />
                    </span>
                  ) : (
                    <span title="Open (no authentication)" className="text-muted-foreground">
                      <LockOpen className="h-3.5 w-3.5" />
                    </span>
                  )}
                  <span className="font-medium">{s.name}</span>
                  {s.vlan != null && <MriStatusBadge label={`VLAN ${s.vlan}`} variant="outline" size="xs" />}
                  {s.radios?.length > 0 && (
                    <span className="text-xs text-muted-foreground">{s.radios.join(', ')}</span>
                  )}
                </span>
              </MriAccordionTrigger>
              <MriAccordionContent>
                <div className="space-y-3 pb-2">
                  <div className="flex flex-wrap gap-4">
                    <Info label="Security" value={s.security || '—'} />
                    <Info label="VLAN" value={s.vlan != null ? String(s.vlan) : '—'} />
                    <Info label="Radios" value={s.radios?.join(', ') || '—'} />
                  </div>
                  <SsidEditRow
                    ssid={s}
                    busy={busy}
                    onUpdate={(body) => doConfigure(body)}
                    onRemove={() => doConfigure({ name: s.name, remove: true })}
                  />
                </div>
              </MriAccordionContent>
            </MriAccordionItem>
          ))}
        </MriAccordion>
      )}

      <div className="space-y-2 rounded-md border border-border p-3">
        <span className="text-xs font-medium text-muted-foreground">Add SSID</span>
        <div className="grid gap-2 sm:grid-cols-3">
          <Field label="Name" value={add.name} onChange={(v) => setAdd({ ...add, name: v })} placeholder="HK-DEMO" />
          <label className="flex flex-col gap-1" htmlFor="add-security">
            <span className="text-xs text-muted-foreground">Security</span>
            <select
              id="add-security"
              className={SELECT_CLASS}
              value={add.security}
              onChange={(e) => setAdd({ ...add, security: e.target.value })}
            >
              {SUITES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          {addKeyed && (
            <Field
              label="Passphrase"
              value={add.psk}
              onChange={(v) => setAdd({ ...add, psk: v })}
              placeholder="min 8 chars"
            />
          )}
          {addEnterprise && (
            <>
              <Field
                label="RADIUS server"
                value={add.radiusServer}
                onChange={(v) => setAdd({ ...add, radiusServer: v })}
                placeholder="10.0.0.5"
              />
              <Field
                label="RADIUS shared secret"
                value={add.radiusSecret}
                onChange={(v) => setAdd({ ...add, radiusSecret: v })}
                placeholder="shared secret"
              />
            </>
          )}
          {!addKeyed && !addEnterprise && (
            <div className="flex items-end text-xs text-muted-foreground">No passphrase (open network)</div>
          )}
          <Field label="VLAN (optional)" value={add.vlan} onChange={(v) => setAdd({ ...add, vlan: v })} placeholder="7" />
        </div>
        <MriButton size="sm" disabled={busy || !addReady} onClick={addSsid}>
          Add SSID
        </MriButton>
      </div>

      {onApply && ssids?.length > 0 && (
        <>
          <MinRateBlock ssids={ssids} busy={busy} onApply={(body) => onApply(device, body)} />
          <HardeningBlock ssids={ssids} busy={busy} onApply={(body) => onApply(device, body)} />
          <QosBlock ssids={ssids} busy={busy} onApply={(body) => onApply(device, body)} />
          <PpskBlock ssids={ssids} busy={busy} onApply={(body) => onApply(device, body)} />
          <PpskRadiusBlock ssids={ssids} busy={busy} onApply={(body) => onApply(device, body)} />
        </>
      )}
    </div>
  )
}
