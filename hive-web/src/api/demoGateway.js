/**
 * In-memory mock of the gateway client (see ./gateway.js) for the static demo build (VITE_DEMO). It exposes
 * the same method surface, backed by seeded fixtures; writes mutate this module-local state and everything
 * resets on reload. No network, no auth, no SSH — so the whole console is explorable with believable data.
 */

// The pre-seeded session the demo runs as: a multi-org operator, so the full console (not the solo subset) and
// the org switcher render. Consumed by AuthProvider.
export const DEMO_USER = {
  access_token: 'demo',
  profile: { name: 'Demo Operator', email: 'demo@hivekeeper.dev' },
}
export const DEMO_ME = {
  userId: 'u-demo',
  name: 'Demo Operator',
  email: 'demo@hivekeeper.dev',
  organizations: [
    { tenantId: 'acme', tenantName: 'Acme Corp' },
    { tenantId: 'globex', tenantName: 'Globex' },
  ],
}

const isoAgo = (min) => new Date(Date.now() - min * 60000).toISOString()
const clone = (v) => JSON.parse(JSON.stringify(v))

const st = (mac, ipAddress, ssid, hostname, rssi) => ({ mac, ipAddress, ssid, hostname, rssi })
const radios = (ch24, ch5) => [
  { name: 'wifi0', mode: '11ax', channel: ch24, width: 20, txPower: 18 },
  { name: 'wifi1', mode: '11ax', channel: ch5, width: 80, txPower: 23 },
]
const defaultSsids = () => [
  { name: 'Corp-WiFi', security: 'wpa2-aes-psk', vlan: 10, radios: ['wifi0', 'wifi1'] },
  { name: 'Guest', security: 'wpa2-aes-psk', vlan: 20, radios: ['wifi1'] },
]

// One AP. `agentId` not in the connected `agents` list => the AP reads as offline (inventory/backup reject).
function ap(deviceId, serial, model, mgmtIp, agentId, label, siteId, groups, extra = {}) {
  return {
    deviceId,
    serial,
    model,
    mgmtIp,
    agentId,
    label,
    siteId,
    groups,
    firmwareVersion: extra.firmwareVersion || 'HiveOS 10.6r1a',
    uptime: extra.uptime || '6d 4h',
    hiveName: extra.hiveName || 'hk-mesh',
    radios: extra.radios || radios(6, 149),
    stations: extra.stations || [],
    ssids: extra.ssids || defaultSsids(),
  }
}

