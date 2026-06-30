import { describe, it, expect } from 'vitest'
import {
  parseSsids,
  parseHives,
  parseCapwap,
  parseAcsp,
  parseLog,
  filterLog,
  parseUserProfiles,
  parseStaticRoutes,
  parseFirewallPolicies,
  parseQosPolicies,
  parseSchedules,
  parseRebootSchedule,
} from './hiveosParse'

// A real running-config excerpt captured from the AP230 (secrets already masked by the gateway).
const CONFIG = `
hostname AH-827200
security-object TESTE
security-object TESTE security protocol-suite wpa2-aes-psk ascii-key ***
security-object HK-JOB
security-object HK-JOB security protocol-suite wpa2-aes-psk ascii-key ***
security-object HK-JOB default-user-profile-attr 7
ssid TESTE
ssid TESTE security-object TESTE
ssid HK-JOB
ssid HK-JOB security-object HK-JOB
interface wifi0 ssid TESTE
interface wifi1 ssid TESTE
interface wifi0 ssid HK-JOB
interface wifi1 ssid HK-JOB
user-profile HK-JOB qos-policy def-user-qos vlan-id 7 attribute 7
`

describe('parseSsids', () => {
  it('lists each SSID with its security, VLAN and bound radios', () => {
    expect(parseSsids(CONFIG)).toEqual([
      { name: 'TESTE', security: 'wpa2-aes-psk', vlan: null, radios: ['wifi0', 'wifi1'] },
      { name: 'HK-JOB', security: 'wpa2-aes-psk', vlan: 7, radios: ['wifi0', 'wifi1'] },
    ])
  })

  it('returns an empty list for a config with no SSIDs', () => {
    expect(parseSsids('hostname x\nno capwap client enable')).toEqual([])
    expect(parseSsids('')).toEqual([])
    expect(parseSsids(null)).toEqual([])
  })
})

describe('parseUserProfiles', () => {
  it('reads the coalesced profile line and the SSID that binds its attribute', () => {
    expect(parseUserProfiles(CONFIG)).toEqual([
      {
        name: 'HK-JOB',
        attribute: 7,
        vlanId: 7,
        vlanGroup: null,
        qosPolicy: 'def-user-qos',
        schedule: null,
        boundTo: ['HK-JOB'],
      },
    ])
  })

  it('orders by attribute, leaves boundTo empty when nothing binds it, and reads a VLAN group', () => {
    const cfg = `
user-profile staff attribute 5 vlan-id 50
user-profile guest vlan-group guest-vlans attribute 3
security-object Corp default-user-profile-attr 5
`
    expect(parseUserProfiles(cfg)).toEqual([
      { name: 'guest', attribute: 3, vlanId: null, vlanGroup: 'guest-vlans', qosPolicy: null, schedule: null, boundTo: [] },
      { name: 'staff', attribute: 5, vlanId: 50, vlanGroup: null, qosPolicy: null, schedule: null, boundTo: ['Corp'] },
    ])
  })

  it('returns an empty list when there are no user profiles', () => {
    expect(parseUserProfiles('hostname x')).toEqual([])
    expect(parseUserProfiles('')).toEqual([])
    expect(parseUserProfiles(null)).toEqual([])
  })
})

describe('parseStaticRoutes', () => {
  it('parses net, host and default routes with optional metric', () => {
    const cfg = `
ip route default gateway 192.168.1.1
ip route net 10.9.9.0 255.255.255.0 gateway 192.168.1.1
ip route host 10.8.8.8 gateway 192.168.1.2 metric 5
`
    expect(parseStaticRoutes(cfg)).toEqual([
      { type: 'default', dest: null, netmask: null, gateway: '192.168.1.1', metric: null },
      { type: 'net', dest: '10.9.9.0', netmask: '255.255.255.0', gateway: '192.168.1.1', metric: null },
      { type: 'host', dest: '10.8.8.8', netmask: null, gateway: '192.168.1.2', metric: 5 },
    ])
  })
  it('returns an empty list when there are no routes', () => {
    expect(parseStaticRoutes('hostname x')).toEqual([])
    expect(parseStaticRoutes('')).toEqual([])
  })
})

