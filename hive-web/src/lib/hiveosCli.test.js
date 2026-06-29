import { describe, it, expect } from 'vitest'
import {
  radioCommands,
  radioProfileCommands,
  minRateCommands,
  hostnameCommands,
  ssidHardeningCommands,
  ppskCommands,
  meshCommands,
  hiveTuningCommands,
  captivePortalCommands,
  capwapCommands,
  managementCommands,
  clientModeConnectCommands,
  clientModeDisconnectCommands,
  ledCommands,
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
  // Confirmed via `?`: interface wifiN radio tx-power-control <1-20|auto> (client target power).
  it('builds a tx-power-control line', () => {
    expect(radioCommands('wifi1', { txPowerControl: '15' })).toEqual([
      'interface wifi1 radio tx-power-control 15',
    ])
    expect(radioCommands('wifi1', { txPowerControl: 'auto' })).toEqual([
      'interface wifi1 radio tx-power-control auto',
    ])
  })
})

// Confirmed via `?`: radio profile <name> channel-width 20|40|80 ; band-steering ; client-load-balance ;
//   max-client <n>. Channel width and the density knobs live on the named profile, not the wifiN interface.
describe('radioProfileCommands', () => {
  it('sets channel width, band-steering, load-balance and max-client on the profile', () => {
    expect(
      radioProfileCommands('radio_ac0', {
        channelWidth: '40',
        bandSteering: 'enable',
        clientLoadBalance: 'enable',
        maxClient: '60',
      }),
    ).toEqual([
      'radio profile radio_ac0 channel-width 40',
      'radio profile radio_ac0 band-steering',
      'radio profile radio_ac0 client-load-balance',
      'radio profile radio_ac0 max-client 60',
    ])
  })
  it('emits the negation form to disable band-steering and load-balance', () => {
    expect(radioProfileCommands('radio_ng0', { bandSteering: 'disable', clientLoadBalance: 'disable' })).toEqual([
      'no radio profile radio_ng0 band-steering',
      'no radio profile radio_ng0 client-load-balance',
    ])
  })
  it('leaves unchanged fields out and returns nothing without a profile', () => {
    expect(radioProfileCommands('radio_ac0', {})).toEqual([])
    expect(radioProfileCommands('', { channelWidth: '80' })).toEqual([])
    expect(radioProfileCommands('  ', { channelWidth: '80' })).toEqual([])
  })
})

// Confirmed live on the AP230 via `?`: ssid <name> 11g-rate-set <rate>[-basic] [<rate>[-basic] ...] (one line,
// ascending Mbps, the lowest token as -basic). Rates below the lowest token are dropped from the air entirely —
// that is how slow 802.11b basic rates (1/2/5.5/11) are pruned to save airtime. 11a (5 GHz) has no <11 rates.
describe('minRateCommands', () => {
  it('builds a 2.4 GHz (11g) rate set that drops everything below the chosen minimum', () => {
    expect(minRateCommands('HK-JOB', { band: '2.4', minRate: '12' })).toEqual([
      'ssid HK-JOB 11g-rate-set 12-basic 18 24 36 48 54',
    ])
  })
  it('keeps a fractional rate like 5.5 when the minimum allows it', () => {
    expect(minRateCommands('HK-JOB', { band: '2.4', minRate: '5.5' })).toEqual([
      'ssid HK-JOB 11g-rate-set 5.5-basic 6 9 11 12 18 24 36 48 54',
    ])
  })
  it('uses the 11a ladder for 5 GHz', () => {
    expect(minRateCommands('HK-JOB', { band: '5', minRate: '24' })).toEqual([
      'ssid HK-JOB 11a-rate-set 24-basic 36 48 54',
    ])
  })
  it('returns nothing without an ssid, band, or minimum rate', () => {
    expect(minRateCommands('', { band: '2.4', minRate: '12' })).toEqual([])
    expect(minRateCommands('HK-JOB', { minRate: '12' })).toEqual([])
    expect(minRateCommands('HK-JOB', { band: '2.4' })).toEqual([])
  })
})

describe('hostnameCommands', () => {
  it('sets the hostname', () => {
    expect(hostnameCommands('lab-ap-01')).toEqual(['hostname lab-ap-01'])
  })
})

