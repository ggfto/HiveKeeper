import { X } from 'lucide-react'
import { useToast } from '../../context/ToastProvider'

const TONE = {
  default: 'border-l-4 border-l-border',
  success: 'border-l-4 border-l-green-500',
  error: 'border-l-4 border-l-destructive',
}

/** Renders the toast queue (bottom-right, stacked, dismissible). Mount once near the app root. */
export function Toaster() {
  const { toasts, dismiss } = useToast()
  if (toasts.length === 0) return null
  return (
    <div className="fixed bottom-4 right-4 z-50 flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          role="status"
          className={`flex items-start gap-2 rounded-md border bg-card p-3 text-sm text-foreground shadow-lg ${TONE[t.variant] || TONE.default}`}
        >
          <span className="flex-1 break-words">{t.message}</span>
          <button
            type="button"
            aria-label="Dismiss notification"
            className="text-muted-foreground hover:text-foreground"
            onClick={() => dismiss(t.id)}
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      ))}
    </div>
  )
}
