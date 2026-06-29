/**
 * HiveOS CLI line builders for the guided config forms. The exact grammar was confirmed live against an AP230
 * (HiveOS 10.6r1a) using `?` context help (see scripts/hk-cli-explore.py) so these are accurate, not guessed.
 * Lines are dispatched through the gateway's apply-config endpoint (Command.ApplyConfig). hive-core stays
 * vendor-agnostic; this UI-side knowledge can later move into a driver generator for multi-vendor support.
 */

/**
 * Radio settings for one radio interface (wifi0 = 2.4 GHz, wifi1 = 5 GHz). Blank fields are left unchanged.
 * `tx-power-control` is the client target power (1-20|auto) — distinct from `power` (the AP's own TX power); it
 * addresses AP<->client asymmetry / sticky clients. Channel width and the density knobs are NOT here — they live
 * on the named radio profile (see radioProfileCommands).
 */
export function radioCommands(iface, { channel, power, mode, txPowerControl } = {}) {
  const cmds = []
  if (channel) cmds.push(`interface ${iface} radio channel ${channel}`)
  if (power) cmds.push(`interface ${iface} radio power ${power}`)
  if (txPowerControl) cmds.push(`interface ${iface} radio tx-power-control ${txPowerControl}`)
  if (mode) cmds.push(`interface ${iface} mode ${mode}`)
  return cmds
}

/**
 * Named radio-profile tuning (HiveOS keeps channel width and the density knobs on the profile a wifiN interface
 * references, not on the interface itself — defaults `radio_ng0` for 2.4 GHz, `radio_ac0` for 5 GHz). Confirmed
 * grammar: `radio profile <name> channel-width 20|40|80`, `radio profile <name> band-steering`,
 * `radio profile <name> client-load-balance`, `radio profile <name> max-client <n>`. band-steering and
 * client-load-balance are toggles ('enable'|'disable'|'' unchanged), the latter emitting the `no ...` negation.
 * BLAST RADIUS: a profile may be shared across interfaces/APs, so a change here is wider than a per-interface
 * channel/power tweak — the caller should surface which profile a radio uses.
 */
export function radioProfileCommands(profile, { channelWidth, bandSteering, clientLoadBalance, maxClient } = {}) {
  const p = (profile || '').trim()
  if (!p) return []
  const cmds = []
  if (channelWidth) cmds.push(`radio profile ${p} channel-width ${channelWidth}`)
  if (bandSteering === 'enable') cmds.push(`radio profile ${p} band-steering`)
  if (bandSteering === 'disable') cmds.push(`no radio profile ${p} band-steering`)
  if (clientLoadBalance === 'enable') cmds.push(`radio profile ${p} client-load-balance`)
  if (clientLoadBalance === 'disable') cmds.push(`no radio profile ${p} client-load-balance`)
  if (maxClient) cmds.push(`radio profile ${p} max-client ${maxClient}`)
  return cmds
}

// Data-rate ladders (Mbps, ascending) confirmed live on the AP230. 11g (2.4 GHz) includes the slow 802.11b
// rates; 11a (5 GHz) starts at 6. Fractional 5.5 is kept as a number and stringifies to "5.5".
const RATES_11G = [1, 2, 5.5, 6, 9, 11, 12, 18, 24, 36, 48, 54]
const RATES_11A = [6, 9, 12, 18, 24, 36, 48, 54]

/**
 * Prune slow basic rates by setting a minimum data rate on an SSID (a large high-density airtime win). HiveOS
 * grammar confirmed live: `ssid <name> 11g-rate-set <rate>[-basic] [<rate>[-basic] ...]` (2.4 GHz) /
 * `11a-rate-set` (5 GHz) — one line, ascending Mbps, the lowest token marked `-basic` (mandatory) and the rest
 * optional. Every rate below the minimum is left out, so it is removed from the air entirely (1/2/5.5/11 Mbps
 * 11b clients can no longer associate at those rates). band: '2.4' | '5'. Nothing without ssid+band+minRate.
 */
