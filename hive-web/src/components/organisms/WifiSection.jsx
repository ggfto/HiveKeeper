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
import { Wifi, Lock, LockOpen } from 'lucide-react'
import { minRateCommands } from '../../lib/hiveosCli'

// Data-rate ladders (Mbps) per band, for the minimum-data-rate picker. 2.4 GHz (11g) carries the slow 802.11b
// rates that hog airtime; 5 GHz (11a) starts at 6.
const RATE_OPTS = {
  '2.4': ['1', '2', '5.5', '6', '9', '11', '12', '18', '24', '36', '48', '54'],
  5: ['6', '9', '12', '18', '24', '36', '48', '54'],
}

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
 *  masked in the config, so editing means setting a new one. */
function SsidEditRow({ ssid, onUpdate, onRemove, busy }) {
  const [psk, setPsk] = useState('')
  const [vlan, setVlan] = useState(ssid.vlan != null ? String(ssid.vlan) : '')
  return (
    <div className="space-y-2">
      <div className="grid gap-2 sm:grid-cols-2">
        <Field label="New passphrase" value={psk} onChange={setPsk} placeholder="leave blank to keep" />
        <Field label="VLAN" value={vlan} onChange={setVlan} placeholder="—" />
      </div>
      <div className="flex gap-2">
        <MriButton
          size="sm"
          disabled={busy || !psk}
          onClick={() => onUpdate({ name: ssid.name, psk, vlan: vlan ? Number(vlan) : null, remove: false })}
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

/**
 * Wi-Fi management for a device: list the SSIDs read from the AP's running-config (each in an accordion with
 * its security/VLAN/radios + edit + remove), and add new ones. SSID writes use the typed configure-ssid path
 * (the driver generates the CLI); the list re-reads after each change. onApply (apply-config) drives the raw
 * lines that have no typed command yet (the minimum-data-rate / rate-set tuning).
 */
export function WifiSection({ device, loadSsids, configureSsid, onApply, busy }) {
  const [ssids, setSsids] = useState(null)
  const [add, setAdd] = useState({ name: '', psk: '', vlan: '' })

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
  const addSsid = () => {
    if (!add.name || !add.psk) return
    doConfigure({ name: add.name, psk: add.psk, vlan: add.vlan ? Number(add.vlan) : null, remove: false })
    setAdd({ name: '', psk: '', vlan: '' })
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
        <span className="text-xs font-medium text-muted-foreground">Add SSID (WPA2-PSK)</span>
        <div className="grid gap-2 sm:grid-cols-3">
          <Field label="Name" value={add.name} onChange={(v) => setAdd({ ...add, name: v })} placeholder="HK-DEMO" />
          <Field
            label="Passphrase"
            value={add.psk}
            onChange={(v) => setAdd({ ...add, psk: v })}
            placeholder="min 8 chars"
          />
          <Field label="VLAN (optional)" value={add.vlan} onChange={(v) => setAdd({ ...add, vlan: v })} placeholder="7" />
        </div>
        <MriButton size="sm" disabled={busy || !add.name || !add.psk} onClick={addSsid}>
          Add SSID
        </MriButton>
      </div>

      {onApply && ssids?.length > 0 && (
        <MinRateBlock ssids={ssids} busy={busy} onApply={(body) => onApply(device, body)} />
      )}
    </div>
  )
}
