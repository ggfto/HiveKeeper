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
