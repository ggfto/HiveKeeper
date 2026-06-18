import { useEffect, useRef, useState } from 'react'

// A module-local counter gives each render a stable, unique id (mermaid needs one per diagram).
let seq = 0

/**
 * Renders a Mermaid diagram client-side, to match the public docs site (which renders the same ```mermaid
 * fences via astro-mermaid). Mermaid is imported lazily so it lands in its own chunk — the bundle only pays
 * for it on a Help page that actually has a diagram. Until (and unless) it renders, the diagram SOURCE is
 * shown, so it degrades gracefully outside a browser (SSR/tests) and on a syntax error.
 */
export function MermaidDiagram({ code }) {
  const [svg, setSvg] = useState('')
  const idRef = useRef(`mermaid-${++seq}`)

  useEffect(() => {
    let active = true
    ;(async () => {
      try {
        const mermaid = (await import('mermaid')).default
        const dark =
          typeof document !== 'undefined' && document.documentElement.classList.contains('dark')
        mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default' })
        const { svg: out } = await mermaid.render(idRef.current, code)
        if (active) setSvg(out)
      } catch {
        // Leave the source fallback in place — a broken diagram shouldn't blank the page.
      }
    })()
    return () => {
      active = false
    }
  }, [code])

  if (svg) {
    // eslint-disable-next-line react/no-danger -- mermaid renders with securityLevel:'strict' (sanitized).
    return <div className="my-4 flex justify-center overflow-x-auto" dangerouslySetInnerHTML={{ __html: svg }} />
  }
  return (
    <pre className="my-4 overflow-x-auto rounded-lg border border-border bg-muted/50 p-3 text-xs">
      <code className="font-mono text-xs">{code}</code>
    </pre>
  )
}
