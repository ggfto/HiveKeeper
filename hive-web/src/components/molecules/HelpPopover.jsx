import { MriPopover, MriPopoverTrigger, MriPopoverContent } from '@mriqbox/ui-kit'
import { HelpCircle } from 'lucide-react'
import { Markdown } from './Markdown'

/**
 * Contextual help: a small "?" affordance that opens a short markdown blurb in a popover, with an optional
 * "Learn more" link into the embedded manual (/help/<docId>). The same single-source docs, one click away
 * from where the setting lives.
 */
export function HelpPopover({ title, body, docId, label = 'Help' }) {
  return (
    <MriPopover>
      <MriPopoverTrigger asChild>
        <button
          type="button"
          aria-label={label}
          className="inline-flex h-5 w-5 items-center justify-center rounded text-muted-foreground hover:text-foreground"
        >
          <HelpCircle className="h-4 w-4" />
        </button>
      </MriPopoverTrigger>
      <MriPopoverContent align="start" className="w-80 max-w-[calc(100vw-2rem)] text-sm">
        {title && <div className="mb-1 font-semibold">{title}</div>}
        {body && <Markdown>{body}</Markdown>}
        {docId && (
          <a href={`#/help/${docId}`} className="mt-2 inline-block text-xs text-primary underline">
            Learn more →
          </a>
        )}
      </MriPopoverContent>
    </MriPopover>
  )
}
