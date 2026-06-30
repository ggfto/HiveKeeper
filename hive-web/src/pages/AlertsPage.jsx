import { useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { BellRing } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { FleetAlertsPanel } from '../components/organisms/FleetAlertsPanel'
import { loadThresholds, saveThresholds } from '../lib/alerts'

/**
 * Fleet alerts: an on-demand scan that reads each device's live inventory through its agent and evaluates it
 * against the configured thresholds. No background poller — the operator scans when they want a fresh picture;
 * the raw snapshots are kept so threshold edits re-evaluate without re-scanning. Thresholds persist locally.
 */
export function AlertsPage() {
  const { gateway, activeOrg } = useAuth()
  const { toast } = useToast()
  const [scan, setScan] = useState(null)
  const [scanning, setScanning] = useState(false)
  const [scannedAt, setScannedAt] = useState(null)
  const [thresholds, setThresholds] = useState(() => loadThresholds(window.localStorage))

  const onThresholds = (t) => setThresholds(saveThresholds(window.localStorage, t))

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

  // Re-load devices/agents implicitly each scan, so an org switch just needs a re-scan; nothing to preload here.
  void activeOrg

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
    </div>
  )
}
