import { NetworkForm } from './NetworkForm'
import { SchemaConfigForm } from './SchemaConfigForm'
import { DNS_SECTION, NTP_SECTION } from '../../lib/configSchema'

/** The Network screen: the mgt0 management settings plus DNS and NTP, all on one page (they were separate nav
 *  items before). Each part applies confirmed HiveOS CLI through apply-config. */
export function NetworkSection({ device, onApply, busy }) {
  return (
    <div className="grid items-start gap-8 xl:grid-cols-2">
      <NetworkForm device={device} onApply={onApply} busy={busy} />
      <div className="space-y-8">
        <SchemaConfigForm section={DNS_SECTION} device={device} onApply={onApply} busy={busy} />
        <SchemaConfigForm section={NTP_SECTION} device={device} onApply={onApply} busy={busy} />
      </div>
    </div>
  )
}
