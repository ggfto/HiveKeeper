import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton, MriStatusBadge } from '@mriqbox/ui-kit'
import { Boxes, Wifi, Network, Radio, Globe, Terminal, Power, ArrowLeft, Router, Activity, DoorOpen } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { ConfigNav } from '../components/molecules/ConfigNav'
import { HelpPopover } from '../components/molecules/HelpPopover'
import { WifiSection } from '../components/organisms/WifiSection'
import { MeshSection } from '../components/organisms/MeshSection'
import { RadioForm } from '../components/organisms/RadioForm'
import { NetworkSection } from '../components/organisms/NetworkSection'
import { ClientModeForm } from '../components/organisms/ClientModeForm'
import { AdvancedConfigForm } from '../components/organisms/AdvancedConfigForm'
import { PowerForm } from '../components/organisms/PowerForm'
import { LedForm } from '../components/organisms/LedForm'
import { DeviceOverviewForm } from '../components/organisms/DeviceOverviewForm'
import { CaptivePortalForm } from '../components/organisms/CaptivePortalForm'
import { MonitoringSection } from '../components/organisms/MonitoringSection'
import { MONITORING_SECTIONS } from '../lib/configSchema'
import { parseSsids, parseHives, parseCapwap, parseAcsp, parseLog } from '../lib/hiveosParse'
import { meshCommands } from '../lib/hiveosCli'
import { groupNamesFor, siteName } from '../lib/fleet'

const SECTIONS = [
  { id: 'overview', label: 'Overview', icon: Boxes },
  { id: 'wifi', label: 'Wi-Fi', icon: Wifi },
  { id: 'captiveportal', label: 'Captive portal', icon: DoorOpen },
  { id: 'mesh', label: 'Mesh', icon: Network },
  { id: 'radio', label: 'Radio', icon: Radio },
  { id: 'clientmode', label: 'Client mode', icon: Router },
  { id: 'network', label: 'Network', icon: Globe },
  { id: 'monitoring', label: 'Monitoring', icon: Activity },
  { id: 'advanced', label: 'Advanced', icon: Terminal },
  { id: 'power', label: 'Power', icon: Power },
]

function Info({ label, value, mono }) {
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={mono ? 'font-mono text-xs' : 'text-sm'}>{value || '—'}</div>
    </div>
  )
}

/** A device's management page: identity/inventory header + a vertical nav of config categories. Replaces the
 *  cramped modal — this scales to the AP's large config surface. */
