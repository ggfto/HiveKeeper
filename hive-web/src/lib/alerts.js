/**
 * Threshold-based health alerts for an AP, evaluated from the live monitoring snapshot (no new backend — the
 * same `inventory` / status data the console already reads). Pure, so both the device Monitoring tab (one
 * device's loaded snapshot) and the fleet Alerts scan (each device's inventory) share one rule set, and the
 * thresholds are testable without a browser.
 *
 * A snapshot is the parsed Device status: { stations: [...], radios: [{name, channel, width, txPower|power}],
 * cloud: { managed }, ... }. `online` is whether the device's agent is connected; `model` its hardware model.
 * Missing data simply means a rule does not fire. -> [{ id, severity: 'critical'|'warning'|'info', message }].
 */
import { radioAdvisories } from './radioAdvisories'

export const DEFAULT_THRESHOLDS = { maxStations: 30 }
const THRESH_KEY = 'hivekeeper.alertThresholds'

const SEVERITY_RANK = { critical: 0, warning: 1, info: 2 }

/** Sort rank for a severity (lower = worse); unknown severities rank last. */
export function severityRank(severity) {
  return SEVERITY_RANK[severity] ?? 99
}

/** The worst (most severe) severity among a list of alerts, or null when there are none. */
export function worstSeverity(alerts) {
  if (!alerts || alerts.length === 0) return null
  return alerts.reduce((worst, a) => (severityRank(a.severity) < severityRank(worst) ? a.severity : worst), 'info')
}

export function evaluateAlerts({ online, snapshot } = {}, thresholds = DEFAULT_THRESHOLDS) {
  const t = { ...DEFAULT_THRESHOLDS, ...(thresholds || {}) }
  // An offline agent makes the AP unreachable; nothing else about it is knowable, so this is the only alert.
  if (online === false) {
    return [{ id: 'agent-offline', severity: 'critical', message: 'Agent offline — the AP is unreachable.' }]
  }

  const out = []
  // HiveKeeper's signature: a standalone AP should NOT be phoning home. CAPWAP still up = not standalone.
  if (snapshot?.cloud?.managed === true) {
    out.push({ id: 'cloud-managed', severity: 'warning', message: 'Still cloud-managed (CAPWAP up) — not standalone.' })
  }

  const stations = snapshot?.stations
  if (Array.isArray(stations) && stations.length > t.maxStations) {
    out.push({
      id: 'high-clients',
      severity: 'warning',
      message: `${stations.length} clients (> ${t.maxStations}) — high load on this AP.`,
    })
  }

  for (const r of snapshot?.radios || []) {
    const warnings = radioAdvisories({
      iface: (r.name || '').toLowerCase(),
      channel: r.channel,
      power: r.txPower ?? r.power,
      width: r.width,
    }).filter((a) => a.level === 'warning')
    if (warnings.length > 0) {
      out.push({
        id: `radio-${(r.name || '').toLowerCase()}`,
        severity: 'info',
        message: `Radio ${r.name}: ${warnings.map((w) => w.code).join(', ')}.`,
      })
    }
  }
  return out
}

/** Load the operator's alert thresholds from storage, merged over the defaults; defaults on missing/garbage. */
export function loadThresholds(storage) {
  try {
    const raw = storage?.getItem(THRESH_KEY)
    const parsed = raw ? JSON.parse(raw) : {}
    return { ...DEFAULT_THRESHOLDS, ...(parsed && typeof parsed === 'object' ? parsed : {}) }
  } catch {
    return { ...DEFAULT_THRESHOLDS }
  }
}

/** Persist alert thresholds (merged over the defaults), returning the stored object. */
export function saveThresholds(storage, thresholds) {
  const merged = { ...DEFAULT_THRESHOLDS, ...(thresholds || {}) }
  storage?.setItem(THRESH_KEY, JSON.stringify(merged))
  return merged
}
