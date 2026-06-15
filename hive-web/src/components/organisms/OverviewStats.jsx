import { MriCard, MriCardContent } from '@mriqbox/ui-kit'
import { Server, Boxes, Building2, Layers } from 'lucide-react'

const ITEMS = [
  { key: 'agents', label: 'Agents', icon: Server },
  { key: 'devices', label: 'Devices', icon: Boxes },
  { key: 'sites', label: 'Sites', icon: Building2 },
  { key: 'groups', label: 'Groups', icon: Layers },
]

/** The dashboard headline: one metric card per fleet dimension for the active organization. */
export function OverviewStats({ counts = {} }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {ITEMS.map(({ key, label, icon: Icon }) => (
        <MriCard key={key}>
          <MriCardContent className="flex items-center gap-3 p-4">
            <div className="rounded-md bg-primary/10 p-2 text-primary">
              <Icon className="h-5 w-5" />
            </div>
            <div>
              <div className="text-2xl font-semibold leading-none">{counts[key] ?? 0}</div>
              <div className="mt-1 text-xs text-muted-foreground">{label}</div>
            </div>
          </MriCardContent>
        </MriCard>
      ))}
    </div>
  )
}
