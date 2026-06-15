import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MriPageHeader, MriButton } from '@mriqbox/ui-kit'
import { Boxes } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { DevicesTable } from '../components/organisms/DevicesTable'

/** The registered fleet: list devices, run per-device inventory/backup, tag into groups, and open a device's
 *  management page to configure it. */
export function DevicesPage() {
  const { gateway, activeOrg } = useAuth()
  const navigate = useNavigate()
  const [devices, setDevices] = useState(null)
  const [groups, setGroups] = useState([])
  const [sites, setSites] = useState([])
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    const d = await gateway.devices().catch((e) => (e.status === 403 ? 'forbidden' : null))
    const g = await gateway.groups().catch(() => [])
    const s = await gateway.sites().catch(() => [])
    setDevices(d)
    setGroups(Array.isArray(g) ? g : [])
    setSites(Array.isArray(s) ? s : [])
  }, [gateway])

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

  const onInventory = (d) => run(`Inventory ${d.mgmtIp}`, () => gateway.inventory(d.agentId, d.mgmtIp))
  const onBackup = (d) => run(`Backup ${d.mgmtIp}`, () => gateway.backup(d.agentId, d.mgmtIp))
  const onTag = (deviceId, groupId) =>
    run('Tag device', async () => {
      await gateway.tagDevice(deviceId, groupId)
      await load()
    })
  const onConfigure = (d) => navigate(`/devices/${d.deviceId}`)

  return (
    <div className="space-y-4">
      <MriPageHeader
        title="Devices"
        icon={Boxes}
        count={Array.isArray(devices) ? devices.length : undefined}
        countLabel="registered"
      >
        <MriButton size="sm" variant="outline" disabled={busy} onClick={load}>
          Refresh
        </MriButton>
      </MriPageHeader>
      {devices === 'forbidden' ? (
        <p className="text-sm text-muted-foreground">
          The fleet list needs an organization-level viewer or admin role.
        </p>
      ) : (
        <DevicesTable
          devices={Array.isArray(devices) ? devices : []}
          groups={groups}
          sites={sites}
          onInventory={onInventory}
          onBackup={onBackup}
          onTag={onTag}
          onConfigure={onConfigure}
          busy={busy}
        />
      )}
      {status && <p className="text-sm text-muted-foreground">{status}</p>}
    </div>
  )
}
