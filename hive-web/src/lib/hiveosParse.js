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
 * Parse `show reboot schedule`. When a reboot is scheduled the AP prints
 * `Next reboot Scheduled At:<date>  <time>  <weekday>` (followed by a countdown line); when nothing is scheduled
 * the command echoes with no data row. -> { scheduledAt: '<date> <time>', weekday } or null.
 */
export function parseRebootSchedule(output) {
  const m = (output || '').match(/Next reboot Scheduled At:\s*(\S+)\s+(\S+)\s+(\S+)/)
  return m ? { scheduledAt: `${m[1]} ${m[2]}`, weekday: m[3] } : null
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

// HiveOS's sentinel cost for a channel that is not a candidate at all.
export const UNUSABLE_COST = 32767

/**
 * Parse `show acsp channel-info` + `show acsp neighbor` into one scan per radio.
 *
 * The costs are the AP's own. HiveOS runs ACSP, which scans and scores every permitted channel
 * continuously; reading that out beats re-deriving it from a neighbour list, because the AP measured the
 * air and the browser did not. Our job is to surface the number and explain what drove it.
 *
 * A cost of UNUSABLE_COST is a sentinel, not an expensive channel: on 2.4 GHz every channel but 1/6/11 is
 * flagged `overlap`, and on 5 GHz a channel that is the secondary half of a bonded pair is flagged
 * `offset`. Those are channels the radio cannot centre on, so they are excluded rather than ranked.
 *
 * -> [{ iface, state, currentChannel, channels: [{ channel, cost, reason, usable }], neighbors: [...] }]
 */
export function parseChannelScans(channelInfo, neighborOutput) {
  const byRadio = parseAcspNeighbors(neighborOutput)
  const scans = []
  let current = null

  for (const raw of (channelInfo || '').split('\n')) {
    const line = raw.trim()
    const radio = /^(wifi\d+)\s*\(\d+\)\s*:/i.exec(line)
    if (radio) {
      current = {
        iface: radio[1].toLowerCase(),
        state: null,
        currentChannel: null,
        channels: [],
        neighbors: [],
      }
      current.neighbors = byRadio[current.iface] || []
      scans.push(current)
      continue
    }
    if (!current) continue

    const state = /^State:\s*(\S+)/.exec(line)
    if (state) {
      current.state = state[1]
      continue
    }
    const lowest = /^Lowest cost channel:\s*(\d+)/.exec(line)
    if (lowest) {
      current.currentChannel = Number(lowest[1])
      continue
    }
    const ch = /^Channel\s+(\d+)\s+Cost:\s+(\d+)\s*(?:\((\w+)\))?/.exec(line)
    if (ch) {
      const cost = Number(ch[2])
      current.channels.push({
        channel: Number(ch[1]),
        cost,
        reason: ch[3] || null,
        usable: cost < UNUSABLE_COST,
      })
    }
  }
  return scans
}

/**
 * Parse `show acsp neighbor` into { wifi0: [...], wifi1: [...] }.
 *
 * Splitting on whitespace does not work here: the SSID column can contain spaces (a real capture holds
 * `Portaria Zenith`) and can be empty for a hidden network, so the column count varies row to row. Anchor
 * on the two unambiguous fields instead — the BSSID at the start and the numeric channel/RSSI pair — and
 * take whatever sits between as the SSID.
 */
function parseAcspNeighbors(output) {
  const out = {}
  let iface = null
  const row =
    /^([0-9a-f]{4}:[0-9a-f]{4}:[0-9a-f]{4})\s+(\S+)\s+(.*?)\s*(\d+)\s+(-?\d+)\s+(yes|no)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)/i

  for (const raw of (output || '').split('\n')) {
    const line = raw.trim()
    const header = /^(wifi\d+)\(\d+\)\s+ACSP neighbor list/i.exec(line)
    if (header) {
      iface = header[1].toLowerCase()
      out[iface] = out[iface] || []
      continue
    }
    if (!iface) continue
    const m = row.exec(line)
    if (!m) continue
    const ssid = m[3].trim()
    out[iface].push({
      bssid: m[1],
      ssid: ssid || null,
      channel: Number(m[4]),
      rssiDbm: Number(m[5]),
      ourFleet: m[6].toLowerCase() === 'yes',
      // A foreign AP prints `--` for these; keep them null rather than NaN.
      utilization: num(m[7]),
      stations: num(m[9]),
      channelWidth: m[10],
    })
  }
  return out
}

