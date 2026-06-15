import { describe, it, expect } from 'vitest'
import {
  radioCommands,
  hostnameCommands,
  capwapCommands,
  mgtIpCommands,
  clientModeConnectCommands,
  clientModeDisconnectCommands,
} from './hiveosCli'

// Syntax confirmed live on an AP230 (HiveOS 10.6r1a) via `?` context help:
//   interface wifiN radio channel <num|auto> ; interface wifiN radio power <1-20|auto> ; interface wifiN mode <...>
//   hostname <string>(1-32) ; [no] capwap client enable
describe('radioCommands', () => {
  it('builds channel, power and mode lines for the chosen radio', () => {
    expect(radioCommands('wifi0', { channel: '36', power: '12', mode: 'access' })).toEqual([
      'interface wifi0 radio channel 36',
      'interface wifi0 radio power 12',
      'interface wifi0 mode access',
    ])
  })
  it('supports auto and the 5GHz radio, skipping blank fields', () => {
    expect(radioCommands('wifi1', { channel: 'auto' })).toEqual(['interface wifi1 radio channel auto'])
  })
  it('returns nothing when no field is set', () => {
    expect(radioCommands('wifi0', {})).toEqual([])
    expect(radioCommands('wifi0')).toEqual([])
  })
})

describe('hostnameCommands', () => {
  it('sets the hostname', () => {
    expect(hostnameCommands('lab-ap-01')).toEqual(['hostname lab-ap-01'])
  })
})

describe('capwapCommands', () => {
  it('goes standalone (disconnect from cloud) by default', () => {
    expect(capwapCommands(false)).toEqual(['no capwap client enable'])
  })
  it('reconnects to the cloud', () => {
    expect(capwapCommands(true)).toEqual(['capwap client enable'])
  })
})

describe('mgtIpCommands', () => {
  it('sets the mgt0 IP with a netmask (slash form)', () => {
    expect(mgtIpCommands('192.168.1.50', '255.255.255.0')).toEqual([
      'interface mgt0 ip 192.168.1.50/255.255.255.0',
    ])
  })
  it('accepts a bare IP', () => {
    expect(mgtIpCommands('192.168.1.50')).toEqual(['interface mgt0 ip 192.168.1.50'])
  })
})

describe('clientMode', () => {
  it('binds an SSID profile and connects', () => {
    expect(clientModeConnectCommands('HomeAP')).toEqual(['client-mode ssid HomeAP', 'client-mode connect'])
  })
  it('reverts to AP mode', () => {
    expect(clientModeDisconnectCommands()).toEqual(['no client-mode connect'])
  })
})
