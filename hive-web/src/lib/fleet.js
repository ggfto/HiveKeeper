/**
 * Pure fleet/domain helpers shared across the console's components. Keeping this logic out of the React tree
 * makes it fully unit-testable and the components thin (atomic design: smarts here, presentation there).
 */

/** The display names of the groups a device is tagged into (falls back to the id for an unknown group). */
export function groupNamesFor(device, groups) {
  const byId = new Map((groups || []).map((g) => [g.groupId, g.name]))
  return (device?.groups || []).map((id) => byId.get(id) || id)
}

/** A site's display name (or the id if not found, or null for no site). */
export function siteName(siteId, sites) {
  if (!siteId) return null
  return (sites || []).find((s) => s.siteId === siteId)?.name || siteId
}

/**
 * How long ago an agent was last connected, for an offline agent's card. Relative while that reads as a
 * duration someone can act on ("18 min ago", "3 d ago") and absolute past a month, where "47 d ago" stops
 * meaning anything. `null` last-seen is an agent that has never dialed in — worth saying outright, since it
 * usually means the enrollment token was issued but the install never finished.
 *
 * `now` is a parameter so this stays pure and testable rather than reading the clock.
 */
export function lastSeenLabel(lastSeen, now = Date.now()) {
  if (!lastSeen) return 'never connected'
  const then = new Date(lastSeen).getTime()
  if (Number.isNaN(then)) return 'never connected'
  const seconds = Math.floor((now - then) / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.floor(hours / 24)
  if (days <= 30) return `${days} d ago`
  return new Date(lastSeen).toLocaleDateString()
}

/** The MriSelect options for a bulk-op target: the org, then each site, then each group. */
export function bulkTargetOptions(sites = [], groups = []) {
  return [
    { label: 'Whole organization', value: 'org' },
    ...sites.map((s) => ({ label: `Site: ${s.name}`, value: `site:${s.siteId}` })),
    ...groups.map((g) => ({ label: `Group: ${g.name}`, value: `group:${g.groupId}` })),
  ]
}

/** Parse a bulk-target select value ('org' | 'site:<id>' | 'group:<id>') into the gateway client shape. */
export function parseBulkTarget(value) {
  if (typeof value === 'string' && value.startsWith('site:')) return { kind: 'site', id: value.slice(5) }
  if (typeof value === 'string' && value.startsWith('group:')) return { kind: 'group', id: value.slice(6) }
  return { kind: 'org' }
}

/** A one-line summary of a bulk response. */
export function summarizeBulk(result) {
  if (!result) return ''
  const { op, ok = 0, total = 0, failed = 0 } = result
  return `${op}: ${ok}/${total} ok, ${failed} failed`
}

const OUTCOME_VARIANT = {
  ok: 'success',
  failed: 'destructive',
  forbidden: 'destructive',
  agent_offline: 'warning',
  skipped: 'warning',
  timeout: 'warning',
}

/** Map a per-device bulk outcome status to an MriStatusBadge variant. */
export function outcomeVariant(status) {
  return OUTCOME_VARIANT[status] || 'outline'
}