export function minRateCommands(ssid, { band, minRate } = {}) {
  const name = (ssid || '').trim()
  const min = Number(minRate)
  if (!name || !Number.isFinite(min)) return []
  const ladder = band === '2.4' ? RATES_11G : band === '5' ? RATES_11A : null
  if (!ladder) return []
  const kept = ladder.filter((r) => r >= min)
  if (kept.length === 0) return []
  const keyword = band === '2.4' ? '11g-rate-set' : '11a-rate-set'
  const tokens = kept.map((r, i) => (i === 0 ? `${r}-basic` : `${r}`))
  return [`ssid ${name} ${keyword} ${tokens.join(' ')}`]
}

/**
 * Per-SSID hardening / tuning. Grammar confirmed live on an AP230 (HiveOS 10.6r1a) via `?`:
 *   `ssid <n> hide-ssid` (toggle), `ssid <n> max-client <1-255>`, `ssid <n> inter-station-traffic` (toggle,
 *   default Enabled = clients can talk to each other), `ssid <n> dtim-period <1-255>`, `ssid <n> schedule <name>`,
 *   `ssid <n> rrm enable` (802.11k neighbor reports), `ssid <n> wnm enable` (802.11v BSS transition).
 * Toggle fields take 'enable' | 'disable' | '' (unchanged). NOTE: client isolation is the inverse of
 * inter-station-traffic — isolation 'enable' emits `no ssid <n> inter-station-traffic` (deny peer traffic).
 * Blank/absent fields emit nothing; no SSID name means nothing to do. Dispatched through apply-config.
 */
export function ssidHardeningCommands(ssid, { hideSsid, maxClient, clientIsolation, dtimPeriod, schedule, rrm, wnm } = {}) {
  const name = (ssid || '').trim()
  if (!name) return []
  const cmds = []
  if (hideSsid === 'enable') cmds.push(`ssid ${name} hide-ssid`)
  if (hideSsid === 'disable') cmds.push(`no ssid ${name} hide-ssid`)
  if (maxClient) cmds.push(`ssid ${name} max-client ${maxClient}`)
  // Client isolation is the negation of inter-station-traffic (default permitted).
  if (clientIsolation === 'enable') cmds.push(`no ssid ${name} inter-station-traffic`)
  if (clientIsolation === 'disable') cmds.push(`ssid ${name} inter-station-traffic`)
  if (dtimPeriod) cmds.push(`ssid ${name} dtim-period ${dtimPeriod}`)
  if (schedule && schedule.trim()) cmds.push(`ssid ${name} schedule ${schedule.trim()}`)
  if (rrm === 'enable') cmds.push(`ssid ${name} rrm enable`)
  if (wnm === 'enable') cmds.push(`ssid ${name} wnm enable`)
  return cmds
}

/**
 * Private PSK (PPSK). HiveKeeper does NOT mint individual per-user keys — HiveOS has no running-config grammar to
 * create a key (confirmed live: `… private-psk user …` is rejected). Instead a HiveAP itself acts as the PPSK
 * server and hosts a self-registration web portal: users enrol (optionally authenticated against RADIUS) and the
 * AP issues + stores the key locally (in users.txt, which the backup captures). This builder configures that
 * model. All grammar `?`-confirmed on the AP230:
 *   `[no] security-object <so> security private-psk` (enable PPSK mode on the security object),
 *   `… private-psk external-server` (look up keys on an external PPSK server instead of this AP),
 *   `… private-psk default-psk-disabled` (refuse the default PSK — require a private key),
 *   `… private-psk ppsk-server <ip>` (the mgt0 IP of the HiveAP that serves PPSK — point members at it),
 *   `security-object <so> ppsk-web-server` (enable the self-registration portal; `… https`, `… web-directory <d>`,
 *   `… auth-user` to authenticate registrants against RADIUS first),
 *   `ssid <so> user-group <group>` (bind the SSID to a PPSK user-group).
 * In HiveKeeper a security object and its SSID share a name, so `so` is the SSID name. Toggles take
 * 'enable'|'disable'|'' (unchanged); blanks emit nothing; no name means nothing to do.
 */
