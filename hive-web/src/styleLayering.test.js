import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

/**
 * Regression guard for a SILENT layout break (tests are green, the desktop shell is wrong).
 *
 * @mriqbox/ui-kit is Tailwind v3 and ships dist/style.css with UNLAYERED utilities (`.hidden`, `.flex`, …). Ours
 * are Tailwind v4 and land in `@layer utilities`. Per the CSS cascade an unlayered rule beats ANY layered rule,
 * so the kit's plain `.hidden{display:none}` overrode our `lg:flex`/`lg:hidden` at every width and the responsive
 * shell (persistent sidebar vs. mobile hamburger) collapsed to its mobile branch on desktop. The fix imports the
 * kit into its own `kit` cascade layer, declared before `utilities`, from index.css. Nothing in jsdom can catch a
 * cascade-layer regression, so we assert the source setup that makes it correct.
 */
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

describe('style cascade layering', () => {
  it('imports the ui-kit stylesheet into a low-priority `kit` layer from index.css', () => {
    const css = read('./index.css')
    expect(css).toMatch(/@import\s+['"]@mriqbox\/ui-kit\/dist\/style\.css['"]\s+layer\(kit\)/)
    // `kit` must be declared before `utilities` so our utilities win the cascade.
    const order = css.match(/@layer\s+([a-z, ]+);/)
    expect(order, 'index.css must declare an explicit @layer order').not.toBeNull()
    const layers = order[1].split(',').map((s) => s.trim())
    expect(layers.indexOf('kit')).toBeGreaterThanOrEqual(0)
    expect(layers.indexOf('kit')).toBeLessThan(layers.indexOf('utilities'))
  })

  it('does not import the kit stylesheet unlayered from main.jsx (which would bypass the layer)', () => {
    const main = read('./main.jsx')
    expect(main).not.toMatch(/import\s+['"]@mriqbox\/ui-kit\/dist\/style\.css['"]/)
  })
})
