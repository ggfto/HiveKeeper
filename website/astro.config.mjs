// @ts-check
import { defineConfig } from 'astro/config'
import starlight from '@astrojs/starlight'

// The HiveKeeper docs site. Content is NOT kept here — it lives in ../docs (the single source the in-app
// help also renders); see src/content.config.ts for the loader. Set `site` to your real domain before
// publishing (it drives canonical URLs + the sitemap).
export default defineConfig({
  site: 'https://hivekeeper.dev',
  integrations: [
    starlight({
      title: 'HiveKeeper',
      description: 'Cloud-free management for Aerohive / Extreme HiveOS access points, over SSH.',
      sidebar: [
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
