import { MriSelect } from '@mriqbox/ui-kit'
import { orgOptions } from '../../lib/authState'

/**
 * The organization switcher — the multi-org heart of the console. The active org rides along on every gateway
 * request (X-Org), so switching it re-scopes the whole console to the user's role in that organization.
 */
export function OrgSwitcher({ me, activeOrg, onChange }) {
  const options = orgOptions(me)
  if (options.length === 0) return null
  if (options.length === 1) {
    return <span className="text-sm font-medium">{options[0].label}</span>
  }
  return (
    <div className="w-44">
      <MriSelect options={options} value={activeOrg} onChange={onChange} size="sm" />
    </div>
  )
}
