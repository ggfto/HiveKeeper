import { MriSectionHeader } from '@mriqbox/ui-kit'
import { Globe } from 'lucide-react'
import { NetworkForm } from './NetworkForm'
import { SchemaConfigForm } from './SchemaConfigForm'
import { DNS_SECTION, NTP_SECTION } from '../../lib/configSchema'

/** The Network screen: the mgt0 management settings plus DNS and NTP, all on one page (they were separate nav
 *  items before). Leads with a section header like every other device tab, then groups the parts; each applies
 *  confirmed HiveOS CLI through apply-config. */
export function NetworkSection({ device, onApply, busy }) {
  return (
    <div className="space-y-4">
      <MriSectionHeader icon={Globe} title="Network" />
      <p className="text-xs text-muted-foreground">The management interface (mgt0), DNS, and NTP for this AP.</p>
      <div className="grid items-start gap-8 xl:grid-cols-2">
        <NetworkForm device={device} onApply={onApply} busy={busy} />
        <div className="space-y-8">
          <SchemaConfigForm section={DNS_SECTION} device={device} onApply={onApply} busy={busy} />
          <SchemaConfigForm section={NTP_SECTION} device={device} onApply={onApply} busy={busy} />
        </div>
      </div>
    </div>
  )
}
