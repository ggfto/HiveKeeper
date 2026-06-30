import { describe, it, expect } from 'vitest'
import {
  radioCommands,
  radioProfileCommands,
  minRateCommands,
  hostnameCommands,
  ssidHardeningCommands,
  ppskCommands,
  ppskRadiusCommands,
  meshCommands,
  hiveTuningCommands,
  captivePortalCommands,
  capwapCommands,
  managementCommands,
  clientModeConnectCommands,
  clientModeDisconnectCommands,
  ledCommands,
  userProfileCommands,
  bindUserProfileCommands,
  removeUserProfileCommands,
  userProfilePolicyCommands,
  ipPolicyCommands,
  macPolicyCommands,
  qosPolicyCommands,
  ssidQosCommands,
  lldpCommands,
  staticRouteCommands,
  scheduleCommands,
  removeScheduleCommands,
  rebootScheduleCommands,
  cancelRebootScheduleCommands,
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
  // Phase 4 advanced interface knobs, confirmed live on the AP230:
  //   rx-sop <number|high|low|medium> ; ed-threshold <-70..-50> ; dfs-backup-channel <freq|channel>
  it('builds the advanced rx-sop / ed-threshold / dfs-backup-channel lines', () => {
    expect(
      radioCommands('wifi1', { rxSop: 'high', edThreshold: '-62', dfsBackupChannel: '36' }),
    ).toEqual([
      'interface wifi1 radio rx-sop high',
      'interface wifi1 radio ed-threshold -62',
      'interface wifi1 radio dfs-backup-channel 36',
    ])
    expect(radioCommands('wifi0', { rxSop: '-82' })).toEqual(['interface wifi0 radio rx-sop -82'])
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
  // Phase 4 advanced profile knobs, confirmed live on the AP230. Bare-line toggles (dfs, short-guard-interval,
  // ampdu, amsdu, frameburst) negate with `no ...`; high-density / weak-snr-suppress carry an `enable` sub-word.
  it('builds the advanced bare-line toggles and their negations', () => {
    expect(
      radioProfileCommands('radio_ac0', {
        dfs: 'enable',
        shortGuardInterval: 'enable',
        ampdu: 'enable',
        amsdu: 'enable',
        frameburst: 'enable',
      }),
    ).toEqual([
      'radio profile radio_ac0 dfs',
      'radio profile radio_ac0 short-guard-interval',
      'radio profile radio_ac0 ampdu',
      'radio profile radio_ac0 amsdu',
      'radio profile radio_ac0 frameburst',
    ])
    expect(radioProfileCommands('radio_ng0', { dfs: 'disable', ampdu: 'disable' })).toEqual([
      'no radio profile radio_ng0 dfs',
      'no radio profile radio_ng0 ampdu',
    ])
  })
  it('builds high-density / weak-snr-suppress with the enable sub-word and their negations', () => {
    expect(radioProfileCommands('radio_ac0', { highDensity: 'enable', weakSnrSuppress: 'enable' })).toEqual([
      'radio profile radio_ac0 high-density enable',
      'radio profile radio_ac0 weak-snr-suppress enable',
    ])
    expect(radioProfileCommands('radio_ac0', { highDensity: 'disable', weakSnrSuppress: 'disable' })).toEqual([
      'no radio profile radio_ac0 high-density enable',
      'no radio profile radio_ac0 weak-snr-suppress enable',
    ])
  })
  // tx-beamforming auto|explicit-only (no ... to disable) ; phymode <11a|11ac|11b/g|11na|11ng> ;
  // receive-chain / transmit-chain <1-3>. phymode is emitted FIRST — channel-width and tx-beamforming reject
  // until the PHY mode matches (confirmed live on the AP230).
  it('builds tx-beamforming, phymode and chain counts with phymode first', () => {
    expect(
      radioProfileCommands('radio_ac0', {
        txBeamforming: 'explicit-only',
        phymode: '11ac',
        receiveChain: '2',
        transmitChain: '3',
      }),
    ).toEqual([
      'radio profile radio_ac0 phymode 11ac',
      'radio profile radio_ac0 tx-beamforming explicit-only',
      'radio profile radio_ac0 receive-chain 2',
      'radio profile radio_ac0 transmit-chain 3',
    ])
    expect(radioProfileCommands('radio_ac0', { txBeamforming: 'auto' })).toEqual([
      'radio profile radio_ac0 tx-beamforming auto',
    ])
    expect(radioProfileCommands('radio_ac0', { txBeamforming: 'disable' })).toEqual([
      'no radio profile radio_ac0 tx-beamforming',
    ])
  })
  it('emits phymode before channel-width (live: width rejects until the PHY mode matches)', () => {
    expect(radioProfileCommands('custom1', { channelWidth: '80', phymode: '11ac' })).toEqual([
      'radio profile custom1 phymode 11ac',
      'radio profile custom1 channel-width 80',
    ])
  })
  // bindInterface appends `interface <iface> radio profile <p>` LAST so the configured profile takes effect on
  // the radio. Confirmed live: create+configure HKBIND, then `interface wifi1 radio profile HKBIND`.
  it('binds the profile to a radio interface as the last line', () => {
    expect(
      radioProfileCommands('hk_5g_dense', { phymode: '11ac', channelWidth: '80', bindInterface: 'wifi1' }),
    ).toEqual([
      'radio profile hk_5g_dense phymode 11ac',
      'radio profile hk_5g_dense channel-width 80',
      'interface wifi1 radio profile hk_5g_dense',
    ])
  })
  it('emits the bind line even with no knob changes', () => {
    expect(radioProfileCommands('hk_5g_dense', { bindInterface: 'wifi0' })).toEqual([
      'interface wifi0 radio profile hk_5g_dense',
    ])
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

// Confirmed live on an AP230 (HiveOS 10.6r1a): aaa ppsk-server radius-server primary <ip> [shared-secret <s>]
// [auth-port <n>]; aaa ppsk-server auto-save-interval <60-3600>; [no] security-object <so> security private-psk
// radius-auth [pap|chap|ms-chap-v2] (bare = enable with the default PAP method). PPSK Caminho B wiring only —
// minting per-user keys needs the RADIUS runtime (a future phase).
describe('ppskRadiusCommands', () => {
  it('wires the local PPSK server at a RADIUS backend and forwards a security object to it', () => {
    expect(
      ppskRadiusCommands('Corp', {
        radiusServer: '10.0.0.5',
        sharedSecret: 'topsecret',
        authPort: 1812,
        autoSaveInterval: 600,
        radiusAuth: 'chap',
      }),
    ).toEqual([
      'aaa ppsk-server radius-server primary 10.0.0.5 shared-secret topsecret auth-port 1812',
      'aaa ppsk-server auto-save-interval 600',
      'security-object Corp security private-psk radius-auth chap',
    ])
  })
  it('emits the RADIUS server without an optional shared secret or auth port', () => {
    expect(ppskRadiusCommands('Corp', { radiusServer: '10.0.0.5', radiusAuth: 'pap' })).toEqual([
      'aaa ppsk-server radius-server primary 10.0.0.5',
      'security-object Corp security private-psk radius-auth pap',
    ])
  })
  it('enables radius-auth bare (default PAP) and disables it with the no-form', () => {
    expect(ppskRadiusCommands('Corp', { radiusAuth: 'enable' })).toEqual([
      'security-object Corp security private-psk radius-auth',
    ])
    expect(ppskRadiusCommands('Corp', { radiusAuth: 'disable' })).toEqual([
      'no security-object Corp security private-psk radius-auth',
    ])
  })
  it('leaves the security object alone when only the device-wide server is set', () => {
    expect(ppskRadiusCommands('', { radiusServer: '10.0.0.5', sharedSecret: 's' })).toEqual([
      'aaa ppsk-server radius-server primary 10.0.0.5 shared-secret s',
    ])
  })
  it('returns nothing without any fields', () => {
    expect(ppskRadiusCommands('Corp', {})).toEqual([])
    expect(ppskRadiusCommands('', {})).toEqual([])
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

// Confirmed live on the AP230: each user-profile setting is its OWN line (a combined
// `user-profile <n> attribute 99 vlan-id 99` is REJECTED), the running-config later coalescing them. The
// numeric attribute keys the profile; an SSID binds it via `security-object <so> default-user-profile-attr`.
describe('userProfileCommands', () => {
  it('emits attribute first, then VLAN id, QoS policy and schedule as separate lines', () => {
    expect(
      userProfileCommands('staff', { attribute: 7, vlanId: 7, qosPolicy: 'def-user-qos', schedule: 'work-hours' }),
    ).toEqual([
      'user-profile staff attribute 7',
      'user-profile staff vlan-id 7',
      'user-profile staff qos-policy def-user-qos',
      'user-profile staff schedule work-hours',
    ])
  })
  it('uses a VLAN group when no VLAN id is given (id wins if both are present)', () => {
    expect(userProfileCommands('guest', { attribute: 3, vlanGroup: 'guest-vlans' })).toEqual([
      'user-profile guest attribute 3',
      'user-profile guest vlan-group guest-vlans',
    ])
    expect(userProfileCommands('guest', { attribute: 3, vlanId: 9, vlanGroup: 'guest-vlans' })).toEqual([
      'user-profile guest attribute 3',
      'user-profile guest vlan-id 9',
    ])
  })
  it('needs a name and a numeric attribute, otherwise nothing', () => {
    expect(userProfileCommands('staff', { vlanId: 7 })).toEqual([])
    expect(userProfileCommands('', { attribute: 7 })).toEqual([])
    expect(userProfileCommands('staff', { attribute: 'x', vlanId: 7 })).toEqual([])
    expect(userProfileCommands('  ', { attribute: 1 })).toEqual([])
  })
  it('allows attribute 0 (the default-profile attribute)', () => {
    expect(userProfileCommands('default-profile', { attribute: 0, vlanId: 1 })).toEqual([
      'user-profile default-profile attribute 0',
      'user-profile default-profile vlan-id 1',
    ])
  })
})

describe('bindUserProfileCommands', () => {
  it('binds a profile attribute to a security object as its default', () => {
    expect(bindUserProfileCommands('Corp', 7)).toEqual(['security-object Corp default-user-profile-attr 7'])
  })
  it('needs a security object and a numeric attribute', () => {
    expect(bindUserProfileCommands('', 7)).toEqual([])
    expect(bindUserProfileCommands('Corp', '')).toEqual([])
    expect(bindUserProfileCommands('Corp', 'x')).toEqual([])
  })
})

describe('removeUserProfileCommands', () => {
  it('negates the named profile', () => {
    expect(removeUserProfileCommands('staff')).toEqual(['no user-profile staff'])
  })
  it('emits nothing without a name', () => {
    expect(removeUserProfileCommands('')).toEqual([])
    expect(removeUserProfileCommands('  ')).toEqual([])
  })
})

// Confirmed live: each is its own line. perf-sentinel enable/guaranteed-bandwidth/action; ip/mac-policy default
// actions; security ip/mac-policy from-access|to-access <named policy>; qos-marker-map 8021p|diffserv <map>.
describe('userProfilePolicyCommands', () => {
  it('builds performance-sentinel, firewall bindings + default actions, and a marker-map', () => {
    expect(
      userProfilePolicyCommands('staff', {
        perfSentinel: 'enable',
        guaranteedBandwidth: 2000,
        perfAction: 'boost',
        ipDefaultAction: 'deny',
        ipPolicyFrom: 'block-smb',
        ipPolicyTo: 'allow-dns',
        macDefaultAction: 'permit',
        macPolicyFrom: 'mac-allow',
        qosMarkerMapType: 'diffserv',
        qosMarkerMap: 'voip-map',
      }),
    ).toEqual([
      'user-profile staff performance-sentinel enable',
      'user-profile staff performance-sentinel guaranteed-bandwidth 2000',
      'user-profile staff performance-sentinel action boost',
      'user-profile staff ip-policy-default-action deny',
      'user-profile staff security ip-policy from-access block-smb',
      'user-profile staff security ip-policy to-access allow-dns',
      'user-profile staff mac-policy-default-action permit',
      'user-profile staff security mac-policy from-access mac-allow',
      'user-profile staff qos-marker-map diffserv voip-map',
    ])
  })
  it('disables performance-sentinel via the no form and defaults the marker-map type to 8021p', () => {
    expect(userProfilePolicyCommands('staff', { perfSentinel: 'disable', qosMarkerMap: 'm1' })).toEqual([
      'no user-profile staff performance-sentinel enable',
      'user-profile staff qos-marker-map 8021p m1',
    ])
  })
  it('emits nothing without a profile name or with all-blank fields', () => {
    expect(userProfilePolicyCommands('', { perfSentinel: 'enable' })).toEqual([])
    expect(userProfilePolicyCommands('staff', {})).toEqual([])
  })
})

// Confirmed live: `ip-policy <name>` creates the group FIRST, then a combined rule line works; `any` is valid.
describe('ipPolicyCommands', () => {
  it('creates the policy group then adds a rule (defaulting from/to/service to any)', () => {
    expect(ipPolicyCommands('block-smb', { id: 1, action: 'deny', service: 'smb' })).toEqual([
      'ip-policy block-smb',
      'ip-policy block-smb id 1 from any to any service smb action deny',
    ])
  })
  it('emits only the create line when no full rule is given', () => {
    expect(ipPolicyCommands('empty', {})).toEqual(['ip-policy empty'])
    expect(ipPolicyCommands('empty', { id: 2 })).toEqual(['ip-policy empty']) // no action -> no rule
  })
  it('removes the policy and needs a name', () => {
    expect(ipPolicyCommands('block-smb', { remove: true })).toEqual(['no ip-policy block-smb'])
    expect(ipPolicyCommands('', { id: 1, action: 'deny' })).toEqual([])
  })
})

describe('macPolicyCommands', () => {
  it('creates and removes a MAC policy', () => {
    expect(macPolicyCommands('mac-allow')).toEqual(['mac-policy mac-allow'])
    expect(macPolicyCommands('mac-allow', { remove: true })).toEqual(['no mac-policy mac-allow'])
    expect(macPolicyCommands('')).toEqual([])
  })
})

// Confirmed live: `qos policy <n>` creates, `qos policy <n> user-profile <kbps> <weight>` (weight required).
describe('qosPolicyCommands', () => {
  it('enables QoS, creates the policy and sets the user-profile rate + weight', () => {
    expect(qosPolicyCommands('voip', { enableQos: true, rateKbps: 5000, weight: 20 })).toEqual([
      'qos enable',
      'qos policy voip',
      'qos policy voip user-profile 5000 20',
    ])
  })
  it('defaults the weight to 10 and can just create the policy', () => {
    expect(qosPolicyCommands('voip', { rateKbps: 5000 })).toEqual([
      'qos policy voip',
      'qos policy voip user-profile 5000 10',
    ])
    expect(qosPolicyCommands('voip', {})).toEqual(['qos policy voip'])
  })
  it('removes the policy and needs a name', () => {
    expect(qosPolicyCommands('voip', { remove: true })).toEqual(['no qos policy voip'])
    expect(qosPolicyCommands('')).toEqual([])
  })
})

describe('ssidQosCommands', () => {
  it('binds classifier/marker profiles and toggles WMM', () => {
    expect(ssidQosCommands('Voice', { qosClassifier: 'voip-class', qosMarker: 'voip-mark', wmm: 'enable' })).toEqual([
      'ssid Voice qos-classifier voip-class',
      'ssid Voice qos-marker voip-mark',
      'ssid Voice wmm',
    ])
    expect(ssidQosCommands('Voice', { wmm: 'disable' })).toEqual(['no ssid Voice wmm'])
  })
  it('emits nothing without an SSID or fields', () => {
    expect(ssidQosCommands('', { wmm: 'enable' })).toEqual([])
    expect(ssidQosCommands('Voice', {})).toEqual([])
  })
})

// Confirmed live: `lldp`/`no lldp` toggle, `lldp timer|holdtime|max-entries <n>`, `lldp receive-only` toggle.
describe('lldpCommands', () => {
  it('enables LLDP and sets timers/limits', () => {
    expect(lldpCommands({ enable: 'enable', timer: 45, holdtime: 120, receiveOnly: 'enable', maxEntries: 100 })).toEqual([
      'lldp',
      'lldp timer 45',
      'lldp holdtime 120',
      'lldp receive-only',
      'lldp max-entries 100',
    ])
  })
  it('disables LLDP and receive-only via the no forms', () => {
    expect(lldpCommands({ enable: 'disable', receiveOnly: 'disable' })).toEqual(['no lldp', 'no lldp receive-only'])
  })
  it('emits nothing for an empty request', () => {
    expect(lldpCommands({})).toEqual([])
  })
})

// Confirmed live: net route `ip route net <ip> <mask> gateway <gw> [metric]`; host route omits the mask.
describe('staticRouteCommands', () => {
  it('builds a net route with an optional metric', () => {
    expect(staticRouteCommands({ type: 'net', dest: '10.9.9.0', netmask: '255.255.255.0', gateway: '192.168.1.1' })).toEqual([
      'ip route net 10.9.9.0 255.255.255.0 gateway 192.168.1.1',
    ])
    expect(
      staticRouteCommands({ type: 'net', dest: '10.9.9.0', netmask: '255.255.255.0', gateway: '192.168.1.1', metric: 5 }),
    ).toEqual(['ip route net 10.9.9.0 255.255.255.0 gateway 192.168.1.1 metric 5'])
  })
  it('builds a host route (no netmask) and the remove form', () => {
    expect(staticRouteCommands({ type: 'host', dest: '10.9.9.9', gateway: '192.168.1.1' })).toEqual([
      'ip route host 10.9.9.9 gateway 192.168.1.1',
    ])
    expect(staticRouteCommands({ type: 'host', dest: '10.9.9.9', gateway: '192.168.1.1', remove: true })).toEqual([
      'no ip route host 10.9.9.9 gateway 192.168.1.1',
    ])
  })
  it('needs a dest + gateway (and a netmask for a net route)', () => {
    expect(staticRouteCommands({ type: 'net', dest: '10.9.9.0', gateway: '192.168.1.1' })).toEqual([])
    expect(staticRouteCommands({ type: 'host', dest: '', gateway: '192.168.1.1' })).toEqual([])
    expect(staticRouteCommands({ type: 'host', dest: '10.9.9.9' })).toEqual([])
  })
})

// Schedule objects. Grammar confirmed live on an AP230 (HiveOS 10.6r1a) — applied to the running-config and
// reverted (`no schedule <name>`):
//   schedule <name> recurrent [date-range <d> [to <d>]] [weekday-range <Day> [to <Day>]] [time-range <hh:mm> to <hh:mm>]
//   schedule <name> once <date> <time> to <date> <time>
describe('scheduleCommands', () => {
  it('builds a recurrent weekday + time schedule', () => {
    expect(
      scheduleCommands('work-hours', { type: 'recurrent', weekdayStart: 'Monday', weekdayEnd: 'Friday', timeStart: '08:00', timeEnd: '17:00' }),
    ).toEqual(['schedule work-hours recurrent weekday-range Monday to Friday time-range 08:00 to 17:00'])
  })
  it('builds a single-weekday recurrent schedule (no end weekday)', () => {
    expect(scheduleCommands('sun', { type: 'recurrent', weekdayStart: 'Sunday', timeStart: '00:00', timeEnd: '06:00' })).toEqual([
      'schedule sun recurrent weekday-range Sunday time-range 00:00 to 06:00',
    ])
  })
  it('includes an optional date range when a start date is given', () => {
    expect(
      scheduleCommands('promo', { type: 'recurrent', dateStart: '2026-01-01', dateEnd: '2026-03-31', timeStart: '09:00', timeEnd: '12:00' }),
    ).toEqual(['schedule promo recurrent date-range 2026-01-01 to 2026-03-31 time-range 09:00 to 12:00'])
  })
  it('builds a one-time schedule (date + time start, to date + time end)', () => {
    expect(
      scheduleCommands('xmas', { type: 'once', dateStart: '2026-12-25', timeStart: '08:00', dateEnd: '2026-12-26', timeEnd: '17:00' }),
    ).toEqual(['schedule xmas once 2026-12-25 08:00 to 2026-12-26 17:00'])
  })
  it('drops a half time-range (only one of start/end) but keeps the weekday', () => {
    expect(scheduleCommands('w', { type: 'recurrent', weekdayStart: 'Monday', timeStart: '08:00' })).toEqual([
      'schedule w recurrent weekday-range Monday',
    ])
  })
  it('emits nothing without a name, with no recurrent sub-range, or with an incomplete one-time', () => {
    expect(scheduleCommands('', { type: 'recurrent', weekdayStart: 'Monday' })).toEqual([])
    expect(scheduleCommands('empty', { type: 'recurrent' })).toEqual([])
    expect(scheduleCommands('half', { type: 'once', dateStart: '2026-12-25', timeStart: '08:00' })).toEqual([])
  })
  it('removes a schedule by name', () => {
    expect(removeScheduleCommands('work-hours')).toEqual(['no schedule work-hours'])
    expect(removeScheduleCommands('  ')).toEqual([])
  })
})

// Scheduled reboot. Grammar confirmed live on an AP230 (applied → `show reboot schedule` → `no reboot schedule`):
//   reboot schedule daily every <1-365> day(s) time <hh:mm:ss>
//   reboot schedule weekly every <1-52> week(s) <Weekday> time <hh:mm:ss>
// LIVE QUIRK: the daily/weekly `reboot schedule` forms are NON-interactive (apply-config safe), but `reboot date`
// and `reboot offset` prompt "Do you really want to reboot? (Y/N)" — which would hang the exec channel — so only
// the recurring forms are built here.
describe('rebootScheduleCommands', () => {
  it('builds a daily reboot schedule (time padded to hh:mm:ss)', () => {
    expect(rebootScheduleCommands({ type: 'daily', interval: 1, time: '04:30' })).toEqual([
      'reboot schedule daily every 1 day(s) time 04:30:00',
    ])
  })
  it('builds a weekly reboot schedule with a weekday', () => {
    expect(rebootScheduleCommands({ type: 'weekly', interval: 2, weekday: 'Sunday', time: '03:00:00' })).toEqual([
      'reboot schedule weekly every 2 week(s) Sunday time 03:00:00',
    ])
  })
  it('needs a positive interval, a time, and (weekly) a weekday', () => {
    expect(rebootScheduleCommands({ type: 'daily', interval: 0, time: '04:30' })).toEqual([])
    expect(rebootScheduleCommands({ type: 'daily', interval: 1, time: '' })).toEqual([])
    expect(rebootScheduleCommands({ type: 'weekly', interval: 1, time: '03:00', weekday: '' })).toEqual([])
  })
  it('cancels a scheduled reboot', () => {
    expect(cancelRebootScheduleCommands()).toEqual(['no reboot schedule'])
  })
})