function seed() {
  const sites = [
    { siteId: 's-hq', name: 'Headquarters' },
    { siteId: 's-wh', name: 'Warehouse' },
  ]
  const groups = [
    { groupId: 'g-floor1', name: 'Floor 1', siteId: 's-hq' },
    { groupId: 'g-floor2', name: 'Floor 2', siteId: 's-hq' },
    { groupId: 'g-guest', name: 'Guest APs', siteId: null },
  ]
  const agents = ['hq-agent', 'wh-agent'] // connected
  const devices = [
    ap('d-rcp', 'AH01-2207-0011', 'AP230', '192.168.10.11', 'hq-agent', 'Reception', 's-hq', ['g-floor1', 'g-guest'], {
      stations: [
        st('9c8e:99a1:2b04', '192.168.10.51', 'Corp-WiFi', 'Galaxy-S23', -47),
        st('a4cf:12bd:77e1', '192.168.10.52', 'Corp-WiFi', 'MacBook-Pro', -55),
        st('e0b9:4d22:1a90', '192.168.20.31', 'Guest', 'iPad-Visitor', -68),
      ],
    }),
    ap('d-f1e', 'AH01-2207-0012', 'AP250', '192.168.10.12', 'hq-agent', 'Floor 1 East', 's-hq', ['g-floor1'], {
      radios: radios(11, 36),
      stations: [
        st('3c2e:ff10:9a01', '192.168.10.61', 'Corp-WiFi', 'ThinkPad-X1', -52),
        st('b827:eb44:0c12', '192.168.10.62', 'Corp-WiFi', 'pi-sensor', -71),
      ],
    }),
    ap('d-f2w', 'AH01-2207-0013', 'AP250', '192.168.10.13', 'hq-agent', 'Floor 2 West', 's-hq', ['g-floor2'], {
      radios: radios(1, 44),
      stations: [
        st('f0d1:a920:55ab', '192.168.10.71', 'Corp-WiFi', 'Pixel-8', -49),
        st('8c85:90bb:1277', '192.168.10.72', 'Corp-WiFi', 'XPS-13', -60),
        st('00e0:4c68:0099', '192.168.10.73', 'Corp-WiFi', 'hp-printer', -74),
        st('dca6:32aa:4410', '192.168.20.41', 'Guest', 'echo-dot', -66),
      ],
    }),
    ap('d-dock', 'AH01-2210-0021', 'AP630', '192.168.20.11', 'wh-agent', 'Warehouse Dock', 's-wh', [], {
      hiveName: 'wh-mesh',
      radios: radios(6, 153),
      stations: [
        st('5c51:88f2:3b21', '192.168.20.51', 'Corp-WiFi', 'scanner-01', -58),
        st('5c51:88f2:3b22', '192.168.20.52', 'Corp-WiFi', 'scanner-02', -63),
      ],
    }),
    ap('d-whoffice', 'AH01-2210-0022', 'AP230', '192.168.20.12', 'wh-agent', 'Warehouse Office', 's-wh', ['g-guest'], {
      hiveName: 'wh-mesh',
      stations: [st('a0d7:95c1:7e30', '192.168.20.61', 'Guest', 'contractor-laptop', -70)],
    }),
    // Offline: its agent is not connected, so the map and detail page show it offline.
    ap('d-spare', 'AH01-2207-0099', 'AP230', '192.168.10.19', 'old-agent', 'Spare AP', 's-hq', [], {
      uptime: '—',
      stations: [],
    }),
  ]
  const members = [
    { userId: 'u-demo', name: 'Demo Operator', email: 'demo@hivekeeper.dev', status: 'active', role: 'owner' },
    { userId: 'u-nina', name: 'Nina Ops', email: 'nina@acme.example', status: 'active', role: 'admin' },
    { userId: 'u-raj', name: 'Raj Patel', email: 'raj@acme.example', status: 'active', role: 'operator' },
    { userId: 'u-sam', name: 'Sam Lee', email: 'sam@acme.example', status: 'invited', role: 'viewer' },
  ]
  const operations = [
    { id: 'op-6', createdAt: isoAgo(4), agentId: 'hq-agent', opType: 'inventory', host: '192.168.10.11', summary: 'AP230 · 3 stations' },
    { id: 'op-5', createdAt: isoAgo(22), agentId: 'hq-agent', opType: 'configure-ssid', host: '192.168.10.13', summary: 'set SSID Corp-WiFi (vlan 10)' },
    { id: 'op-4', createdAt: isoAgo(51), agentId: 'wh-agent', opType: 'backup', host: '192.168.20.11', summary: '4128 bytes · users' },
    { id: 'op-3', createdAt: isoAgo(95), agentId: 'wh-agent', opType: 'inventory', host: '192.168.20.12', summary: 'AP230 · 1 station' },
    { id: 'op-2', createdAt: isoAgo(140), agentId: 'hq-agent', opType: 'reboot', host: '192.168.10.12', summary: 'reboot requested' },
    { id: 'op-1', createdAt: isoAgo(180), agentId: 'hq-agent', opType: 'adopt', host: '192.168.10.13', summary: 'adopted AP250' },
  ]
  return { sites, groups, agents, devices, members, operations }
}

// --- canned HiveOS CLI output, shaped for the UI's parsers in lib/hiveosParse.js -------------------------

