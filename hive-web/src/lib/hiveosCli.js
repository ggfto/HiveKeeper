/**
 * HiveOS CLI line builders for the guided config forms. The exact grammar was confirmed live against an AP230
 * (HiveOS 10.6r1a) using `?` context help (see scripts/hk-cli-explore.py) so these are accurate, not guessed.
 * Lines are dispatched through the gateway's apply-config endpoint (Command.ApplyConfig). hive-core stays
 * vendor-agnostic; this UI-side knowledge can later move into a driver generator for multi-vendor support.
 */

/** Radio settings for one radio interface (wifi0 = 2.4 GHz, wifi1 = 5 GHz). Blank fields are left unchanged. */
export function radioCommands(iface, { channel, power, mode } = {}) {
  const cmds = []
  if (channel) cmds.push(`interface ${iface} radio channel ${channel}`)
  if (power) cmds.push(`interface ${iface} radio power ${power}`)
  if (mode) cmds.push(`interface ${iface} mode ${mode}`)
  return cmds
}

/** Set the AP hostname (1-32 chars). */
export function hostnameCommands(name) {
  return [`hostname ${name}`]
}

/** Cloud (CAPWAP) connection: standalone cuts the link to HiveManager / ExtremeCloud IQ. */
export function capwapCommands(connected) {
  return [connected ? 'capwap client enable' : 'no capwap client enable']
}

/** Set the mgt0 management IP. Confirmed grammar: `interface mgt0 ip <ip_addr/netmask>` (or bare ip).
 *  DANGER: changing this drops the AP's connectivity; the caller must warn + re-adopt at the new IP. */
export function mgtIpCommands(ip, netmask) {
  return [netmask ? `interface mgt0 ip ${ip}/${netmask}` : `interface mgt0 ip ${ip}`]
}

/** Device-level CLIENT mode: the AP stops serving and associates with another AP using an existing SSID
 *  profile. Confirmed: `client-mode ssid <profile>` + `client-mode connect`. DANGER: the AP may drop off the
 *  LAN — the caller must warn + confirm. */
export function clientModeConnectCommands(profile) {
  return [`client-mode ssid ${profile}`, 'client-mode connect']
}

/** Revert the device from client mode back to normal AP operation. Confirmed: `no client-mode connect`. */
export function clientModeDisconnectCommands() {
  return ['no client-mode connect']
}
