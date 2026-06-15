import { MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { Cloud, Power } from 'lucide-react'
import { capwapCommands } from '../../lib/hiveosCli'

/** Cloud connection (standalone via CAPWAP) + reboot. Standalone is the HiveKeeper signature: cut the AP's
 *  link to HiveManager / ExtremeCloud IQ and manage it directly. */
export function PowerForm({ device, onApply, onReboot, busy }) {
  return (
    <div className="space-y-5">
      <section className="space-y-2">
        <MriSectionHeader icon={Cloud} title="Cloud connection" />
        <p className="text-xs text-muted-foreground">
          Standalone cuts the AP&apos;s CAPWAP link to the cloud (HiveManager / ExtremeCloud IQ) so HiveKeeper
          manages it directly.
        </p>
        <div className="flex flex-wrap gap-2">
          <MriButton
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() => onApply(device, { commands: capwapCommands(false), save: true })}
          >
            Disconnect (standalone)
          </MriButton>
          <MriButton
            size="sm"
            variant="ghost"
            disabled={busy}
            onClick={() => onApply(device, { commands: capwapCommands(true), save: true })}
          >
            Reconnect to cloud
          </MriButton>
        </div>
      </section>
      <section className="space-y-2">
        <MriSectionHeader icon={Power} title="Power" />
        <MriButton size="sm" variant="destructive" disabled={busy} onClick={() => onReboot(device)}>
          Reboot device
        </MriButton>
      </section>
    </div>
  )
}
