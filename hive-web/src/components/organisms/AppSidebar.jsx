import { MriSidebar } from '@mriqbox/ui-kit'
import { LayoutDashboard, Server, Boxes, Layers, ListChecks, ScrollText } from 'lucide-react'

/** The console's primary navigation. Routes are the HashRouter paths the pages mount on. */
export const NAV = [
  { label: 'Overview', route: '/overview', icon: LayoutDashboard },
  { label: 'Agents', route: '/agents', icon: Server },
  { label: 'Devices', route: '/devices', icon: Boxes },
  { label: 'Sites & Groups', route: '/sites-groups', icon: Layers },
  { label: 'Bulk ops', route: '/bulk', icon: ListChecks },
  { label: 'Audit log', route: '/audit', icon: ScrollText },
]

export function AppSidebar({ activeRoute, onNavigate, footer }) {
  return <MriSidebar items={NAV} activeRoute={activeRoute} onNavigate={onNavigate} footer={footer} />
}
