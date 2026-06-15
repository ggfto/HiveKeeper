import { describe, it, expect } from 'vitest'
import { parseSsids, parseHives, parseCapwap, parseAcsp, parseLog } from './hiveosParse'

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
