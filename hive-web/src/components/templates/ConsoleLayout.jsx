import { MriDashboardLayout, MriTopbar, MriStatusBadge, MriButton } from '@mriqbox/ui-kit'
import { AppSidebar } from '../organisms/AppSidebar'
import { OrgSwitcher } from '../molecules/OrgSwitcher'
import { AccountMenu } from '../molecules/AccountMenu'

/**
 * The control-panel shell: a persistent sidebar (sections) + a topbar carrying the brand, the organization
 * switcher (the multi-org pivot) and the account menu, around the routed page content.
 */
export function ConsoleLayout({ activeRoute, onNavigate, auth, children }) {
  const brand = (
    <div className="flex items-center gap-2">
      <span className="text-xl" aria-hidden>
        🐝
      </span>
      <span className="font-semibold tracking-tight">HiveKeeper</span>
    </div>
  )
  const right = auth.user ? (
    <div className="flex items-center gap-3">
      <OrgSwitcher me={auth.me} activeOrg={auth.activeOrg} onChange={auth.setActiveOrg} />
      <AccountMenu user={auth.user} me={auth.me} onSignOut={auth.signOut} />
    </div>
  ) : (
    <div className="flex items-center gap-2">
      <MriStatusBadge label="DEV OWNER" variant="warning" size="sm" />
      <MriButton size="sm" variant="ghost" onClick={auth.signOut}>
        Exit
      </MriButton>
    </div>
  )
  return (
    <MriDashboardLayout
      sidebar={<AppSidebar activeRoute={activeRoute} onNavigate={onNavigate} />}
      topbar={<MriTopbar items={[]} logo={brand} rightContent={right} />}
    >
      <div className="h-full w-full p-4 lg:p-6">{children}</div>
    </MriDashboardLayout>
  )
}