describe('parseFirewallPolicies', () => {
  it('collects de-duplicated, sorted ip- and mac-policy names', () => {
    const cfg = `
ip-policy block-smb
ip-policy block-smb id 1 from any to any service smb action deny
ip-policy allow-dns
mac-policy mac-allow
`
    expect(parseFirewallPolicies(cfg)).toEqual({ ip: ['allow-dns', 'block-smb'], mac: ['mac-allow'] })
  })
  it('returns empty arrays for a config with no policies', () => {
    expect(parseFirewallPolicies('hostname x')).toEqual({ ip: [], mac: [] })
  })
})

describe('parseQosPolicies', () => {
  it('collects de-duplicated, sorted qos policy names', () => {
    const cfg = `
qos policy voip
qos policy voip user-profile 5000 10
qos policy bulk
`
    expect(parseQosPolicies(cfg)).toEqual(['bulk', 'voip'])
  })
  it('returns an empty list when there are none', () => {
    expect(parseQosPolicies('hostname x')).toEqual([])
  })
})

// `show hive` output from the AP230.
const SHOW_HIVE = `
show hive
Name    N-vlan  Frag    RTS     Mac filter
----    ------  ----    ----    ----------
hive0   1       2346    2346    None
hk-mesh 1       2346    2346    None
`

describe('parseHives', () => {
  it('lists each hive with its native VLAN, skipping header/separator/echo', () => {
    expect(parseHives(SHOW_HIVE)).toEqual([
      { name: 'hive0', nativeVlan: 1 },
      { name: 'hk-mesh', nativeVlan: 1 },
    ])
  })
  it('is empty for no hives', () => {
    expect(parseHives('')).toEqual([])
    expect(parseHives(null)).toEqual([])
  })
})

// `show capwap client` from the AP230 (standalone -> CAPWAP client Disabled).
const CAPWAP_OFF = `
CAPWAP client:   Disabled
Discovery interval:      5 seconds
Primary server tries:    0`

describe('parseCapwap', () => {
  it('reads a standalone AP (CAPWAP client disabled = not calling home)', () => {
    expect(parseCapwap(CAPWAP_OFF)).toEqual({ known: true, managed: false })
  })
  it('reads a cloud-managed AP (CAPWAP client enabled)', () => {
    expect(parseCapwap('CAPWAP client:   Enabled')).toEqual({ known: true, managed: true })
  })
  it('is unknown when the line is absent', () => {
    expect(parseCapwap('')).toEqual({ known: false, managed: false })
    expect(parseCapwap(null)).toEqual({ known: false, managed: false })
  })
})

// `show acsp` from the AP230 (per-radio channel-selection state).
const SHOW_ACSP = `

Interface Channel select state  Primary channel  Channel width Power ctrl state      Tx power(dbm) Use Last Selection
--------- --------------------- ---------------- ------------- --------------------- ------------- ---------------------
Wifi0     Enable                11               20            Enable                18            Channel:No  Power:No
Wifi1     Enable                165              20            Enable                20            Channel:No  Power:No`

describe('parseAcsp', () => {
  it('lists each radio with channel, width, Tx power and auto-select state', () => {
    expect(parseAcsp(SHOW_ACSP)).toEqual([
      { name: 'Wifi0', channelSelect: 'Enable', channel: 11, width: 20, powerCtrl: 'Enable', txPower: 18 },
      { name: 'Wifi1', channelSelect: 'Enable', channel: 165, width: 20, powerCtrl: 'Enable', txPower: 20 },
    ])
  })
  it('is empty for no radio rows', () => {
    expect(parseAcsp('')).toEqual([])
    expect(parseAcsp(null)).toEqual([])
  })
})

// `show log buffered` from the AP230 (newest entry first): "<date> <time> <level>  <message>".
const SHOW_LOG = `2026-06-15 20:19:32 info    ah_cli: security: admin:<show log buffered>
2026-06-15 20:19:31 notice  -ah_cli_ui: security: Admin logged in
a non-timestamped continuation line that should be skipped
2026-06-15 20:19:30 error   kernel: something failed`

