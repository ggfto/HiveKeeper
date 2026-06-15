/**
 * Vertical category navigation for the device page. Scales to many config categories (unlike a tab strip),
 * which is the point — the AP has a large config surface. Controlled: the page owns the active section.
 */
export function ConfigNav({ sections, active, onSelect }) {
  return (
    <nav className="flex shrink-0 flex-row gap-1 overflow-x-auto sm:w-48 sm:flex-col">
      {sections.map((s) => {
        const Icon = s.icon
        const on = s.id === active
        return (
          <button
            key={s.id}
            type="button"
            onClick={() => onSelect(s.id)}
            className={`flex items-center gap-2 whitespace-nowrap rounded-md px-3 py-2 text-left text-sm transition-colors ${
              on ? 'bg-primary/10 font-medium text-primary' : 'text-muted-foreground hover:bg-muted hover:text-foreground'
            }`}
          >
            {Icon && <Icon className="h-4 w-4 shrink-0" />}
            <span>{s.label}</span>
          </button>
        )
      })}
    </nav>
  )
}
