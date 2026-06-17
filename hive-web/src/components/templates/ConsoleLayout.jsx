import { useEffect, useState } from 'react'
import { MriDashboardLayout, MriStatusBadge, MriButton } from '@mriqbox/ui-kit'
import { Menu, X } from 'lucide-react'
import { AppSidebar } from '../organisms/AppSidebar'
import { OrgSwitcher } from '../molecules/OrgSwitcher'
import { AccountMenu } from '../molecules/AccountMenu'

/**
 * The control-panel shell: a single persistent sidebar carrying the brand (top), the navigation (middle), and —
 * pinned to its footer — the organization switcher (the multi-org pivot) and the account menu, around the routed
 * page content. There is no topbar; everything that used to live on the horizontal bar now rides in the sidebar.
 *
 * Below the `lg` breakpoint the persistent sidebar is hidden and replaced by a slim mobile top bar with a
 * hamburger that opens the same sidebar as a slide-over drawer — so the full ~240px-wide navigation never
 * squeezes the page content on a phone.
 */
export function ConsoleLayout({ activeRoute, onNavigate, auth, children }) {
  const [drawerOpen, setDrawerOpen] = useState(false)

  // Close the drawer whenever the route changes (i.e. after a nav tap on a phone).
  useEffect(() => {
    setDrawerOpen(false)
  }, [activeRoute])

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
      sidebar={
        <AppSidebar
          activeRoute={activeRoute}
          onNavigate={onNavigate}
          footer={footer}
          solo={auth.solo}
          className="hidden lg:flex"
        />
      }
    >
      <div className="flex h-full w-full flex-col">
        {/* Mobile-only top bar: the hamburger that opens the navigation drawer (the desktop sidebar is hidden). */}
        <div className="flex shrink-0 items-center gap-3 border-b border-border bg-card px-4 py-3 lg:hidden">
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            aria-label="Open navigation"
            className="-ml-1 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <Menu className="h-5 w-5" />
          </button>
          <span className="text-lg" aria-hidden>
            🐝
          </span>
          <span className="font-semibold tracking-tight">HiveKeeper</span>
        </div>
        {auth.demo && <DemoBanner />}
        <div className="min-h-0 flex-1 overflow-y-auto p-4 lg:p-6">{children}</div>
      </div>

      {/* Mobile navigation drawer: the full sidebar slides over the content; tapping the backdrop or a nav item
          (which changes the route) closes it. Rendered only below `lg`. */}
      {drawerOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div
            className="absolute inset-0 bg-black/60"
            onClick={() => setDrawerOpen(false)}
            aria-hidden
          />
          <div className="absolute inset-y-0 left-0 max-w-[85vw] shadow-xl">
            <button
              type="button"
              onClick={() => setDrawerOpen(false)}
              aria-label="Close navigation"
              className="absolute right-2 top-3 z-10 rounded-md p-1 text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <X className="h-5 w-5" />
            </button>
            <AppSidebar
              activeRoute={activeRoute}
              onNavigate={onNavigate}
              footer={footer}
              solo={auth.solo}
              className="flex"
            />
          </div>
        </div>
      )}
    </MriDashboardLayout>
  )
}

/** A thin strip making clear this is a sandbox: data is fake and any change is local + reset on reload. */
function DemoBanner() {
  return (
    <div className="flex flex-wrap items-center justify-center gap-x-2 gap-y-0.5 border-b border-primary/30 bg-primary/10 px-4 py-1.5 text-center text-xs text-foreground">
      <span>
        <strong>Demo</strong> — sample data; changes are in-memory and reset on reload.
      </span>
      <a
        href="https://github.com/ggfto/HiveKeeper"
        target="_blank"
        rel="noreferrer"
        className="text-primary underline"
      >
        Source &amp; docs
      </a>
    </div>
  )
}
