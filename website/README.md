# HiveKeeper docs site

The public documentation site, built with [Astro](https://astro.build) +
[Starlight](https://starlight.astro.build).

**Content lives in [`../docs`](../docs), not here.** That folder is the single source of truth: this site
renders it, and so does the in-app Help inside `hive-web` (via `scripts/sync-docs.mjs`). Edit the markdown
in `../docs`; both surfaces pick it up. The Starlight `docs` collection is pointed at `../docs` in
[`src/content.config.ts`](src/content.config.ts).

## Develop

```sh
pnpm install
pnpm dev        # http://localhost:4321
pnpm build      # static site -> dist/
pnpm preview    # serve the built site
```

## Adding a page

1. Add a `.md` file under `../docs` with frontmatter `title:` (and ideally `description:`).
2. Add it to the `sidebar` in [`astro.config.mjs`](astro.config.mjs) (the site does not auto-order pages).
3. If it should also appear in the in-app Help nav, add its id to `ORDER` in `hive-web/src/lib/docs.js`.

## Deploy

`pnpm build` emits a static site to `dist/` (with a Pagefind search index).

**GitHub Pages** is wired up via [`.github/workflows/deploy-docs.yml`](../.github/workflows/deploy-docs.yml):
on push to `main` (when `docs/**` or `website/**` change) it builds and publishes. The site URL and base
path are injected at build time from `actions/configure-pages` (env vars `SITE_URL` / `BASE_PATH`), so a
project page at `https://<owner>.github.io/<repo>/` works with no hardcoding — a rehype plugin in
`astro.config.mjs` also rewrites root-absolute links in the markdown to include the base. One-time setup:
**Settings → Pages → Source: GitHub Actions** (the workflow also attempts to enable it).

For any other static host (Cloudflare Pages, Netlify, …), run `pnpm build` and serve `dist/`, setting
`SITE_URL` (and `BASE_PATH` if not at the domain root) in the environment.
