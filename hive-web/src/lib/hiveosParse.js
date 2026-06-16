/**
 * Pure parsers for HiveOS CLI output. The agent's running-config is the source of truth for what is configured
 * on an AP; parsing it here (UI-side, fully tested) lets the Wi-Fi section list real SSIDs without a new
 * backend Command. Grammar confirmed against an AP230's running-config.
 */

function escapeRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Parse the SSIDs from a `show running-config`. Each SSID: { name, security, vlan, radios }. The security
 * protocol comes from `security-object <name> security protocol-suite <suite> ...`, the VLAN from the SSID's
 * `user-profile ... vlan-id <n>`, and the radios from `interface wifiN ssid <name>` bindings.
 */
export function parseSsids(config) {
  const lines = (config || '')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)

  const names = [
    ...new Set(
      lines
        .filter((l) => /^ssid \S/.test(l))
        .map((l) => l.split(/\s+/)[1])
        .filter(Boolean),
    ),
  ]

  return names.map((name) => {
    const n = escapeRe(name)
    const secLine = lines.find((l) => l.startsWith(`security-object ${name} security protocol-suite `))
    const security = secLine ? secLine.split('protocol-suite ')[1].split(/\s+/)[0] : null
    const vlanLine = lines.find((l) => new RegExp(`^user-profile ${n} .*\\bvlan-id \\d+`).test(l))
    const vlan = vlanLine ? Number(vlanLine.match(/vlan-id (\d+)/)[1]) : null
    const radios = lines
      .filter((l) => new RegExp(`^interface (wifi\\d+) ssid ${n}$`).test(l))
      .map((l) => l.split(/\s+/)[1])
    return { name, security, vlan, radios }
  })
}

/**
 * Parse the hives (mesh profiles) from `show hive`. Each row starts with the hive name followed by its native
 * VLAN, so a name + a number anchors a data row (skipping the header/separator/prompt). -> [{ name, nativeVlan }].
 */
export function parseHives(output) {
  return (output || '')
    .split('\n')
    .map((l) => l.trim())
    .map((l) => l.match(/^(\S+)\s+(\d+)\s+/))
    .filter(Boolean)
    .map((m) => ({ name: m[1], nativeVlan: Number(m[2]) }))
}

const num = (x) => (x !== undefined && x !== '' && !Number.isNaN(Number(x)) ? Number(x) : null)

const LOG_LINE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s+(\S+)\s+(.*)$/

/**
 * Parse `show log buffered` into structured entries. Each entry is "<date> <time> <level>  <message>"; the
 * buffer is newest-first, so the first `limit` parseable lines are the most recent. Non-timestamped lines
 * (wrapped continuations, banners) are skipped. -> [{ time, level, message }].
 */
export function parseLog(output, limit = 120) {
  const rows = []
  for (const line of (output || '').split('\n')) {
    const m = line.match(LOG_LINE)
    if (m) {
      rows.push({ time: m[1], level: m[2], message: m[3].trim() })
      if (rows.length >= limit) break
    }
  }
  return rows
}

// Syslog severities, most severe (0) to least (7). Unknown levels sort as info so a strict filter hides them.
const LEVEL_RANK = { emerg: 0, alert: 1, crit: 2, err: 3, error: 3, warning: 4, warn: 4, notice: 5, info: 6, debug: 7 }
const SEVERITY_MAX = { all: 99, notice: 5, warning: 4, error: 3 }

/**
 * Filter parsed log entries by a minimum severity (all | notice | warning | error -> show that level and
 * anything more severe) and a free-text query (matched against the message and the level). Pure, so the log
 * view can filter ~120 entries without a re-read.
 */
export function filterLog(entries, level = 'all', query = '') {
  const max = SEVERITY_MAX[level] ?? 99
  const q = (query || '').trim().toLowerCase()
  return (entries || []).filter((e) => {
    const rank = LEVEL_RANK[(e.level || '').toLowerCase()] ?? 6
    if (rank > max) return false
    return !q || (e.message || '').toLowerCase().includes(q) || (e.level || '').toLowerCase().includes(q)
  })
}

/**
 * Parse `show capwap client`. The first line reads "CAPWAP client:   Enabled|Disabled" — disabled means the AP
 * is standalone (not phoning home to a cloud controller), which is the whole point of HiveKeeper. -> { known,
 * managed } where `managed` = the AP is still talking to a cloud control plane.
 */
export function parseCapwap(output) {
  const line = (output || '').split('\n').find((l) => /CAPWAP client:/i.test(l))
  return { known: !!line, managed: !!line && /enabled/i.test(line) }
}

/**
 * Parse `show acsp` (the per-radio channel-selection / auto-power table). Each data row starts with the radio
 * name (Wifi0/Wifi1) and carries: channel-select state, primary channel, channel width, power-control state,
 * Tx power (dBm). This is richer than the inventory radio parse (which leaves Tx power null).
 * -> [{ name, channelSelect, channel, width, powerCtrl, txPower }].
 */
export function parseAcsp(output) {
  return (output || '')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => /^wifi\d+\s/i.test(l))
    .map((l) => {
      const t = l.split(/\s+/)
      return {
        name: t[0],
        channelSelect: t[1] || null,
        channel: num(t[2]),
        width: num(t[3]),
        powerCtrl: t[4] || null,
        txPower: num(t[5]),
      }
    })
}