function runningConfig(d) {
  const lines = ['hostname ' + (d.label || d.serial).replace(/\s+/g, '-')]
  for (const s of d.ssids || []) {
    lines.push(`ssid ${s.name}`)
    lines.push(`security-object ${s.name} security protocol-suite ${s.security || 'wpa2-aes-psk'}`)
    if (s.vlan != null) lines.push(`user-profile ${s.name} qos-policy default vlan-id ${s.vlan}`)
    for (const r of s.radios || []) lines.push(`interface ${r} ssid ${s.name}`)
  }
  return lines.join('\n')
}

function hiveOutput(d) {
  const header = 'Name              Native-VLAN  Members'
  return d.hiveName ? `${header}\n${d.hiveName}        1            3` : header
}

function acspOutput(d) {
  const header = 'Name   ChnlSel  Chnl  Width  PwrCtrl  Power(dBm)'
  const rows = (d.radios || []).map(
    (r) => `${r.name.replace('wifi', 'Wifi')}  enabled  ${r.channel}  ${r.width}  enabled  ${r.txPower}`,
  )
  return [header, ...rows].join('\n')
}

function logOutput(d) {
  const first = (d.stations && d.stations[0] && d.stations[0].mac) || '9c8e:99a1:2b04'
  return [
    `2026-06-17 14:02:11 info  AP associated: station ${first} on Corp-WiFi`,
    `2026-06-17 14:01:50 notice  DHCP lease granted to ${first}`,
    '2026-06-17 13:58:03 warning  Radio wifi1 DFS radar check on channel 52',
    '2026-06-17 13:40:12 info  Config saved to flash',
    '2026-06-17 13:12:44 notice  Mesh neighbor up: ' + (d.hiveName || 'hk-mesh'),
  ].join('\n')
}

function cmdOutput(d, cmd) {
  const c = cmd.trim().toLowerCase()
  if (c === 'show running-config') return runningConfig(d)
  if (c === 'show hive') return hiveOutput(d)
  if (c === 'show capwap client') return 'CAPWAP client:   Disabled\nThe AP is operating standalone (no cloud controller).'
  if (c === 'show acsp') return acspOutput(d)
  if (c.startsWith('show log buffered')) return logOutput(d)
  if (c.startsWith('show ')) return `% (demo) no canned output for: ${cmd}`
  return `% accepted: ${cmd}`
}

// --- the client ----------------------------------------------------------------------------------------

