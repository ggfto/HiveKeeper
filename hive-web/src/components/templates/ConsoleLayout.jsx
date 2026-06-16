import { MriDashboardLayout, MriStatusBadge, MriButton } from '@mriqbox/ui-kit'
import { AppSidebar } from '../organisms/AppSidebar'
import { OrgSwitcher } from '../molecules/OrgSwitcher'
import { AccountMenu } from '../molecules/AccountMenu'

/**
 * The control-panel shell: a single persistent sidebar carrying the brand (top), the navigation (middle), and —
 * pinned to its footer — the organization switcher (the multi-org pivot) and the account menu, around the routed
 * page content. There is no topbar; everything that used to live on the horizontal bar now rides in the sidebar.
 */
export function ConsoleLayout({ activeRoute, onNavigate, auth, children }) {
  let footer
  if (auth.solo) {
    // Single-user local mode: no organizations, no account — just a marker.
    footer = (
      <div className="flex items-center gap-2 px-1">
        <MriStatusBadge label="LOCAL" variant="success" size="sm" />
        <span className="text-xs text-muted-foreground">single-AP mode</span>
      </div>
    )
  } else if (auth.user) {
    footer = (
      <div className="flex flex-col gap-2">
        <OrgSwitcher me={auth.me} activeOrg={auth.activeOrg} onChange={auth.setActiveOrg} />
        <AccountMenu user={auth.user} me={auth.me} onSignOut={auth.signOut} />
      </div>
    )
  } else {
    footer = (
      <div className="flex items-center justify-between gap-2 px-1">
        <MriStatusBadge label="DEV OWNER" variant="warning" size="sm" />
        <MriButton size="sm" variant="ghost" onClick={auth.signOut}>
          Exit
        </MriButton>
      </div>
    )
  }
  return (
    <MriDashboardLayout
      sidebar={<AppSidebar activeRoute={activeRoute} onNavigate={onNavigate} footer={footer} solo={auth.solo} />}
    >
      <div className="h-full w-full p-4 lg:p-6">{children}</div>
    </MriDashboardLayout>
  )
}
