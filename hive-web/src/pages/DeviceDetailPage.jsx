import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { Boxes, Wifi, Network, Radio, Tag, Globe, Terminal, Power, ArrowLeft, Router } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { ConfigNav } from '../components/molecules/ConfigNav'
import { WifiSection } from '../components/organisms/WifiSection'
import { HiveForm } from '../components/organisms/HiveForm'
import { RadioForm } from '../components/organisms/RadioForm'
import { IdentityForm } from '../components/organisms/IdentityForm'
import { NetworkForm } from '../components/organisms/NetworkForm'
import { ClientModeForm } from '../components/organisms/ClientModeForm'
import { AdvancedConfigForm } from '../components/organisms/AdvancedConfigForm'
import { PowerForm } from '../components/organisms/PowerForm'
import { DeviceOverviewForm } from '../components/organisms/DeviceOverviewForm'
import { SchemaConfigForm } from '../components/organisms/SchemaConfigForm'
import { SCHEMA_SECTIONS } from '../lib/configSchema'
import { parseSsids } from '../lib/hiveosParse'
import { groupNamesFor, siteName } from '../lib/fleet'

const SECTIONS = [
  { id: 'overview', label: 'Overview', icon: Boxes },
  { id: 'wifi', label: 'Wi-Fi', icon: Wifi },
  { id: 'mesh', label: 'Mesh', icon: Network },
  { id: 'radio', label: 'Radio', icon: Radio },
  { id: 'clientmode', label: 'Client mode', icon: Router },
  { id: 'identity', label: 'Identity', icon: Tag },
  { id: 'network', label: 'Network', icon: Globe },
  // declarative field -> CLI categories (DNS, NTP, SNMP, Syslog, ...)
  ...SCHEMA_SECTIONS.map((s) => ({ id: s.id, label: s.label, icon: s.icon })),
  { id: 'advanced', label: 'Advanced', icon: Terminal },
  { id: 'power', label: 'Power', icon: Power },
]

function Info({ label, value, mono }) {
  return (
    <div>
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
  const [device, setDevice] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const [groups, setGroups] = useState([])
  const [sites, setSites] = useState([])
  const [section, setSection] = useState('overview')
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const [configResult, setConfigResult] = useState(null)

  const load = useCallback(async () => {
    const [list, g, s] = await Promise.all([
      gateway.devices().catch(() => null),
      gateway.groups().catch(() => []),
      gateway.sites().catch(() => []),
    ])
    const d = Array.isArray(list) ? list.find((x) => x.deviceId === deviceId) : null
    setDevice(d || null)
    setNotFound(!d)
    setGroups(Array.isArray(g) ? g : [])
    setSites(Array.isArray(s) ? s : [])
  }, [gateway, deviceId])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const run = async (label, fn) => {
    setBusy(true)
    setStatus(`${label}…`)
    try {
      await fn()
      setStatus(`${label}: done.`)
    } catch (e) {
      setStatus(`${label}: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }
  const apply = (d, op, body) => gateway.agentOp(d.agentId, op, { host: d.mgmtIp, port: 22, ...body })
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
  const onConfigureHive = (d, body) => run('Hive', () => apply(d, 'configure-hive', body))
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

  const schema = SCHEMA_SECTIONS.find((s) => s.id === section)

  return (
    <div className="space-y-5">
      <MriButton size="sm" variant="ghost" onClick={() => navigate('/devices')}>
        <ArrowLeft className="h-4 w-4" /> Devices
      </MriButton>
      <MriPageHeader title={device.label || device.serial} icon={Boxes} countLabel={device.model} />

      <div className="grid gap-3 rounded-lg border border-border bg-card p-4 sm:grid-cols-2 lg:grid-cols-4">
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
              busy={busy}
            />
          )}
          {section === 'wifi' && (
            <WifiSection device={device} loadSsids={loadSsids} configureSsid={onConfigureSsid} busy={busy} />
          )}
          {section === 'mesh' && <HiveForm device={device} onConfigureHive={onConfigureHive} busy={busy} />}
          {section === 'radio' && <RadioForm device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'clientmode' && <ClientModeForm device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'identity' && <IdentityForm device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'network' && <NetworkForm device={device} onApply={onApplyConfig} busy={busy} />}
          {schema && <SchemaConfigForm section={schema} device={device} onApply={onApplyConfig} busy={busy} />}
          {section === 'advanced' && (
            <AdvancedConfigForm device={device} onApply={onApplyConfig} result={configResult} busy={busy} />
          )}
          {section === 'power' && <PowerForm device={device} onApply={onApplyConfig} onReboot={onReboot} busy={busy} />}
        </div>
      </div>

      {status && <p className="text-sm text-muted-foreground">{status}</p>}
    </div>
  )
}
