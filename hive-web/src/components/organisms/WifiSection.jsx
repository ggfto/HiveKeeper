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

/**
 * Wi-Fi management for a device: list the SSIDs read from the AP's running-config (each in an accordion with
 * its security/VLAN/radios + edit + remove), and add new ones. SSID writes use the typed configure-ssid path
 * (the driver generates the CLI); the list re-reads after each change.
 */
export function WifiSection({ device, loadSsids, configureSsid, busy }) {
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
    </div>
  )
}