export function ppskCommands(
  securityObject,
  { enable, externalServer, defaultPskDisabled, ppskServer, webServer, webHttps, webDirectory, authUser, userGroup } = {},
) {
  const so = (securityObject || '').trim()
  if (!so) return []
  const cmds = []
  if (enable === 'enable') cmds.push(`security-object ${so} security private-psk`)
  if (enable === 'disable') cmds.push(`no security-object ${so} security private-psk`)
  if (externalServer === 'enable') cmds.push(`security-object ${so} security private-psk external-server`)
  if (defaultPskDisabled === 'enable') cmds.push(`security-object ${so} security private-psk default-psk-disabled`)
  if (ppskServer && ppskServer.trim()) cmds.push(`security-object ${so} security private-psk ppsk-server ${ppskServer.trim()}`)
  if (webServer === 'enable') cmds.push(`security-object ${so} ppsk-web-server`)
  if (webServer === 'disable') cmds.push(`no security-object ${so} ppsk-web-server`)
  if (webHttps === 'enable') cmds.push(`security-object ${so} ppsk-web-server https`)
  if (webDirectory && webDirectory.trim()) cmds.push(`security-object ${so} ppsk-web-server web-directory ${webDirectory.trim()}`)
  if (authUser === 'enable') cmds.push(`security-object ${so} ppsk-web-server auth-user`)
  if (userGroup && userGroup.trim()) cmds.push(`ssid ${so} user-group ${userGroup.trim()}`)
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

/**
 * Captive web portal on a security object. Confirmed grammar: `security-object <so> web-server` enables the
 * AP's internal splash server (with `web-server port <n>` / `web-server ssl`), `security-object <so>
 * web-directory <dir>` picks the uploaded page set, and `security-object <so> walled-garden
 * ip-address|hostname <v>` lets unauthenticated clients reach specific hosts before they log in. An entry that
 * looks like an IPv4 address uses ip-address, otherwise hostname. Bind the security object to an SSID in the
 * Wi-Fi tab. Nothing without a security object name.
 */
export function captivePortalCommands(securityObject, { webServer, port, ssl, webDirectory, walledGarden = [] } = {}) {
  const so = (securityObject || '').trim()
  if (!so) return []
  const cmds = []
  if (webServer) cmds.push(`security-object ${so} web-server`)
  if (port && String(port).trim()) cmds.push(`security-object ${so} web-server port ${String(port).trim()}`)
  if (ssl) cmds.push(`security-object ${so} web-server ssl`)
  if (webDirectory && webDirectory.trim()) cmds.push(`security-object ${so} web-directory ${webDirectory.trim()}`)
  for (const raw of walledGarden) {
    const v = (raw || '').trim()
    if (!v) continue
    const isIp = /^\d{1,3}(\.\d{1,3}){3}/.test(v)
    cmds.push(`security-object ${so} walled-garden ${isIp ? 'ip-address' : 'hostname'} ${v}`)
  }
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

/**
 * Status LED control. Confirmed grammar: `system led brightness <bright|off>` (off turns the LEDs off) and
 * `system led power-saving-mode` / `no system led power-saving-mode` to enable/disable the power-saving dimming.
 * powerSaving is 'enable' | 'disable' | '' (leave unchanged). Blank fields emit nothing.
 */
export function ledCommands({ brightness, powerSaving } = {}) {
  const cmds = []
  if (brightness) cmds.push(`system led brightness ${brightness}`)
  if (powerSaving === 'enable') cmds.push('system led power-saving-mode')
  if (powerSaving === 'disable') cmds.push('no system led power-saving-mode')
  return cmds
}
