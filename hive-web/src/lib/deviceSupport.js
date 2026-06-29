/**
 * How well HiveKeeper supports a given device model. A soft signal, not a hard block: HiveKeeper drives any
 * HiveOS AP through the generic apply-config path, but only a few models have been exercised against real
 * hardware. Surfaced as a badge on adopted devices (and, later, on discovered hosts during adoption) so an
 * operator knows whether they are on a tested path or in untested territory.
 */

// Models validated against real hardware (or close siblings). Extend as more are exercised.
export const TESTED_MODELS = ['AP230', 'AP250', 'AP630']

/**
 * Classify a device by model.
 * @param model   the reported model string (e.g. "AP230"), or null/unknown.
 * @param hiveOs  whether the device is a recognized HiveOS AP (default true for an adopted device; pass
 *                false for a discovered host that did not identify as HiveOS).
 * @returns 'tested' | 'untested' | 'unsupported'
 */
export function supportLevel(model, { hiveOs = true } = {}) {
  if (!hiveOs) return 'unsupported'
  const m = (model || '').trim().toUpperCase()
  return TESTED_MODELS.includes(m) ? 'tested' : 'untested'
}

/** The badge label + ui-kit variant for a support level. */
export function supportBadge(level) {
  switch (level) {
    case 'tested':
      return { label: 'tested', variant: 'success' }
    case 'unsupported':
      return { label: 'unsupported', variant: 'destructive' }
    default:
      return { label: 'HiveOS · untested', variant: 'outline' }
  }
}
