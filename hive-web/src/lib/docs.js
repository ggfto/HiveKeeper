// In-app documentation, loaded from the markdown bundled by scripts/sync-docs.mjs (the same single source the
// public Starlight site renders). Pure helpers: parse frontmatter, normalize Starlight-isms for in-app
// rendering, and expose an ordered list keyed by doc id (the filename without extension).

// Eagerly inline every doc as a raw string at build time. The key is like '../docs/getting-started.md'.
const RAW = import.meta.glob('../docs/*.md', { query: '?raw', import: 'default', eager: true })

// Sidebar order (matches the website's astro.config sidebar). 'index' is the introduction/home.
const ORDER = [
  'index',
  'getting-started',
  'capabilities',
  'authentication',
  'device-configuration',
  'deployment',
  'architecture',
  'agent-protocol',
]

const TITLE_FALLBACK = { index: 'Introduction' }

/** Strip a leading YAML frontmatter block, returning { data, body }. Minimal (title/description only). */
export function parseFrontmatter(raw) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(raw)
  if (!m) return { data: {}, body: raw }
  const data = {}
  for (const line of m[1].split(/\r?\n/)) {
    const kv = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(line)
    if (kv) data[kv[1]] = kv[2].replace(/^["']|["']$/g, '').trim()
  }
  return { data, body: raw.slice(m[0].length) }
}

// Turn Starlight asides (`:::note[Title]\n...\n:::`) into plain blockquotes so react-markdown renders them
// sensibly in-app (the website keeps the styled asides). Also rewrite site-absolute doc links
// ('/getting-started/') to the in-app help route ('#/help/getting-started') the HashRouter understands.
function normalize(body) {
  const withQuotes = body.replace(
    /^:::(\w+)(?:\[([^\]]*)\])?\r?\n([\s\S]*?)\r?\n:::\s*$/gm,
    (_all, type, title, inner) => {
      const heading = `**${title || type[0].toUpperCase() + type.slice(1)}**`
      const quoted = inner
        .split(/\r?\n/)
        .map((l) => `> ${l}`)
        .join('\n')
      return `> ${heading}\n>\n${quoted}`
    },
  )
  return withQuotes
}

function docId(globKey) {
  const file = globKey.split('/').pop().replace(/\.md$/, '')
  return file
}

const BY_ID = {}
for (const [key, raw] of Object.entries(RAW)) {
  const id = docId(key)
  const { data, body } = parseFrontmatter(raw)
  BY_ID[id] = {
    id,
    title: data.title || TITLE_FALLBACK[id] || id,
    description: data.description || '',
    body: normalize(body),
  }
}

/** All docs, in sidebar order (unknown ids appended alphabetically). */
export function allDocs() {
  const known = ORDER.filter((id) => BY_ID[id]).map((id) => BY_ID[id])
  const extra = Object.keys(BY_ID)
    .filter((id) => !ORDER.includes(id))
    .sort()
    .map((id) => BY_ID[id])
  return [...known, ...extra]
}

/** One doc by id, or null. The home page is 'index'. */
export function getDoc(id) {
  return BY_ID[id] || null
}

/** The default doc id to show on /help (the introduction). */
export const HOME_DOC = 'index'
