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
