// @ts-check
import { defineConfig } from 'astro/config'
import starlight from '@astrojs/starlight'
import { visit } from 'unist-util-visit'

// Starlight base-prefixes its own nav/sidebar, but NOT root-absolute links written inside markdown
// (`[x](/foo/)`). Under a project-page base like /HiveKeeper those would 404. This rehype plugin prefixes
// them at build time, so the content stays portable: the markdown keeps clean `/foo/` links (which the in-app
// Help rewrites to `#/help/foo`), and the published site gets the base. Anchors/external/`//` are left alone.
function rehypeBaseLinks() {
  const base = (process.env.BASE_PATH || '/').replace(/\/$/, '')
  return (/** @type {any} */ tree) => {
    if (!base) return
    visit(tree, 'element', (/** @type {any} */ node) => {
      const href = node.tagName === 'a' ? node.properties?.href : undefined
      if (
        typeof href === 'string' &&
        href.startsWith('/') &&
        !href.startsWith('//') &&
        href !== base &&
        !href.startsWith(base + '/')
      ) {
        node.properties.href = base + href
      }
    })
  }
}

// The HiveKeeper docs site. Content is NOT kept here — it lives in ../docs (the single source the in-app
// help also renders); see src/content.config.ts for the loader.
//
// `site`/`base` come from the environment so the GitHub Pages workflow can inject the right values (derived
// at build time by actions/configure-pages — no GitHub username hardcoded). Locally they default to a root
// site so `pnpm dev`/`build` Just Work. For a GitHub project page the workflow sets BASE_PATH=/HiveKeeper.
const SITE_URL = process.env.SITE_URL || 'https://hivekeeper.dev'
const BASE_PATH = process.env.BASE_PATH || '/'

export default defineConfig({
  site: SITE_URL,
  base: BASE_PATH,
  markdown: {
    rehypePlugins: [rehypeBaseLinks],
  },
  integrations: [
    starlight({
      title: 'HiveKeeper',
      description: 'Cloud-free management for Aerohive / Extreme HiveOS access points, over SSH.',
      customCss: ['./src/styles/theme.css'],
      sidebar: [
        { label: 'Live demo ↗', link: '/demo/', attrs: { target: '_blank', rel: 'noopener' } },
        { label: 'Introduction', link: '/' },
        { label: 'Getting started', link: '/getting-started/' },
        { label: 'Capabilities', link: '/capabilities/' },
        { label: 'Authentication', link: '/authentication/' },
        { label: 'Device configuration', link: '/device-configuration/' },
        { label: 'Architecture', link: '/architecture/' },
        { label: 'Agent ⇄ gateway protocol', link: '/agent-protocol/' },
      ],
    }),
  ],
})
