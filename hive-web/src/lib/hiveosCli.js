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
 *
 * Advanced (Phase 4) interface-level knobs, all confirmed live on the AP230:
 *   `rx-sop <number|high|low|medium>` — receiver start-of-packet detection threshold (dBm or a density preset).
 *   `ed-threshold <-70..-50>` — energy-detect threshold in dBm.
 *   `dfs-backup-channel <freq|channel>` — fallback channel when DFS forces a radar avoidance switch (5 GHz).
 */
export function radioCommands(
  iface,
  { channel, power, mode, txPowerControl, rxSop, edThreshold, dfsBackupChannel } = {},
) {
  const cmds = []
  if (channel) cmds.push(`interface ${iface} radio channel ${channel}`)
  if (power) cmds.push(`interface ${iface} radio power ${power}`)
  if (txPowerControl) cmds.push(`interface ${iface} radio tx-power-control ${txPowerControl}`)
  if (mode) cmds.push(`interface ${iface} mode ${mode}`)
  if (rxSop) cmds.push(`interface ${iface} radio rx-sop ${rxSop}`)
  if (edThreshold) cmds.push(`interface ${iface} radio ed-threshold ${edThreshold}`)
  if (dfsBackupChannel) cmds.push(`interface ${iface} radio dfs-backup-channel ${dfsBackupChannel}`)
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
 *
 * Advanced (Phase 4) profile-level knobs for dense RF, all confirmed live on the AP230. Toggles take
 * 'enable'|'disable'|'' (unchanged), 'disable' emitting the `no ...` negation:
 *   `dfs`, `short-guard-interval`, `ampdu`, `amsdu`, `frameburst` — bare-line toggles.
 *   `high-density` / `weak-snr-suppress` — toggles whose positive form carries the `enable` sub-word, so the
 *     negation is `no radio profile <p> <knob> enable`.
 *   `tx-beamforming auto|explicit-only` (with `no ...` to disable), `phymode 11a|11ac|11b/g|11na|11ng`,
 *   `receive-chain`/`transmit-chain <1-3>`.
 *
 * LIVE QUIRKS confirmed on the AP230 (the order below is deliberate, not cosmetic):
 *   - HiveOS REFUSES to edit a DEFAULT profile ("can't configure default radio profile radio_ac0!") — these
 *     knobs only apply to a NON-default (custom) profile, which the first knob line auto-creates. The caller
 *     warns when the chosen name is a default (see RadioProfileForm).
 *   - `phymode` must be set BEFORE `channel-width` and `tx-beamforming` — a fresh profile is 11b/g, and both
 *     reject as "inconsistent with current phymode" / "incompatible PHY mode" until phymode matches. So phymode
 *     is emitted first.
 *
 * `bindInterface` (e.g. 'wifi1') appends `interface <iface> radio profile <p>` as the LAST line — this is how a
 * custom profile takes effect on a radio (the knobs above auto-create it first). Confirmed live on the AP230
 * (`interface wifi1 radio profile <name>`). Binding swaps the radio's whole profile and briefly disrupts its
 * wireless clients — the caller warns. Match the profile's band to the radio (5 GHz = wifi1, 2.4 GHz = wifi0).
 */
export function radioProfileCommands(
  profile,
  {
    channelWidth,
    bandSteering,
    clientLoadBalance,
    maxClient,
    dfs,
    shortGuardInterval,
    ampdu,
    amsdu,
    frameburst,
    txBeamforming,
    highDensity,
    weakSnrSuppress,
    phymode,
    receiveChain,
    transmitChain,
    bindInterface,
  } = {},
) {
  const p = (profile || '').trim()
  if (!p) return []
  const cmds = []
  // phymode first: channel-width and tx-beamforming reject until the PHY mode matches (confirmed live).
  if (phymode) cmds.push(`radio profile ${p} phymode ${phymode}`)
  if (channelWidth) cmds.push(`radio profile ${p} channel-width ${channelWidth}`)
  if (bandSteering === 'enable') cmds.push(`radio profile ${p} band-steering`)
  if (bandSteering === 'disable') cmds.push(`no radio profile ${p} band-steering`)
  if (clientLoadBalance === 'enable') cmds.push(`radio profile ${p} client-load-balance`)
  if (clientLoadBalance === 'disable') cmds.push(`no radio profile ${p} client-load-balance`)
  if (maxClient) cmds.push(`radio profile ${p} max-client ${maxClient}`)
  // Bare-line toggles: positive enables, `no ...` disables.
  for (const [val, knob] of [
    [dfs, 'dfs'],
    [shortGuardInterval, 'short-guard-interval'],
    [ampdu, 'ampdu'],
    [amsdu, 'amsdu'],
    [frameburst, 'frameburst'],
  ]) {
    if (val === 'enable') cmds.push(`radio profile ${p} ${knob}`)
    if (val === 'disable') cmds.push(`no radio profile ${p} ${knob}`)
  }
  // Toggles whose positive form carries an `enable` sub-word (so does its negation).
  for (const [val, knob] of [
    [highDensity, 'high-density'],
    [weakSnrSuppress, 'weak-snr-suppress'],
  ]) {
    if (val === 'enable') cmds.push(`radio profile ${p} ${knob} enable`)
    if (val === 'disable') cmds.push(`no radio profile ${p} ${knob} enable`)
  }
  if (txBeamforming === 'auto' || txBeamforming === 'explicit-only')
    cmds.push(`radio profile ${p} tx-beamforming ${txBeamforming}`)
  if (txBeamforming === 'disable') cmds.push(`no radio profile ${p} tx-beamforming`)
  if (receiveChain) cmds.push(`radio profile ${p} receive-chain ${receiveChain}`)
  if (transmitChain) cmds.push(`radio profile ${p} transmit-chain ${transmitChain}`)
  // Bind LAST: the profile must exist/be configured before a radio adopts it (the knobs above auto-create it).
  if (bindInterface) cmds.push(`interface ${bindInterface} radio profile ${p}`)
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
 * User profile (the HiveOS policy a client lands in: default VLAN, QoS, schedule, L2/L3 firewall). Confirmed
 * live on an AP230 (HiveOS 10.6r1a): a profile is keyed by a numeric `attribute` (0-4095) and each setting is
 * its OWN line — HiveOS REJECTS a combined `user-profile <n> attribute 99 vlan-id 99` (verified: it errors at
 * the second keyword), even though `show running-config` later coalesces them onto one line. So we emit:
 *   `user-profile <name> attribute <0-4095>`  (creates/keys the profile — always first),
 *   `user-profile <name> vlan-id <1-4094>`     (the default VLAN) OR
 *   `user-profile <name> vlan-group <name>`    (a VLAN group instead of a single VLAN — mutually exclusive),
 *   `user-profile <name> qos-policy <name>`    (reference an existing QoS policy by name),
 *   `user-profile <name> schedule <name>`      (only apply the profile during a named schedule).
 * The numeric attribute is what an SSID/security-object binds to (see bindUserProfileCommands) and what RADIUS
 * returns to select a profile. A blank name (or a non-numeric attribute) yields nothing. Dispatched through
 * apply-config. `vlanId` wins if both a VLAN id and a VLAN group are somehow supplied.
 */
export function userProfileCommands(name, { attribute, vlanId, vlanGroup, qosPolicy, schedule } = {}) {
  const n = (name || '').trim()
  const attr = String(attribute ?? '').trim()
  if (!n || !/^\d+$/.test(attr)) return []
  const cmds = [`user-profile ${n} attribute ${attr}`]
  const vid = String(vlanId ?? '').trim()
  const vgroup = (vlanGroup || '').trim()
  if (vid) cmds.push(`user-profile ${n} vlan-id ${vid}`)
  else if (vgroup) cmds.push(`user-profile ${n} vlan-group ${vgroup}`)
  if (qosPolicy && qosPolicy.trim()) cmds.push(`user-profile ${n} qos-policy ${qosPolicy.trim()}`)
  if (schedule && schedule.trim()) cmds.push(`user-profile ${n} schedule ${schedule.trim()}`)
  return cmds
}

/**
 * Bind a user profile to an SSID's security object as the DEFAULT profile applied to its traffic. Confirmed
 * live: `security-object <so> default-user-profile-attr <attribute>` (the running-config shows exactly this for
 * a bound SSID). In HiveKeeper a security object and its SSID share a name, so `so` is the SSID name. The
 * attribute must match a user profile's `attribute` (see userProfileCommands). Nothing without both.
 */
export function bindUserProfileCommands(securityObject, attribute) {
  const so = (securityObject || '').trim()
  const attr = String(attribute ?? '').trim()
  if (!so || !/^\d+$/.test(attr)) return []
  return [`security-object ${so} default-user-profile-attr ${attr}`]
}

/**
 * A named schedule object (reused by an SSID's `schedule <name>` and a user-profile's `schedule <name>` to gate
 * when each applies). Grammar confirmed live on an AP230 (HiveOS 10.6r1a) — both forms applied to the
 * running-config and reverted:
 *   `schedule <name> recurrent [date-range <d> [to <d>]] [weekday-range <Day> [to <Day>]] [time-range <hh:mm> to <hh:mm>]`
 *   `schedule <name> once <date> <time> to <date> <time>`
 * Weekdays are capitalised (Monday…Sunday), dates yyyy-mm-dd, times hh:mm. A recurrent schedule needs at least
 * one sub-range to be meaningful (a bare `schedule <n> recurrent` would mean "always", which we refuse). A
 * one-time schedule needs all four of start date/time + end date/time. A half time-range (only one bound) is
 * dropped — HiveOS requires `time-range <start> to <end>`. No name → nothing. Dispatched through apply-config.
 */
export function scheduleCommands(
  name,
  { type = 'recurrent', dateStart, dateEnd, weekdayStart, weekdayEnd, timeStart, timeEnd } = {},
) {
  const n = (name || '').trim()
  if (!n) return []
  const ds = (dateStart || '').trim()
  const de = (dateEnd || '').trim()
  const ts = (timeStart || '').trim()
  const te = (timeEnd || '').trim()

  if (type === 'once') {
    if (!ds || !ts || !de || !te) return []
    return [`schedule ${n} once ${ds} ${ts} to ${de} ${te}`]
  }

  const parts = []
  if (ds) parts.push(`date-range ${ds}${de ? ` to ${de}` : ''}`)
  const ws = (weekdayStart || '').trim()
  const we = (weekdayEnd || '').trim()
  if (ws) parts.push(`weekday-range ${ws}${we ? ` to ${we}` : ''}`)
  if (ts && te) parts.push(`time-range ${ts} to ${te}`)
  if (parts.length === 0) return []
  return [`schedule ${n} recurrent ${parts.join(' ')}`]
}

/** Remove a schedule object. Confirmed live: `no schedule <name>` (clears it from running-config). */
export function removeScheduleCommands(name) {
  const n = (name || '').trim()
  return n ? [`no schedule ${n}`] : []
}

/** Remove a user profile entirely. Confirmed live: `no user-profile <name>` (clears it from running-config). */
export function removeUserProfileCommands(name) {
  const n = (name || '').trim()
  return n ? [`no user-profile ${n}`] : []
}

/**
 * Advanced user-profile policy: a per-user rate limit (via a QoS policy), a guaranteed-bandwidth SLA
 * (performance-sentinel), L2/L3 firewall bindings + default actions, and a QoS marker-map. All grammar confirmed
 * live on an AP230, each setting its own line:
 *   `user-profile <n> performance-sentinel enable` / `… guaranteed-bandwidth <100-500000>` (kbps) /
 *     `… action boost|log`,
 *   `user-profile <n> ip-policy-default-action permit|deny|inter-station-traffic-drop`,
 *   `user-profile <n> security ip-policy from-access|to-access <ip-policy-name>`,
 *   `user-profile <n> mac-policy-default-action permit|deny`,
 *   `user-profile <n> security mac-policy from-access|to-access <mac-policy-name>`,
 *   `user-profile <n> qos-marker-map 8021p|diffserv <marker-map-name>`.
 * The firewall/marker-map references point at objects defined elsewhere (ip-policy/mac-policy/qos marker-map).
 * Toggles take 'enable'|'disable'|''; blanks emit nothing; no profile name means nothing to do.
 */
export function userProfilePolicyCommands(
  name,
  {
    perfSentinel,
    guaranteedBandwidth,
    perfAction,
    ipDefaultAction,
    ipPolicyFrom,
    ipPolicyTo,
    macDefaultAction,
    macPolicyFrom,
    macPolicyTo,
    qosMarkerMapType,
    qosMarkerMap,
  } = {},
) {
  const n = (name || '').trim()
  if (!n) return []
  const cmds = []
  if (perfSentinel === 'enable') cmds.push(`user-profile ${n} performance-sentinel enable`)
  if (perfSentinel === 'disable') cmds.push(`no user-profile ${n} performance-sentinel enable`)
  const bw = String(guaranteedBandwidth ?? '').trim()
  if (bw) cmds.push(`user-profile ${n} performance-sentinel guaranteed-bandwidth ${bw}`)
  if (perfAction) cmds.push(`user-profile ${n} performance-sentinel action ${perfAction}`)
  if (ipDefaultAction) cmds.push(`user-profile ${n} ip-policy-default-action ${ipDefaultAction}`)
  if (ipPolicyFrom && ipPolicyFrom.trim()) cmds.push(`user-profile ${n} security ip-policy from-access ${ipPolicyFrom.trim()}`)
  if (ipPolicyTo && ipPolicyTo.trim()) cmds.push(`user-profile ${n} security ip-policy to-access ${ipPolicyTo.trim()}`)
  if (macDefaultAction) cmds.push(`user-profile ${n} mac-policy-default-action ${macDefaultAction}`)
  if (macPolicyFrom && macPolicyFrom.trim()) cmds.push(`user-profile ${n} security mac-policy from-access ${macPolicyFrom.trim()}`)
  if (macPolicyTo && macPolicyTo.trim()) cmds.push(`user-profile ${n} security mac-policy to-access ${macPolicyTo.trim()}`)
  if (qosMarkerMap && qosMarkerMap.trim()) {
    const type = qosMarkerMapType === 'diffserv' ? 'diffserv' : '8021p'
    cmds.push(`user-profile ${n} qos-marker-map ${type} ${qosMarkerMap.trim()}`)
  }
  return cmds
}

/**
 * IP firewall policy. Confirmed live on an AP230: the policy GROUP must exist before a rule is added —
 * `ip-policy <name>` creates the (empty) group, then a rule is ONE line
 * `ip-policy <name> id <n> from <src> to <dst> service <svc> action <permit|deny|nat|redirect|inter-station-traffic-drop>`
 * (the combined line is accepted once the group exists; `any` is a valid from/to/service). `from`/`to`/`service`
 * default to `any` when blank. Pass `remove: true` for `no ip-policy <name>`. The rule is emitted only when an id
 * and an action are given; the create line is always emitted (idempotent) so a first rule works.
 */
export function ipPolicyCommands(name, { id, from, to, service, action, remove } = {}) {
  const n = (name || '').trim()
  if (!n) return []
  if (remove) return [`no ip-policy ${n}`]
  const cmds = [`ip-policy ${n}`]
  const rid = String(id ?? '').trim()
  if (rid && action) {
    const f = (from || '').trim() || 'any'
    const t = (to || '').trim() || 'any'
    const s = (service || '').trim() || 'any'
    cmds.push(`ip-policy ${n} id ${rid} from ${f} to ${t} service ${s} action ${action}`)
  }
  return cmds
}

/**
 * MAC firewall policy (object lifecycle only). Confirmed live: `mac-policy <name>` creates the group and
 * `no mac-policy <name>` removes it. MAC rules are entered per-attribute (`mac-policy <n> id <i> from <mac>` …,
 * the combined `from any to any action` line is rejected) and are niche, so HiveKeeper exposes create/remove here
 * and leaves rule entry to the Advanced raw-CLI section. The created policy is bindable from a user profile.
 */
export function macPolicyCommands(name, { remove } = {}) {
  const n = (name || '').trim()
  if (!n) return []
  return remove ? [`no mac-policy ${n}`] : [`mac-policy ${n}`]
}

/**
 * QoS policy (per-user-profile rate limit). Confirmed live: `qos policy <name>` creates the policy (auto-filling
 * the default per-class weights), and `qos policy <name> user-profile <rate-kbps> <weight 0-1000>` sets the
 * user-profile rate limit + scheduling weight (the trailing weight is required — without it HiveOS reports
 * "Incomplete command"). `qos enable` turns QoS on globally. `no qos policy <name>` removes it. Weight defaults to
 * 10 (the HiveOS default). A user profile then references this policy by name (`user-profile <n> qos-policy <name>`).
 */
export function qosPolicyCommands(name, { rateKbps, weight = 10, enableQos, remove } = {}) {
  const n = (name || '').trim()
  if (!n) return []
  if (remove) return [`no qos policy ${n}`]
  const cmds = []
  if (enableQos) cmds.push('qos enable')
  cmds.push(`qos policy ${n}`)
  const rate = String(rateKbps ?? '').trim()
  if (rate) cmds.push(`qos policy ${n} user-profile ${rate} ${weight}`)
  return cmds
}

/**
 * Per-SSID QoS. Confirmed live on an AP230: `ssid <n> qos-classifier <profile>` (classify incoming traffic),
 * `ssid <n> qos-marker <profile>` (mark outgoing), and `ssid <n> wmm` (a toggle — WMM is on by default, so the
 * `no` form disables it). The classifier/marker reference `qos classifier-profile` / `qos marker-profile` objects
 * by name. Blanks emit nothing; no SSID name means nothing to do.
 */
export function ssidQosCommands(ssid, { qosClassifier, qosMarker, wmm } = {}) {
  const name = (ssid || '').trim()
  if (!name) return []
  const cmds = []
  if (qosClassifier && qosClassifier.trim()) cmds.push(`ssid ${name} qos-classifier ${qosClassifier.trim()}`)
  if (qosMarker && qosMarker.trim()) cmds.push(`ssid ${name} qos-marker ${qosMarker.trim()}`)
  if (wmm === 'enable') cmds.push(`ssid ${name} wmm`)
  if (wmm === 'disable') cmds.push(`no ssid ${name} wmm`)
  return cmds
}

/**
 * LLDP / CDP neighbor discovery (global, not per-interface — `interface mgt0 lldp` is rejected; the settings are
 * device-wide). Confirmed live on an AP230: `lldp` enables it (and shows as `lldp` in running-config), `no lldp`
 * disables, `lldp timer <5-65534>` (advertise interval, default 30), `lldp holdtime <0-65535>` (default 90),
 * `lldp receive-only` (cache neighbors but don't advertise — a toggle), `lldp max-entries <1-128>` (default 64).
 * Toggles take 'enable'|'disable'|''; blank numerics emit nothing.
 */
export function lldpCommands({ enable, timer, holdtime, receiveOnly, maxEntries } = {}) {
  const cmds = []
  if (enable === 'enable') cmds.push('lldp')
  if (enable === 'disable') cmds.push('no lldp')
  if (String(timer ?? '').trim()) cmds.push(`lldp timer ${String(timer).trim()}`)
  if (String(holdtime ?? '').trim()) cmds.push(`lldp holdtime ${String(holdtime).trim()}`)
  if (receiveOnly === 'enable') cmds.push('lldp receive-only')
  if (receiveOnly === 'disable') cmds.push('no lldp receive-only')
  if (String(maxEntries ?? '').trim()) cmds.push(`lldp max-entries ${String(maxEntries).trim()}`)
  return cmds
}

/**
 * Static IP route. Confirmed live on an AP230 (and that the gateway must be on a directly-connected subnet, else
 * HiveOS silently drops the route): a network route is `ip route net <ip> <netmask> gateway <gw> [metric <m>]`
 * and a host route is `ip route host <ip> gateway <gw> [metric <m>]`. `no ip route …` removes it (echo the full
 * line). type is 'net' | 'host'. A net route needs dest+netmask+gateway; a host route needs dest+gateway.
 * DANGER-adjacent: a bad route can blackhole traffic, so the caller should confirm.
 */
export function staticRouteCommands({ type = 'net', dest, netmask, gateway, metric, remove } = {}) {
  const d = (dest || '').trim()
  const gw = (gateway || '').trim()
  if (!d || !gw) return []
  const m = String(metric ?? '').trim()
  let line
  if (type === 'host') {
    line = `ip route host ${d} gateway ${gw}`
  } else {
    const nm = (netmask || '').trim()
    if (!nm) return []
    line = `ip route net ${d} ${nm} gateway ${gw}`
  }
  if (m) line += ` metric ${m}`
  return [remove ? `no ${line}` : line]
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
