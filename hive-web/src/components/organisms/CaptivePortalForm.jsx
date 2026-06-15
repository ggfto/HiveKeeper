import { useState } from 'react'
import { MriInput, MriTextarea, MriButton, MriSwitch, MriSectionHeader } from '@mriqbox/ui-kit'
import { DoorOpen } from 'lucide-react'
import { captivePortalCommands } from '../../lib/hiveosCli'

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

/**
 * Turn an SSID's security object into a captive web portal: serve a splash page from the AP's own web server and
 * let unauthenticated clients reach a walled garden of hosts before they log in. The security object and its
 * splash pages (web directory) drive the portal; binding it to an SSID happens in the Wi-Fi tab. Confirmed
 * HiveOS grammar lives in lib/hiveosCli.js (captivePortalCommands); applied through apply-config.
 */
export function CaptivePortalForm({ device, onApply, busy }) {
  const [securityObject, setSecurityObject] = useState('')
  const [webServer, setWebServer] = useState(true)
  const [ssl, setSsl] = useState(false)
  const [port, setPort] = useState('')
  const [webDirectory, setWebDirectory] = useState('')
  const [walled, setWalled] = useState('')

  const apply = () => {
    const walledGarden = walled
      .split(/[\n,]/)
      .map((s) => s.trim())
      .filter(Boolean)
    const commands = captivePortalCommands(securityObject.trim(), {
      webServer,
      port: port.trim(),
      ssl,
      webDirectory: webDirectory.trim(),
      walledGarden,
    })
    if (commands.length === 0) return
    onApply(device, { commands, save: true })
  }

  return (
    <div className="space-y-4">
      <MriSectionHeader icon={DoorOpen} title="Captive web portal" />
      <p className="text-xs text-muted-foreground">
        Serve a splash page from the AP&apos;s internal web server on an SSID&apos;s security object, and let
        unauthenticated clients reach a walled garden of hosts first. Bind the security object to the SSID in the
        Wi-Fi tab; the splash pages (web directory) must already be uploaded to the AP.
      </p>

      <div className="grid gap-3 sm:grid-cols-2">
        <Field
          label="Security object"
          value={securityObject}
          onChange={setSecurityObject}
          placeholder="HK-JOB (usually the SSID name)"
        />
        <Field label="Web directory" value={webDirectory} onChange={setWebDirectory} placeholder="(uploaded page set)" />
        <Field label="HTTP port" value={port} onChange={setPort} placeholder="80" />
      </div>

      <div className="flex flex-wrap gap-6">
        <label className="flex items-center gap-2 text-sm">
          <MriSwitch checked={webServer} onCheckedChange={setWebServer} aria-label="Enable internal web server" />
          Internal web server
        </label>
        <label className="flex items-center gap-2 text-sm">
          <MriSwitch checked={ssl} onCheckedChange={setSsl} aria-label="Serve the portal over HTTPS" />
          SSL (HTTPS)
        </label>
      </div>

      <label className="flex flex-col gap-1">
        <span className="text-xs text-muted-foreground">Walled garden — one IP or host per line (reachable pre-login)</span>
        <MriTextarea
          value={walled}
          onChange={(e) => setWalled(e.target.value)}
          rows={4}
          placeholder={'192.168.1.10\nupdates.example.com'}
          className="font-mono text-xs"
        />
      </label>

      <MriButton size="sm" disabled={busy || !securityObject.trim()} onClick={apply}>
        Apply captive portal
      </MriButton>
    </div>
  )
}
