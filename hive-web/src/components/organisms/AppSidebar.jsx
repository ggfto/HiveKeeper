import { MriSidebar } from '@mriqbox/ui-kit'
import { LayoutDashboard, Server, Boxes, Layers, Users, ListChecks, ScrollText } from 'lucide-react'

/** The console's primary navigation. Routes are the HashRouter paths the pages mount on. */
export const NAV = [
  { label: 'Overview', route: '/overview', icon: LayoutDashboard },
  { label: 'Agents', route: '/agents', icon: Server },
  { label: 'Devices', route: '/devices', icon: Boxes },
  { label: 'Sites & Groups', route: '/sites-groups', icon: Layers },
  { label: 'Members', route: '/members', icon: Users },
  { label: 'Bulk ops', route: '/bulk', icon: ListChecks },
  { label: 'Audit log', route: '/audit', icon: ScrollText },
]

/**
 * The full sidebar: the brand pinned at the top, the navigation in the scrollable middle, and whatever `footer`
 * the shell hands down (the org switcher + account controls) pinned to the bottom. The brand sits above MriSidebar
 * rather than inside it because MriSidebar has no header slot — its `className` leaks onto every nav button — so we
 * compose it in a wrapper and let the nav fill the remaining height (`flex-1 min-h-0` so MriSidebar's `h-full`
 * resolves against the space left under the brand).
 */
export function AppSidebar({ activeRoute, onNavigate, footer }) {
  return (
    <div className="flex h-full w-60 flex-col bg-card">
      <div className="flex items-center gap-2 border-b border-border px-4 py-4">
        <span className="text-xl" aria-hidden>
          🐝
        </span>
        <span className="font-semibold tracking-tight">HiveKeeper</span>
      </div>
      <div className="min-h-0 flex-1">
        <MriSidebar items={NAV} activeRoute={activeRoute} onNavigate={onNavigate} footer={footer} />
      </div>
    </div>
  )
}
