import { describe, it, expect } from 'vitest'
import { parseSsids, parseHives } from './hiveosParse'

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