/** The usable channels of a scan, cheapest first. */
export function rankedChannels(scan) {
  return (scan?.channels || [])
    .filter((c) => c.usable)
    .sort((a, b) => a.cost - b.cost || a.channel - b.channel)
}

/**
 * How crowded a channel is: how many neighbours sit on it, and how loud the loudest one is (dBm, closer to
 * zero is louder). Worth showing beside a cost — one loud neighbour and a dozen distant ones can score
 * alike, and they are not the same problem.
 */
export function channelCrowding(scan, channel) {
  const on = (scan?.neighbors || []).filter((n) => n.channel === channel)
  return {
    count: on.length,
    loudestDbm: on.length ? Math.max(...on.map((n) => n.rssiDbm)) : null,
    ourFleet: on.filter((n) => n.ourFleet).length,
  }
}

/**
 * Parse the `radio profile <name> ...` lines out of a running-config into the shape the RadioProfileForm
 * edits — so an adopted AP's existing profiles can be shown and adjusted from their current values rather
 * than applied blind. It is the inverse of `radioProfileCommands`: every knob that builder can emit, this
 * reads back.
 *
 * A bare knob line (`band-steering`, `dfs`, `ampdu`) means enabled; the builder never writes a disabled one
 * into a profile (it emits `no radio profile ...`), so absence is "unchanged/off". Interface bindings
 * (`interface wifiN radio profile <name>`) are collected too, so the form can preselect the bound radio.
 *
 * -> [{ name, phymode, channelWidth, bandSteering, clientLoadBalance, maxClient, dfs, shortGuardInterval,
 *       ampdu, amsdu, frameburst, highDensity, weakSnrSuppress, txBeamforming, receiveChain, transmitChain,
 *       bssColor, ofdmaDl, ofdmaUl, twt, muMimo, boundInterfaces: [] }]
 */
export function parseRadioProfiles(config) {
  const byName = new Map()
  const profile = (name) => {
    if (!byName.has(name)) {
      byName.set(name, { name, boundInterfaces: [] })
    }
    return byName.get(name)
  }
  // Bare toggles: presence = 'enable'. Toggles whose positive form carries an `enable` word are handled by
  // the regex tail below.
  const BARE = {
    'band-steering': 'bandSteering',
    'client-load-balance': 'clientLoadBalance',
    dfs: 'dfs',
    'short-guard-interval': 'shortGuardInterval',
    ampdu: 'ampdu',
    amsdu: 'amsdu',
    frameburst: 'frameburst',
  }

  for (const raw of (config || '').split('\n')) {
    const line = raw.trim()

    const bind = /^interface\s+(wifi\d+)\s+radio\s+profile\s+(\S+)$/.exec(line)
    if (bind) {
      profile(bind[2]).boundInterfaces.push(bind[1])
      continue
    }
    const m = /^radio\s+profile\s+(\S+)(?:\s+(.*))?$/.exec(line)
    if (!m) continue
    const p = profile(m[1])
    const rest = (m[2] || '').trim()
    if (!rest) continue // a bare `radio profile <name>` declaration

    let g
    if ((g = /^phymode\s+(\S+)/.exec(rest))) p.phymode = g[1]
    else if ((g = /^channel-width\s+(\S+)/.exec(rest))) p.channelWidth = g[1]
    else if ((g = /^max-client\s+(\d+)/.exec(rest))) p.maxClient = g[1]
    else if ((g = /^tx-beamforming\s+(\S+)/.exec(rest))) p.txBeamforming = g[1]
    else if ((g = /^receive-chain\s+(\d+)/.exec(rest))) p.receiveChain = g[1]
    else if ((g = /^transmit-chain\s+(\d+)/.exec(rest))) p.transmitChain = g[1]
    else if (/^high-density\s+enable/.test(rest)) p.highDensity = 'enable'
    else if (/^weak-snr-suppress\s+enable/.test(rest)) p.weakSnrSuppress = 'enable'
    else if ((g = /^11ax\s+bss-color\s+(\d+)/.exec(rest))) p.bssColor = g[1]
    else if (/^11ax\s+ofdma-dl/.test(rest)) p.ofdmaDl = 'enable'
    else if (/^11ax\s+ofdma-ul/.test(rest)) p.ofdmaUl = 'enable'
    else if (/^11ax\s+twt/.test(rest)) p.twt = 'enable'
    else if (/^mu-mimo\s+enable/.test(rest)) p.muMimo = 'enable'
    else if (BARE[rest]) p[BARE[rest]] = 'enable'
  }
  return [...byName.values()]
}
