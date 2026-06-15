import { describe, it, expect } from 'vitest'
import {
  radioCommands,
  hostnameCommands,
  meshCommands,
  hiveTuningCommands,
  captivePortalCommands,
  capwapCommands,
  managementCommands,
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

describe('meshCommands', () => {
  it('creates a hive, sets the password, and binds the chosen interfaces', () => {
    expect(meshCommands({ name: 'hk-mesh', password: 'secret', interfaces: ['mgt0', 'wifi1'] })).toEqual([
      'hive hk-mesh',
      'hive hk-mesh password secret',
      'interface mgt0 hive hk-mesh',
      'interface wifi1 hive hk-mesh',
    ])
  })
  it('works with no password and no interfaces', () => {
    expect(meshCommands({ name: 'hk-mesh' })).toEqual(['hive hk-mesh'])
  })
})

// Confirmed via `?`: hive <name> frag-threshold <256-2346> ; rts-threshold <1-2346> ;
//   neighbor connecting-threshold <-90..-55 | high|medium|low>
describe('hiveTuningCommands', () => {
  it('tunes frag/rts thresholds and the mesh connecting threshold for a hive', () => {
    expect(
      hiveTuningCommands('hk-mesh', { fragThreshold: '2000', rtsThreshold: '1500', connectingThreshold: 'medium' }),
    ).toEqual([
      'hive hk-mesh frag-threshold 2000',
      'hive hk-mesh rts-threshold 1500',
      'hive hk-mesh neighbor connecting-threshold medium',
    ])
  })
  it('accepts a raw dBm connecting threshold and skips blank fields', () => {
    expect(hiveTuningCommands('hk-mesh', { connectingThreshold: '-75' })).toEqual([
      'hive hk-mesh neighbor connecting-threshold -75',
    ])
  })
  it('emits nothing without a hive name or any field', () => {
    expect(hiveTuningCommands('hk-mesh', {})).toEqual([])
    expect(hiveTuningCommands('', { fragThreshold: '2000' })).toEqual([])
  })
})

// Confirmed via `?`: security-object <so> web-server [port <n>] [ssl] ; web-directory <dir> ;
//   walled-garden ip-address|hostname <v>
describe('captivePortalCommands', () => {
  it('enables the internal web server (port + ssl), sets the directory and walled garden', () => {
    expect(
      captivePortalCommands('HK-JOB', {
        webServer: true,
        port: '8080',
        ssl: true,
        webDirectory: 'guest',
        walledGarden: ['192.168.1.10', 'updates.example.com'],
      }),
    ).toEqual([
      'security-object HK-JOB web-server',
      'security-object HK-JOB web-server port 8080',
      'security-object HK-JOB web-server ssl',
      'security-object HK-JOB web-directory guest',
      'security-object HK-JOB walled-garden ip-address 192.168.1.10',
      'security-object HK-JOB walled-garden hostname updates.example.com',
    ])
  })
  it('classifies an IPv4 entry as ip-address and a name as hostname', () => {
    expect(captivePortalCommands('SO', { walledGarden: ['10.0.0.0', 'cdn.test'] })).toEqual([
      'security-object SO walled-garden ip-address 10.0.0.0',
      'security-object SO walled-garden hostname cdn.test',
    ])
  })
  it('emits nothing without a security object', () => {
    expect(captivePortalCommands('', { webServer: true })).toEqual([])
    expect(captivePortalCommands('HK-JOB', {})).toEqual([])
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

describe('managementCommands', () => {
  it('sets the mgt0 IP/netmask, VLAN, gateway, skipping blanks', () => {
    expect(
      managementCommands({ ip: '192.168.1.50', netmask: '255.255.255.0', vlan: '10', gateway: '192.168.1.1' }),
    ).toEqual([
      'interface mgt0 ip 192.168.1.50/255.255.255.0',
      'interface mgt0 vlan 10',
      'ip route default gateway 192.168.1.1',
    ])
  })
  it('toggles the DHCP client', () => {
    expect(managementCommands({ dhcp: 'enable' })).toEqual(['interface mgt0 dhcp client'])
    expect(managementCommands({ dhcp: 'disable' })).toEqual(['no interface mgt0 dhcp client'])
  })
  it('emits nothing when no field is set', () => {
    expect(managementCommands({})).toEqual([])
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
