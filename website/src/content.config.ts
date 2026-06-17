import { defineCollection } from 'astro:content'
import { glob } from 'astro/loaders'
import { docsSchema } from '@astrojs/starlight/schema'

// Source the Starlight `docs` collection from the repo's ../docs folder instead of src/content/docs, so the
// SAME markdown is rendered by this site AND by the in-app help in hive-web (single source of truth). The
// base is resolved relative to this project root (website/).
export const collections = {
  docs: defineCollection({
    loader: glob({ pattern: '**/*.{md,mdx}', base: '../docs' }),
    schema: docsSchema(),
  }),
}
