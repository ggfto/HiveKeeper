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
 * Parse the user profiles (and their SSID bindings) from a `show running-config`. HiveOS coalesces a profile's
 * settings onto one line — e.g. `user-profile HK-JOB qos-policy def-user-qos vlan-id 7 attribute 7` — so each
 * profile is one `user-profile <name> <key> <value> ...` line; we still merge across lines defensively. A
 * security object binds a profile as its default via `security-object <so> default-user-profile-attr <attr>`,
 * matched back to the profile by its numeric attribute. -> [{ name, attribute, vlanId, vlanGroup, qosPolicy,
 * schedule, boundTo: [<so names>] }], sorted by attribute then name.
 */
export function parseUserProfiles(config) {
  const lines = (config || '')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)

  // attribute -> [security-object names that default to it]
  const binds = {}
  for (const l of lines) {
    const m = l.match(/^security-object (\S+) default-user-profile-attr (\d+)$/)
    if (m) (binds[m[2]] ||= []).push(m[1])
  }

  const KEYS = { attribute: 'attribute', 'vlan-id': 'vlanId', 'vlan-group': 'vlanGroup', 'qos-policy': 'qosPolicy', schedule: 'schedule' }
  const byName = new Map()
  for (const l of lines) {
    const m = l.match(/^user-profile (\S+)\s+(.*)$/)
    if (!m) continue
    const name = m[1]
    const prof = byName.get(name) || { name, attribute: null, vlanId: null, vlanGroup: null, qosPolicy: null, schedule: null }
    const tokens = m[2].split(/\s+/)
    for (let i = 0; i < tokens.length - 1; i += 2) {
      const field = KEYS[tokens[i]]
      if (!field) continue
      const raw = tokens[i + 1]
      prof[field] = field === 'attribute' || field === 'vlanId' ? num(raw) : raw
    }
    byName.set(name, prof)
  }

  return [...byName.values()]
    .map((p) => ({ ...p, boundTo: p.attribute !== null ? binds[String(p.attribute)] || [] : [] }))
    .sort((a, b) => (a.attribute ?? 1e9) - (b.attribute ?? 1e9) || a.name.localeCompare(b.name))
}

/**
 * Parse static IP routes from a `show running-config`. Lines look like `ip route net <ip> <mask> gateway <gw>
 * [metric <m>]`, `ip route host <ip> gateway <gw> [metric <m>]`, or `ip route default gateway <gw>`. ->
 * [{ type: 'net'|'host'|'default', dest, netmask, gateway, metric }], dest/netmask null where not applicable.
 */
export function parseStaticRoutes(config) {
  const routes = []
  for (const raw of (config || '').split('\n')) {
    const l = raw.trim()
    const m = l.match(/^ip route (net|host|default)\s+(.*)$/)
    if (!m) continue
    const type = m[1]
    const rest = m[2]
    const gw = rest.match(/gateway (\S+)/)
    const metric = rest.match(/metric (\d+)/)
    const route = { type, dest: null, netmask: null, gateway: gw ? gw[1] : null, metric: metric ? num(metric[1]) : null }
    if (type === 'net') {
      const t = rest.match(/^(\S+)\s+(\S+)/)
      if (t) {
        route.dest = t[1]
        route.netmask = t[2]
      }
    } else if (type === 'host') {
      const t = rest.match(/^(\S+)/)
      if (t) route.dest = t[1]
    }
    routes.push(route)
  }
  return routes
}

/**
 * Parse the named firewall policies from a `show running-config` so the user-profile binding can offer real
 * names. `ip-policy <name> …` / `mac-policy <name> …` declare a policy (the bare line creates it, rule lines
 * repeat the name). -> { ip: [names], mac: [names] }, each de-duplicated and sorted.
 */
export function parseFirewallPolicies(config) {
  const ip = new Set()
  const mac = new Set()
  for (const raw of (config || '').split('\n')) {
    const l = raw.trim()
    let m = l.match(/^ip-policy (\S+)/)
    if (m) ip.add(m[1])
    m = l.match(/^mac-policy (\S+)/)
    if (m) mac.add(m[1])
  }
  return { ip: [...ip].sort(), mac: [...mac].sort() }
}

/**
 * Parse the QoS policy names from a `show running-config` (`qos policy <name> …`). -> [names], de-duplicated and
 * sorted, so a user profile can reference an existing rate-limit policy by name.
 */
export function parseQosPolicies(config) {
  const names = new Set()
  for (const raw of (config || '').split('\n')) {
    const m = raw.trim().match(/^qos policy (\S+)/)
    if (m) names.add(m[1])
  }
  return [...names].sort()
}

/**
 * Parse the named schedule objects from a `show running-config`. A schedule line is
 * `schedule <name> recurrent …` or `schedule <name> once …`; an `ssid <n> schedule <name>` reference is NOT a
 * schedule definition and is skipped (the `recurrent|once` keyword anchors a real definition). ->
 * [{ name, type: 'recurrent'|'once', detail }] in config order, where `detail` is the trailing range text.
 */
export function parseSchedules(config) {
  const out = []
  for (const raw of (config || '').split('\n')) {
    const m = raw.trim().match(/^schedule (\S+) (recurrent|once)\b\s*(.*)$/)
    if (m) out.push({ name: m[1], type: m[2], detail: m[3].trim() })
  }
  return out
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
