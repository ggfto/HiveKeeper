/**
 * Config templates: a reusable set of HiveOS CLI lines an operator can apply across a scope (org / site / group)
 * via the gateway's bulk apply-config. Pure logic only — parsing the template text into commands and persisting
 * named templates in a Storage-like object (injectable, so it is testable without a browser). The gateway
 * re-validates and re-authorizes per device; this is purely the UI-side convenience layer.
 */

const KEY = 'hivekeeper.configTemplates'

/** Split template text into CLI commands: one per line, trimmed, dropping blank lines and `#` comments. */
export function parseTemplateCommands(text) {
  return (text || '')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'))
}

/** Load the saved named templates [{ name, text }] from storage; [] on missing or unparseable data. */
export function loadTemplates(storage) {
  try {
    const raw = storage?.getItem(KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((t) => t && typeof t.name === 'string') : []
  } catch {
    return []
  }
}

// Two names collide when they match ignoring case and surrounding space — so re-saving "Base" overwrites " base ".
const sameName = (a, b) => a.trim().toLowerCase() === b.trim().toLowerCase()

/**
 * Save (insert or overwrite by name) a named template, returning the new list. A blank name or a template with no
 * non-comment commands is ignored (returns the current list unchanged). The stored name keeps its original casing.
 */
export function saveTemplate(storage, name, text) {
  const n = (name || '').trim()
  if (!n || parseTemplateCommands(text).length === 0) return loadTemplates(storage)
  const existing = loadTemplates(storage)
  // Overwriting keeps the original entry's name casing; only its text changes.
  const keepName = existing.find((t) => sameName(t.name, n))?.name ?? n
  const list = existing.filter((t) => !sameName(t.name, n))
  list.push({ name: keepName, text })
  storage?.setItem(KEY, JSON.stringify(list))
  return list
}

/** Delete a named template (case/space-insensitive match), returning the new list. */
export function deleteTemplate(storage, name) {
  const list = loadTemplates(storage).filter((t) => !sameName(t.name, name || ''))
  storage?.setItem(KEY, JSON.stringify(list))
  return list
}