describe('parseLog', () => {
  it('parses timestamped entries (newest first) into {time, level, message}, skipping noise', () => {
    expect(parseLog(SHOW_LOG)).toEqual([
      { time: '2026-06-15 20:19:32', level: 'info', message: 'ah_cli: security: admin:<show log buffered>' },
      { time: '2026-06-15 20:19:31', level: 'notice', message: '-ah_cli_ui: security: Admin logged in' },
      { time: '2026-06-15 20:19:30', level: 'error', message: 'kernel: something failed' },
    ])
  })
  it('keeps only the most recent `limit` entries (the buffer is newest-first)', () => {
    expect(parseLog(SHOW_LOG, 1)).toEqual([
      { time: '2026-06-15 20:19:32', level: 'info', message: 'ah_cli: security: admin:<show log buffered>' },
    ])
  })
  it('is empty when nothing parses', () => {
    expect(parseLog('')).toEqual([])
    expect(parseLog(null)).toEqual([])
    expect(parseLog('no timestamp here')).toEqual([])
  })
})

describe('filterLog', () => {
  const log = [
    { time: 't1', level: 'info', message: 'station assoc on wifi0' },
    { time: 't2', level: 'notice', message: 'admin login' },
    { time: 't3', level: 'error', message: 'dnsmasq failed' },
    { time: 't4', level: 'warning', message: 'DFS radar on ch165' },
  ]
  it('keeps everything for level=all', () => {
    expect(filterLog(log, 'all')).toHaveLength(4)
  })
  it('filters by minimum severity (error -> only error and worse)', () => {
    expect(filterLog(log, 'error').map((e) => e.time)).toEqual(['t3'])
  })
  it('warning -> warning and worse', () => {
    expect(filterLog(log, 'warning').map((e) => e.time).sort()).toEqual(['t3', 't4'])
  })
  it('matches the query against message and level', () => {
    expect(filterLog(log, 'all', 'login').map((e) => e.time)).toEqual(['t2'])
    expect(filterLog(log, 'all', 'notice').map((e) => e.time)).toEqual(['t2']) // by level too
  })
  it('is safe on empty/missing input', () => {
    expect(filterLog([], 'all')).toEqual([])
    expect(filterLog(null, 'error')).toEqual([])
  })
})

describe('parseSchedules', () => {
  const cfg = `
hostname AH-827200
schedule work-hours recurrent weekday-range Monday to Friday time-range 08:00 to 17:00
schedule xmas once 2026-12-25 08:00 to 2026-12-26 17:00
ssid HK-JOB schedule work-hours
`
  it('parses recurrent and one-time schedules with their type and detail', () => {
    const s = parseSchedules(cfg)
    expect(s).toEqual([
      { name: 'work-hours', type: 'recurrent', detail: 'weekday-range Monday to Friday time-range 08:00 to 17:00' },
      { name: 'xmas', type: 'once', detail: '2026-12-25 08:00 to 2026-12-26 17:00' },
    ])
  })
  it('ignores ssid lines that merely reference a schedule', () => {
    expect(parseSchedules(cfg).map((s) => s.name)).not.toContain('HK-JOB')
  })
  it('is safe on empty/missing input', () => {
    expect(parseSchedules('')).toEqual([])
    expect(parseSchedules(null)).toEqual([])
  })
})

describe('parseRebootSchedule', () => {
  it('parses the next scheduled reboot from `show reboot schedule`', () => {
    const out = `show reboot schedule
Next reboot Scheduled At:2026-07-12  02:59:12  Sunday
11 Days 13 Hours 0 Minutes 0 Seconds Left`
    expect(parseRebootSchedule(out)).toEqual({ scheduledAt: '2026-07-12 02:59:12', weekday: 'Sunday' })
  })
  it('returns null when no reboot is scheduled (empty output)', () => {
    expect(parseRebootSchedule('show reboot schedule')).toBeNull()
    expect(parseRebootSchedule('')).toBeNull()
    expect(parseRebootSchedule(null)).toBeNull()
  })
})
