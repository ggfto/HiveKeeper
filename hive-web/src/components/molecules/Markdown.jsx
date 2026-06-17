import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// Rewrite the docs' links for the in-app HashRouter: external links open in a new tab; site-absolute doc
// links ('/getting-started/') become the in-app help hash route; everything else is left alone.
function Anchor({ href, children, ...props }) {
  if (!href) return <a {...props}>{children}</a>
  if (/^https?:/i.test(href)) {
    return (
      <a href={href} target="_blank" rel="noreferrer" className="text-primary underline" {...props}>
        {children}
      </a>
    )
  }
  if (href.startsWith('/')) {
    const seg = href.replace(/^\/+/, '').replace(/[/#].*$/, '')
    return (
      <a href={seg ? `#/help/${seg}` : '#/help'} className="text-primary underline" {...props}>
        {children}
      </a>
    )
  }
  return (
    <a href={href} className="text-primary underline" {...props}>
      {children}
    </a>
  )
}

// react-markdown has no default styling; map the elements to the app's design tokens. Kept deliberately
// plain (no @tailwindcss/typography dependency) so it matches the console.
const COMPONENTS = {
  a: Anchor,
  h1: (p) => <h1 className="mt-2 mb-4 text-2xl font-semibold tracking-tight" {...p} />,
  h2: (p) => <h2 className="mt-8 mb-3 border-b border-border pb-1 text-xl font-semibold" {...p} />,
  h3: (p) => <h3 className="mt-6 mb-2 text-base font-semibold" {...p} />,
  p: (p) => <p className="my-3 leading-relaxed text-foreground" {...p} />,
  ul: (p) => <ul className="my-3 list-disc space-y-1 pl-6" {...p} />,
  ol: (p) => <ol className="my-3 list-decimal space-y-1 pl-6" {...p} />,
  li: (p) => <li className="leading-relaxed" {...p} />,
  blockquote: (p) => (
    <blockquote className="my-4 border-l-4 border-primary/40 bg-muted/40 px-4 py-2 text-sm" {...p} />
  ),
  // react-markdown v9 dropped the `inline` prop; fenced blocks carry a `language-*` class, inline code does not.
  code: ({ className, children, ...props }) =>
    /language-/.test(className || '') ? (
      <code className={`font-mono text-xs ${className}`} {...props}>
        {children}
      </code>
    ) : (
      <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-[0.85em]" {...props}>
        {children}
      </code>
    ),
  pre: (p) => (
    <pre className="my-4 overflow-x-auto rounded-lg border border-border bg-muted/50 p-3 text-xs" {...p} />
  ),
  table: (p) => (
    <div className="my-4 overflow-x-auto">
      <table className="w-full border-collapse text-sm" {...p} />
    </div>
  ),
  th: (p) => <th className="border border-border bg-muted px-3 py-2 text-left font-semibold" {...p} />,
  td: (p) => <td className="border border-border px-3 py-2 align-top" {...p} />,
  hr: () => <hr className="my-6 border-border" />,
}

/** Render a markdown string with GitHub-flavored markdown + the console's styling. */
export function Markdown({ children }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={COMPONENTS}>
      {children || ''}
    </ReactMarkdown>
  )
}