// Confirmed live on an AP230: ssid <n> hide-ssid | max-client <1-255> | inter-station-traffic |
// dtim-period <1-255> | schedule <name> | rrm enable | wnm enable.
describe('ssidHardeningCommands', () => {
  it('hides, caps clients, and isolates (inverse of inter-station-traffic)', () => {
    expect(ssidHardeningCommands('Corp', { hideSsid: 'enable', maxClient: 50, clientIsolation: 'enable' })).toEqual([
      'ssid Corp hide-ssid',
      'ssid Corp max-client 50',
      'no ssid Corp inter-station-traffic',
    ])
  })
  it('un-hides and re-permits peer traffic with the disable toggles', () => {
    expect(ssidHardeningCommands('Corp', { hideSsid: 'disable', clientIsolation: 'disable' })).toEqual([
      'no ssid Corp hide-ssid',
      'ssid Corp inter-station-traffic',
    ])
  })
  it('sets dtim, schedule and the 802.11k/v toggles', () => {
    expect(ssidHardeningCommands('Corp', { dtimPeriod: 3, schedule: 'business-hours', rrm: 'enable', wnm: 'enable' })).toEqual([
      'ssid Corp dtim-period 3',
      'ssid Corp schedule business-hours',
      'ssid Corp rrm enable',
      'ssid Corp wnm enable',
    ])
  })
  it('returns nothing without an SSID or fields', () => {
    expect(ssidHardeningCommands('', { hideSsid: 'enable' })).toEqual([])
    expect(ssidHardeningCommands('Corp', {})).toEqual([])
  })
})

// Confirmed live on an AP230: [no] security-object <so> security private-psk [external-server|default-psk-disabled|
// ppsk-server <ip>]; [no] security-object <so> ppsk-web-server [https|web-directory <d>|auth-user];
// ssid <so> user-group <group>. HiveKeeper configures the self-registration model; it does not mint per-user keys.
describe('ppskCommands', () => {
  it('enables PPSK mode with an external server and a bound user-group', () => {
    expect(
      ppskCommands('Corp', { enable: 'enable', externalServer: 'enable', defaultPskDisabled: 'enable', userGroup: 'staff' }),
    ).toEqual([
      'security-object Corp security private-psk',
      'security-object Corp security private-psk external-server',
      'security-object Corp security private-psk default-psk-disabled',
      'ssid Corp user-group staff',
    ])
  })
  it('configures the AP as the PPSK server with a self-registration portal', () => {
    expect(
      ppskCommands('Corp', {
        enable: 'enable',
        ppskServer: '10.0.0.1',
        webServer: 'enable',
        webHttps: 'enable',
        webDirectory: 'ppsk-reg',
        authUser: 'enable',
      }),
    ).toEqual([
      'security-object Corp security private-psk',
      'security-object Corp security private-psk ppsk-server 10.0.0.1',
      'security-object Corp ppsk-web-server',
      'security-object Corp ppsk-web-server https',
      'security-object Corp ppsk-web-server web-directory ppsk-reg',
      'security-object Corp ppsk-web-server auth-user',
    ])
  })
  it('disables PPSK mode and the portal with the no-form', () => {
    expect(ppskCommands('Corp', { enable: 'disable', webServer: 'disable' })).toEqual([
      'no security-object Corp security private-psk',
      'no security-object Corp ppsk-web-server',
    ])
  })
  it('returns nothing without a security object or fields', () => {
    expect(ppskCommands('', { enable: 'enable' })).toEqual([])
    expect(ppskCommands('Corp', {})).toEqual([])
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

// Confirmed via `?`: system led brightness <bright|off> ; [no] system led power-saving-mode
describe('ledCommands', () => {
  it('sets brightness and toggles power-saving', () => {
    expect(ledCommands({ brightness: 'off', powerSaving: 'disable' })).toEqual([
      'system led brightness off',
      'no system led power-saving-mode',
    ])
    expect(ledCommands({ brightness: 'bright', powerSaving: 'enable' })).toEqual([
      'system led brightness bright',
      'system led power-saving-mode',
    ])
  })
  it('leaves power-saving unchanged when blank, and emits nothing for an empty request', () => {
    expect(ledCommands({ brightness: 'off', powerSaving: '' })).toEqual(['system led brightness off'])
    expect(ledCommands({})).toEqual([])
  })
})
