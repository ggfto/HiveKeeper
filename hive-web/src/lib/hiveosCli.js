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

/** Create/join a hive (mesh) and bind it to the chosen interfaces. Confirmed grammar: `hive <name>`,
 *  `hive <name> password <pwd>`, `interface <iface> hive <name>` (iface = mgt0 control, wifi0/wifi1 backhaul). */
export function meshCommands({ name, password, interfaces = [] } = {}) {
  const cmds = [`hive ${name}`]
  if (password) cmds.push(`hive ${name} password ${password}`)
  for (const iface of interfaces) cmds.push(`interface ${iface} hive ${name}`)
  return cmds
}

/**
 * Advanced per-hive tuning. Confirmed grammar: `hive <name> frag-threshold <256-2346>`,
 * `hive <name> rts-threshold <1-2346>`, `hive <name> neighbor connecting-threshold <-90..-55 | high|medium|low>`
 * (the minimum signal strength to link a neighboring mesh member). Blank fields are left unchanged; no hive
 * name means nothing to tune.
 */
export function hiveTuningCommands(name, { fragThreshold, rtsThreshold, connectingThreshold } = {}) {
  const trimmed = (name || '').trim()
  if (!trimmed) return []
  const cmds = []
  if (fragThreshold) cmds.push(`hive ${trimmed} frag-threshold ${fragThreshold}`)
  if (rtsThreshold) cmds.push(`hive ${trimmed} rts-threshold ${rtsThreshold}`)
  if (connectingThreshold) cmds.push(`hive ${trimmed} neighbor connecting-threshold ${connectingThreshold}`)
  return cmds
}

/** Cloud (CAPWAP) connection: standalone cuts the link to HiveManager / ExtremeCloud IQ. */
export function capwapCommands(connected) {
  return [connected ? 'capwap client enable' : 'no capwap client enable']
}

/**
 * Management (mgt0) network settings. Confirmed grammar: `interface mgt0 ip <ip/netmask>`,
 * `interface mgt0 vlan <id>`, `interface mgt0 native-vlan <id>`, `ip route default gateway <ip>`,
 * `[no] interface mgt0 dhcp client`. Only set fields emit a line. DANGER: any of these can drop the AP's
 * connectivity, so the caller must warn + confirm.
 */
export function managementCommands({ ip, netmask, vlan, nativeVlan, gateway, dhcp } = {}) {
  const cmds = []
  if (ip) cmds.push(netmask ? `interface mgt0 ip ${ip}/${netmask}` : `interface mgt0 ip ${ip}`)
  if (vlan) cmds.push(`interface mgt0 vlan ${vlan}`)
  if (nativeVlan) cmds.push(`interface mgt0 native-vlan ${nativeVlan}`)
  if (gateway) cmds.push(`ip route default gateway ${gateway}`)
  if (dhcp === 'enable') cmds.push('interface mgt0 dhcp client')
  if (dhcp === 'disable') cmds.push('no interface mgt0 dhcp client')
  return cmds
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
