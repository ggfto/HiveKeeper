/**
 * How well HiveKeeper supports a given device model. A soft signal, not a hard block: HiveKeeper drives any
 * HiveOS AP through the generic apply-config path, but only a few models have been exercised against real
 * hardware. Surfaced as a badge on adopted devices (and, later, on discovered hosts during adoption) so an
 * operator knows whether they are on a tested path or in untested territory.
 */

// Models validated against real hardware (or close siblings). Extend as more are exercised.
// AP230, AP410C and AP630 were each driven live on 2026-07-20 (HiveOS 10.6r1a / 10.6r6).
export const TESTED_MODELS = ['AP230', 'AP250', 'AP410C', 'AP630']

// HiveOS reports a hardware revision suffix on some models — the AP410C identifies itself as `AP410C-1`.
// That is the same access point as far as CLI grammar goes, so match the family and drop the suffix rather
// than listing every revision (and silently badging revision 2 as untested the day it appears).
function modelFamily(model) {
  return (model || '').trim().toUpperCase().replace(/-\d+$/, '')
}

/**
 * Classify a device by model.
 * @param model   the reported model string (e.g. "AP230", "AP410C-1"), or null/unknown.
 * @param hiveOs  whether the device is a recognized HiveOS AP (default true for an adopted device; pass
 *                false for a discovered host that did not identify as HiveOS).
 * @returns 'tested' | 'untested' | 'unsupported'
 */
export function supportLevel(model, { hiveOs = true } = {}) {
  if (!hiveOs) return 'unsupported'
  return TESTED_MODELS.includes(modelFamily(model)) ? 'tested' : 'untested'
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