export function createDemoGateway() {
  const db = seed()
  let opSeq = 100
  let idSeq = 0
  const uid = (p) => `${p}-${++idSeq}`
  const ok = (v) => Promise.resolve(v === undefined ? {} : clone(v))
  const fail = (msg, status = 502) => Promise.reject(Object.assign(new Error(msg), { status }))

  const byIp = (host) => db.devices.find((d) => d.mgmtIp === host)
  const byId = (id) => db.devices.find((d) => d.deviceId === id)
  const connected = (agentId) => db.agents.includes(agentId)
  // Unadopted hosts a discover sweep turns up — one tested model, one HiveOS-but-untested, so Identify and the
  // support badge have something believable to show in the demo.
  const discoverable = {
    '192.168.10.31': { serial: 'AH01-NEW-0031', model: 'AP230' },
    '192.168.10.42': { serial: 'AH01-NEW-0042', model: 'AP1130' },
  }
  const logOp = (opType, host, summary, agentId) =>
    db.operations.unshift({ id: `op-${++opSeq}`, createdAt: new Date().toISOString(), agentId, opType, host, summary })

  const deviceView = (d) => ({
    deviceId: d.deviceId,
    serial: d.serial,
    model: d.model,
    mgmtIp: d.mgmtIp,
    agentId: d.agentId,
    label: d.label,
    siteId: d.siteId,
    groups: [...(d.groups || [])],
  })
  const invView = (d) => ({
    serial: d.serial,
    model: d.model,
    firmwareVersion: d.firmwareVersion,
    hostname: null,
    hiveName: d.hiveName,
    uptime: d.uptime,
    stations: clone(d.stations || []),
    radios: clone(d.radios || []),
  })

  function applyConfig(host, { commands = [], save = false }) {
    const d = byIp(host)
    const outputs = commands.map((c) => (d ? cmdOutput(d, c) : `% unknown host ${host}`))
    const isRead = commands.every((c) => c.trim().toLowerCase().startsWith('show '))
    if (!isRead) logOp('apply-config', host, `${commands.length} CLI line(s)`)
    return ok({ commands, outputs, saved: !!save })
  }

  function configureSsid(host, body) {
    const d = byIp(host)
    if (d) {
      d.ssids = (d.ssids || []).filter((s) => s.name !== body.name)
      if (!body.remove) {
        d.ssids.push({
          name: body.name,
          security: body.security || 'wpa2-aes-psk',
          vlan: body.vlan ?? null,
          radios: ['wifi0', 'wifi1'],
        })
      }
    }
    logOp('configure-ssid', host, `${body.remove ? 'remove' : 'set'} SSID ${body.name}`)
    return ok({})
  }

  return {
    req: () => ok({}),
    setupStatus: () => ok({ initialized: true }),
    setup: () => ok({}),
    mode: () => ok({ solo: false }),
    me: () => ok(DEMO_ME),

    members: () => ok(db.members),
    addMember: (body) => {
      const m = { userId: uid('u'), name: body.name || body.username, email: body.email || '', status: 'invited', role: body.role || 'viewer' }
      db.members.push(m)
      return ok({ userId: m.userId })
    },
    setMemberRole: (userId, role) => {
      const m = db.members.find((x) => x.userId === userId)
      if (m) m.role = role
      return ok({})
    },
    removeMember: (userId) => {
      db.members = db.members.filter((x) => x.userId !== userId)
      return ok({})
    },

    agents: () => ok([...db.agents]),
    sites: () => ok(db.sites),
    groups: () => ok(db.groups),
    devices: () => ok(db.devices.map(deviceView)),
    operations: () => ok(db.operations),
    createEnrollment: (body) => ok({ agentId: body.agentId, token: `demo-enroll-${uid('t')}` }),

    createSite: (name) => {
      db.sites.push({ siteId: uid('s'), name })
      return ok({})
    },
    renameSite: (siteId, name) => {
      const s = db.sites.find((x) => x.siteId === siteId)
      if (s) s.name = name
      return ok({})
    },
    deleteSite: (siteId) => {
      db.sites = db.sites.filter((x) => x.siteId !== siteId)
      return ok({})
    },
    createGroup: (name, siteId) => {
      db.groups.push({ groupId: uid('g'), name, siteId: siteId || null })
      return ok({})
    },
    updateGroup: (groupId, body) => {
      const g = db.groups.find((x) => x.groupId === groupId)
      if (g) Object.assign(g, body)
      return ok({})
    },
    deleteGroup: (groupId) => {
      db.groups = db.groups.filter((x) => x.groupId !== groupId)
      return ok({})
    },
    tagDevice: (deviceId, groupId) => {
      const d = byId(deviceId)
      if (d && !d.groups.includes(groupId)) d.groups.push(groupId)
      return ok({})
    },
    untagDevice: (deviceId, groupId) => {
      const d = byId(deviceId)
      if (d) d.groups = d.groups.filter((g) => g !== groupId)
      return ok({})
    },
    updateDevice: (deviceId, body) => {
      const d = byId(deviceId)
      if (d) Object.assign(d, body)
      return ok({})
    },

    agentOp: (agentId, op, body = {}) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      if (op === 'apply-config') return applyConfig(body.host, body)
      if (op === 'configure-ssid') return configureSsid(body.host, body)
      if (op === 'reboot') {
        logOp('reboot', body.host, 'reboot requested', agentId)
        return ok({})
      }
      return ok({})
    },
    inventory: (agentId, host) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      const d = byIp(host)
      if (d) {
        logOp('inventory', host, `${d.model} · ${(d.stations || []).length} station(s)`, agentId)
        return ok({ device: invView(d) })
      }
      // An unadopted but discoverable host (the Identify flow): return believable inventory so the model + a
      // support badge show before adopting.
      const probe = discoverable[host]
      if (probe) {
        logOp('inventory', host, `${probe.model} · identify`, agentId)
        return ok({
          device: {
            serial: probe.serial,
            model: probe.model,
            firmwareVersion: 'HiveOS 10.6r1a',
            hostname: null,
            hiveName: null,
            uptime: '0d 0h',
            stations: [],
            radios: radios(6, 149),
          },
        })
      }
      return fail(`unknown host ${host}`, 404)
    },
    backup: (agentId, host) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      logOp('backup', host, '4096 bytes · users', agentId)
      return ok({ ref: { commitId: 'demo' + uid('c').padEnd(12, '0') }, configBytes: 4096, usersIncluded: true })
    },
    restore: (agentId, host, runningConfig, { save = true } = {}) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      const commands = String(runningConfig || '').split('\n').map((l) => l.trim()).filter(Boolean)
      logOp('restore', host, `${commands.length} line(s)${save ? ' · saved' : ''}`, agentId)
      return ok({ commands, outputs: commands.map(() => ''), saved: !!save })
    },
    firmwareUpgrade: (agentId, host, imageUrl, { reboot = true } = {}) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      logOp('firmware-upgrade', host, `${imageUrl}${reboot ? ' · reboot' : ''}`, agentId)
      return ok({ imageUrl, output: `% (demo) save image ${imageUrl}`, rebooting: !!reboot })
    },
    discover: (agentId) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      return ok({
        hosts: [
          { host: '192.168.10.31', sshBanner: 'SSH-2.0-OpenSSH_8.0', looksLikeSsh: true },
          { host: '192.168.10.42', sshBanner: 'SSH-2.0-dropbear_2020', looksLikeSsh: true },
        ],
      })
    },
    adopt: (agentId, body) => {
      const probe = discoverable[body.host]
      const model = probe?.model || 'AP230'
      const serial = probe?.serial || `AH01-DEMO-${String(++idSeq).padStart(4, '0')}`
      const d = ap(uid('d'), serial, model, body.host, agentId, `Adopted ${body.host}`, db.sites[0].siteId, [])
      db.devices.push(d)
      logOp('adopt', body.host, `adopted ${model}`, agentId)
      return ok({ deviceId: d.deviceId, serial, model })
    },
    setCredential: (agentId, body = {}) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      const credRef = body.deviceId || body.credRef || `cred-${body.host}`
      const d = byId(body.deviceId) || byIp(body.host)
      if (d) d.credRef = credRef
      logOp('set-credential', body.host, `credential set${body.alsoSetOnDevice ? ' · AP password changed' : ''}`, agentId)
      return ok({ credRef, vaultUpdated: true, deviceUpdated: !!body.alsoSetOnDevice })
    },
    // The org's backup destination. The demo keeps the token in memory and, like the real gateway, never
    // hands it back — the read reports only that one is configured.
    getBackupDestination: () =>
      ok(
        db.backupDestination
          ? { ...db.backupDestination, token: undefined, configured: true }
          : { configured: false },
      ),
    setBackupDestination: (body = {}) => {
      db.backupDestination = {
        repoUrl: body.repoUrl,
        branch: body.branch || 'main',
        username: body.username || 'hivekeeper',
        updatedAt: new Date().toISOString(),
        updatedBy: 'demo',
      }
      return ok({
        ...db.backupDestination,
        configured: true,
        agents: (db.agents || []).map((a) => ({ agentId: a.agentId, delivered: !!a.connected })),
      })
    },
    clearBackupDestination: () => {
      db.backupDestination = null
      return ok({ configured: false, agents: [] })
    },
    ppskUsers: (agentId) => ok({ users: (db.ppsk || []).filter((u) => u.agentId === agentId) }),
    createPpskUser: (agentId, body = {}) => {
      if (!connected(agentId)) return fail('the device agent is offline', 503)
      db.ppsk = db.ppsk || []
      const user = {
        id: uid('ppsk'),
        agentId,
        securityObject: body.securityObject,
        userGroup: body.userGroup || null,
        username: body.username,
        userProfileAttr: body.userProfileAttr ?? null,
        vlanId: body.vlanId ?? null,
        scheduleName: body.scheduleName || null,
        macBindings: body.macBindings || [],
        status: 'active',
        createdAt: new Date().toISOString(),
        rotatedAt: null,
      }
      db.ppsk.push(user)
      logOp('ppsk-create', body.securityObject, `minted PPSK user ${body.username}`, agentId)
      return ok({ user, psk: `demo-psk-${Math.random().toString(36).slice(2, 12)}` })
    },
    rotatePpskUser: (agentId, id) => {
      const u = (db.ppsk || []).find((x) => x.id === id)
      if (!u) return fail('ppsk user not found', 404)
      u.rotatedAt = new Date().toISOString()
      return ok({ user: u, psk: `demo-psk-${Math.random().toString(36).slice(2, 12)}` })
    },
    revokePpskUser: (agentId, id) => {
      const u = (db.ppsk || []).find((x) => x.id === id)
      if (!u) return fail('ppsk user not found', 404)
      u.status = 'revoked'
      return ok(u)
    },
    alertSettings: () => ok(db.alertSettings || { maxStations: 30, pollEnabled: true }),
    saveAlertSettings: (body) => {
      db.alertSettings = { maxStations: Math.max(1, body?.maxStations ?? 30), pollEnabled: !!body?.pollEnabled }
      return ok(db.alertSettings)
    },
    alertChannels: () => ok({ channels: db.alertChannels || [] }),
    addAlertChannel: (body) => {
      db.alertChannels = db.alertChannels || []
      const ch = {
        id: uid('ch'),
        type: body?.type,
        target: body?.target,
        minSeverity: body?.minSeverity || 'warning',
        enabled: true,
        createdAt: new Date().toISOString(),
      }
      db.alertChannels.push(ch)
      return ok(ch)
    },
    setAlertChannelEnabled: (id, enabled) => {
      const ch = (db.alertChannels || []).find((c) => c.id === id)
      if (ch) ch.enabled = enabled
      return ok({ error: 'ok', detail: id })
    },
    removeAlertChannel: (id) => {
      db.alertChannels = (db.alertChannels || []).filter((c) => c.id !== id)
      return ok({ error: 'ok', detail: id })
    },
    firingAlerts: () => ok({ alerts: db.firingAlerts || [] }),
    bulk: (op, target) => {
      let pool = db.devices
      if (target?.kind === 'site') pool = pool.filter((d) => d.siteId === target.id)
      if (target?.kind === 'group') pool = pool.filter((d) => (d.groups || []).includes(target.id))
      const results = pool.map((d) =>
        connected(d.agentId)
          ? { deviceId: d.deviceId, host: d.mgmtIp, serial: d.serial, status: 'ok' }
          : { deviceId: d.deviceId, host: d.mgmtIp, serial: d.serial, status: 'agent_offline', detail: 'agent not connected' },
      )
      const okCount = results.filter((r) => r.status === 'ok').length
      logOp(op, target?.kind || 'org', `${okCount}/${results.length} ok`)
      return ok({ op, total: results.length, ok: okCount, failed: results.length - okCount, results })
    },
    bulkApplyConfig: (target, commands, save) => {
      let pool = db.devices
      if (target?.kind === 'site') pool = pool.filter((d) => d.siteId === target.id)
      if (target?.kind === 'group') pool = pool.filter((d) => (d.groups || []).includes(target.id))
      const results = pool.map((d) =>
        connected(d.agentId)
          ? { deviceId: d.deviceId, host: d.mgmtIp, serial: d.serial, status: 'ok' }
          : { deviceId: d.deviceId, host: d.mgmtIp, serial: d.serial, status: 'agent_offline', detail: 'agent not connected' },
      )
      const okCount = results.filter((r) => r.status === 'ok').length
      logOp('apply-config', target?.kind || 'org', `${(commands || []).length} line(s) · ${okCount}/${results.length} ok${save ? ' · saved' : ''}`)
      return ok({ op: 'apply-config', total: results.length, ok: okCount, failed: results.length - okCount, results })
    },
  }
}
