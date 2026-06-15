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
