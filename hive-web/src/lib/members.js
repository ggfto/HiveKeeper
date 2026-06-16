/**
 * Pure helpers for organization membership. The gateway is the source of truth for roles and enforcement;
 * these only drive the UI (the role choices, a label, and a throwaway password suggestion).
 */

/** The four org roles, lowest to highest privilege — the choices offered when adding or re-roling a member. */
export const ROLE_OPTIONS = [
  { label: 'Viewer', value: 'viewer' },
  { label: 'Operator', value: 'operator' },
  { label: 'Admin', value: 'admin' },
  { label: 'Owner', value: 'owner' },
]

/** A human label for a stored role string. */
export function roleLabel(role) {
  return ROLE_OPTIONS.find((r) => r.value === role)?.label || role || '—'
}

/** A throwaway temporary password to hand a new teammate. Not a security control — the gateway forces a reset
 *  at first sign-in regardless; this is just a convenience so the admin need not invent one. */
export function suggestPassword() {
  const rand = Math.random().toString(36).slice(2, 10)
  return `Hk-${rand}`
}
