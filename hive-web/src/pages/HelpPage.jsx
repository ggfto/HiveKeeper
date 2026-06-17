import { useParams } from 'react-router-dom'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { BookOpen } from 'lucide-react'
import { Markdown } from '../components/molecules/Markdown'
import { allDocs, getDoc, HOME_DOC } from '../lib/docs'

/**
 * The embedded manual: the same markdown the public docs site renders, bundled into the app so it works
 * offline (solo / self-hosted). A vertical list of docs on the left, the selected one on the right.
 */
export function HelpPage() {
  const { docId } = useParams()
  const docs = allDocs()
  const active = getDoc(docId || HOME_DOC) || docs[0] || null

  return (
    <div className="space-y-5">
      <MriPageHeader title="Documentation" icon={BookOpen} countLabel={active?.title} />
      <div className="flex flex-col gap-6 sm:flex-row">
        <nav className="flex shrink-0 flex-row gap-1 overflow-x-auto sm:w-56 sm:flex-col">
          {docs.map((d) => {
            const on = active && d.id === active.id
            return (
              <a
                key={d.id}
                href={`#/help/${d.id}`}
                className={`whitespace-nowrap rounded-md px-3 py-2 text-left text-sm transition-colors ${
                  on
                    ? 'bg-primary/10 font-medium text-primary'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground'
                }`}
              >
                {d.title}
              </a>
            )
          })}
        </nav>
        <article className="min-w-0 flex-1">
          {active ? (
            <Markdown>{active.body}</Markdown>
          ) : (
            <p className="text-sm text-muted-foreground">No documentation found.</p>
          )}
        </article>
      </div>
    </div>
  )
}