export function DeviceDetailPage() {
  const { deviceId } = useParams()
  const navigate = useNavigate()
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const [device, setDevice] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const [groups, setGroups] = useState([])
  const [sites, setSites] = useState([])
  const [agents, setAgents] = useState([])
  const [section, setSection] = useState('overview')
  const [busy, setBusy] = useState(false)
  const [configResult, setConfigResult] = useState(null)

  const load = useCallback(async () => {
    const [list, g, s, a] = await Promise.all([
      gateway.devices().catch(() => null),
      gateway.groups().catch(() => []),
      gateway.sites().catch(() => []),
      gateway.agents().catch(() => []),
    ])
    const d = Array.isArray(list) ? list.find((x) => x.deviceId === deviceId) : null
    setDevice(d || null)
    setNotFound(!d)
    setGroups(Array.isArray(g) ? g : [])
    setSites(Array.isArray(s) ? s : [])
    setAgents(Array.isArray(a) ? a : [])
  }, [gateway, deviceId])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  // Run an op with a busy/status lifecycle. If fn returns a string, it becomes the success message (so reads
  // like inventory/backup can report what they found); otherwise we show a generic "done".
  const run = async (label, fn) => {
    setBusy(true)
    try {
      const msg = await fn()
      toast(typeof msg === 'string' ? msg : `${label}: done.`, 'success')
    } catch (e) {
      toast(`${label}: ${e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }
  const apply = (d, op, body) => gateway.agentOp(d.agentId, op, { host: d.mgmtIp, port: 22, ...body })

  // Read-only fleet ops, reachable from the header. Both go through the device's agent (which holds the
  // credential); inventory returns live facts, backup commits the running-config to the tenant's git store.
  const onInventory = (d) =>
    run('Inventory', async () => {
      const r = await gateway.inventory(d.agentId, d.mgmtIp)
      const dev = r?.device || {}
      const parts = [dev.model, dev.firmwareVersion, `${(dev.stations || []).length} station(s)`].filter(Boolean)
      return `Inventory: ${parts.join(' · ')}`
    })
  const onBackup = (d) =>
    run('Backup', async () => {
      const r = await gateway.backup(d.agentId, d.mgmtIp)
      const sha = r?.ref?.commitId ? r.ref.commitId.slice(0, 8) : ''
      return `Backup: ${r?.configBytes ?? '?'} bytes${sha ? ` · ${sha}` : ''}${r?.usersIncluded ? ' · users' : ''}`
    })
  const onConfigureSsid = (d, body) => run('SSID', () => apply(d, 'configure-ssid', body))
  // Read the SSIDs straight from the AP's running-config (parsed UI-side) so the Wi-Fi list reflects reality.
  const loadSsids = useCallback(
    (d) =>
      gateway
        .agentOp(d.agentId, 'apply-config', {
          host: d.mgmtIp,
          port: 22,
          commands: ['show running-config'],
          save: false,
        })
        .then((r) => parseSsids((r.outputs || []).join('\n'))),
    [gateway],
  )
  // Read the hives from the AP (show hive) so the Mesh list reflects reality; apply binds the chosen interfaces.
  const loadHives = useCallback(
    (d) =>
      gateway
        .agentOp(d.agentId, 'apply-config', { host: d.mgmtIp, port: 22, commands: ['show hive'], save: false })
        .then((r) => parseHives((r.outputs || []).join('\n'))),
    [gateway],
  )
  // The live monitoring snapshot: the already-parsed inventory (clients/radios/system facts) enriched with two
  // cheap reads — `show capwap client` (is the AP standalone or still phoning home) and `show acsp` (per-radio
  // channel/width/Tx power, which inventory leaves null). All through the agent. Memoized so the auto-load fires
  // once, not on every parent render.
  const loadStatus = useCallback(
    async (d) => {
      const [inv, outputs] = await Promise.all([
        gateway.inventory(d.agentId, d.mgmtIp).then((r) => r.device),
        gateway
          .agentOp(d.agentId, 'apply-config', {
            host: d.mgmtIp,
            port: 22,
            commands: ['show capwap client', 'show acsp'],
            save: false,
          })
          .then((r) => r.outputs || [])
          .catch(() => []),
      ])
      const rf = new Map(parseAcsp(outputs[1]).map((r) => [r.name.toLowerCase(), r]))
      const radios = (inv.radios || []).map((r) => {
        const a = rf.get((r.name || '').toLowerCase())
        return a ? { ...r, channel: a.channel ?? r.channel, width: a.width, txPower: a.txPower, auto: a.channelSelect } : r
      })
      return { ...inv, radios, cloud: parseCapwap(outputs[0]) }
    },
    [gateway],
  )
  // The recent on-AP log (show log buffered), pulled on demand and trimmed to the most recent entries.
  const loadLog = useCallback(
    (d) =>
      gateway
        .agentOp(d.agentId, 'apply-config', {
          host: d.mgmtIp,
          port: 22,
          commands: ['show log buffered'],
          save: false,
        })
        .then((r) => parseLog((r.outputs || [])[0] || '', 120)),
    [gateway],
  )
  const applyMesh = (d, spec) => onApplyConfig(d, { commands: meshCommands(spec), save: true })
  const onReboot = (d) => {
    if (!window.confirm(`Reboot ${d.mgmtIp}? It will be offline for ~1-2 minutes.`)) return
    run('Reboot', () => apply(d, 'reboot', {}))
  }
  const onApplyConfig = (d, { commands, save }) =>
    run(`Apply ${commands.length} CLI line(s)`, async () => {
      const r = await apply(d, 'apply-config', { commands, save })
      setConfigResult(r)
    })

  // HiveKeeper metadata (gateway, not the AP): name/site/groups.
  const onSaveDevice = (d, body) =>
    run('Save device', async () => {
      await gateway.updateDevice(d.deviceId, body)
      await load()
    })
  const onTag = (d, groupId) =>
    run('Add to group', async () => {
      await gateway.tagDevice(d.deviceId, groupId)
      await load()
    })
  const onUntag = (d, groupId) =>
    run('Remove from group', async () => {
      await gateway.untagDevice(d.deviceId, groupId)
      await load()
    })

  if (notFound) {
    return (
      <div className="space-y-3">
        <MriButton size="sm" variant="ghost" onClick={() => navigate('/devices')}>
          <ArrowLeft className="h-4 w-4" /> Devices
        </MriButton>
        <p className="text-sm text-muted-foreground">Device not found.</p>
      </div>
    )
  }
  if (!device) return <p className="text-sm text-muted-foreground">Loading…</p>

  // Live status = the device's agent is currently connected to the gateway (so we can actually reach it).
  const online = agents.includes(device.agentId)

  return (
    <div className="space-y-5">
      <MriButton size="sm" variant="ghost" onClick={() => navigate('/devices')}>
        <ArrowLeft className="h-4 w-4" /> Devices
      </MriButton>
      <MriPageHeader title={device.label || device.serial} icon={Boxes} countLabel={device.model}>
        <MriStatusBadge
          label={online ? 'online' : 'offline'}
          variant={online ? 'success' : 'outline'}
          size="sm"
        />
        <MriButton
          size="sm"
          variant="outline"
          disabled={busy || !online}
          title={online ? undefined : 'The device agent is offline'}
          onClick={() => onInventory(device)}
        >
          Inventory
        </MriButton>
        <MriButton
          size="sm"
          variant="outline"
          disabled={busy || !online}
          title={online ? undefined : 'The device agent is offline'}
          onClick={() => onBackup(device)}
        >
          Backup
        </MriButton>
        <HelpPopover
          title="Configuring this AP"
          docId="device-configuration"
          body={
            'Each section below maps to part of the AP config. Most apply by generating **HiveOS CLI** and ' +
            'sending it through the agent (the **Advanced** section exposes that raw). Writes that `save ' +
            'config` persist to flash; the live panels are read-only.'
          }
        />
      </MriPageHeader>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Info label="Serial" value={device.serial} mono />
        <Info label="Model" value={device.model} />
        <Info label="Mgmt IP" value={device.mgmtIp} mono />
        <Info label="Site" value={siteName(device.siteId, sites) || '—'} />
        <Info label="Agent" value={device.agentId || '—'} mono />
        <Info label="Groups" value={groupNamesFor(device, groups).join(', ') || '—'} />
      </div>

      <div className="flex flex-col gap-6 sm:flex-row">
        <ConfigNav sections={SECTIONS} active={section} onSelect={setSection} />
        <div className="min-w-0 flex-1">
          {section === 'overview' && (
            <DeviceOverviewForm
              device={device}
              sites={sites}
              groups={groups}
              onSave={onSaveDevice}
              onTag={onTag}
              onUntag={onUntag}
              onApply={onApplyConfig}
              busy={busy}
            />
          )}
          {section === 'wifi' && (
            <WifiSection device={device} loadSsids={loadSsids} configureSsid={onConfigureSsid} busy={busy} />
          )}
          {section === 'captiveportal' && (
            <CaptivePortalForm device={device} onApply={onApplyConfig} busy={busy} />
          )}
          {section === 'mesh' && (
            <MeshSection
              device={device}
              loadHives={loadHives}
              applyMesh={applyMesh}
              onApply={onApplyConfig}
              busy={busy}
            />
          )}
          {section === 'radio' && <RadioForm device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'clientmode' && <ClientModeForm device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'network' && <NetworkSection device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'monitoring' && (
            <MonitoringSection
              device={device}
              online={online}
              loadStatus={loadStatus}
              loadLog={loadLog}
              snmpSection={MONITORING_SECTIONS[0]}
              syslogSection={MONITORING_SECTIONS[1]}
              onApply={onApplyConfig}
              busy={busy}
            />
          )}
          {section === 'advanced' && (
            <AdvancedConfigForm device={device} onApply={onApplyConfig} result={configResult} busy={busy} />
          )}
          {section === 'power' && (
            <div className="space-y-8">
              <PowerForm device={device} onApply={onApplyConfig} onReboot={onReboot} busy={busy} />
              <LedForm device={device} onApply={onApplyConfig} busy={busy} />
            </div>
          )}
        </div>
      </div>

    </div>
  )
}
