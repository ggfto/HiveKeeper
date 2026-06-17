// Copy the single-source docs markdown (../docs) into src/docs so Vite can bundle it (import.meta.glob can
// only see files inside the project root). The same markdown is rendered by the public Starlight site
// (website/) — this keeps the in-app Help in sync with it without duplicating content in git. src/docs is
// gitignored and regenerated before every dev/build/test run. Run via `pnpm sync-docs` (or automatically by
// the dev/build/test scripts).
import { cpSync, mkdirSync, readdirSync, rmSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const src = join(here, '..', '..', 'docs') // repo-root/docs
const dest = join(here, '..', 'src', 'docs')

if (!existsSync(src)) {
  console.error(`[sync-docs] source not found: ${src}`)
  process.exit(1)
}

rmSync(dest, { recursive: true, force: true })
mkdirSync(dest, { recursive: true })
for (const entry of readdirSync(src, { withFileTypes: true })) {
  if (entry.isFile() && entry.name.endsWith('.md')) {
    cpSync(join(src, entry.name), join(dest, entry.name))
  }
}
console.log(`[sync-docs] copied ${readdirSync(dest).length} markdown file(s) -> src/docs`)
