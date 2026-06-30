import { useEffect, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { BellRing } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { FleetAlertsPanel } from '../components/organisms/FleetAlertsPanel'
import { FiringAlertsPanel } from '../components/organisms/FiringAlertsPanel'
import { NotificationsSection } from '../components/organisms/NotificationsSection'
import { DEFAULT_THRESHOLDS } from '../lib/alerts'

/**
 * Fleet alerts: an on-demand scan that reads each device's live inventory through its agent and evaluates it
 * against the thresholds. The threshold baseline is the single server-side value (managed under *Alert
 * delivery* and used by the background poller); the scan's slider is an in-memory what-if that re-evaluates the
 * held snapshots without re-scanning or persisting. The poller's currently-firing set is shown below.
 */
export function AlertsPage() {
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const [scan, setScan] = useState(null)
  const [scanning, setScanning] = useState(false)
  const [scannedAt, setScannedAt] = useState(null)
  const [thresholds, setThresholds] = useState(DEFAULT_THRESHOLDS)

  // Seed the threshold baseline from the server (single source of truth shared with the poller), per org.
  useEffect(() => {
    let live = true
    gateway
      .alertSettings()
      .then((s) => live && setThresholds({ maxStations: s?.maxStations ?? DEFAULT_THRESHOLDS.maxStations }))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [gateway, activeOrg])

  const onThresholds = (t) => setThresholds(t) // in-memory what-if; the persisted value lives in Alert delivery

  const onScan = async () => {
    setScanning(true)
    try {
      const [devs, agentIds] = await Promise.all([
        gateway.devices().catch(() => []),
        gateway.agents().catch(() => []),
      ])
      const list = Array.isArray(devs) ? devs : []
      const connected = new Set(Array.isArray(agentIds) ? agentIds : [])
      // Fan out one inventory read per online device; an offline device needs no read (it alerts as offline).
      const results = await Promise.all(
        list.map(async (d) => {
          const online = connected.has(d.agentId)
          if (!online) return { device: d, online, snapshot: null }
          try {
            const inv = await gateway.inventory(d.agentId, d.mgmtIp).then((r) => r.device)
            return { device: d, online, snapshot: inv }
          } catch (e) {
            return { device: d, online, error: e.message }
          }
        }),
      )
      setScan(results)
      setScannedAt(new Date())
    } catch (e) {
      toast(`Fleet scan: ${e.message}`, 'error')
    } finally {
      setScanning(false)
    }
  }

  return (
    <div className="space-y-4">
      <MriPageHeader title="Alerts" icon={BellRing} />
      <p className="text-xs text-muted-foreground">
        Scan the fleet to flag APs that breach a threshold — offline agents, APs still phoning home to the cloud,
        high client load, or radios outside best practice. This reads each AP live through its agent.
      </p>
      <FleetAlertsPanel
        scan={scan}
        scanning={scanning}
        scannedAt={scannedAt}
        onScan={onScan}
        thresholds={thresholds}
        onThresholds={onThresholds}
      />
      <FiringAlertsPanel loadFiring={() => gateway.firingAlerts().then((r) => r.alerts || [])} />
      <NotificationsSection
        loadSettings={() => gateway.alertSettings()}
        onSaveSettings={(body) => gateway.saveAlertSettings(body)}
        loadChannels={() => gateway.alertChannels().then((r) => r.channels || [])}
        onAddChannel={(body) => gateway.addAlertChannel(body)}
        onToggleChannel={(id, enabled) => gateway.setAlertChannelEnabled(id, enabled)}
        onRemoveChannel={(id) => gateway.removeAlertChannel(id)}
      />
    </div>
  )
}
