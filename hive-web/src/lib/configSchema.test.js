import { describe, it, expect } from 'vitest'
import { MONITORING_SECTIONS, DNS_SECTION, NTP_SECTION } from './configSchema'

const byId = (id) => MONITORING_SECTIONS.find((s) => s.id === id)

// CLI confirmed live on the AP230 via `?`.
describe('DNS_SECTION', () => {
  it('builds domain + primary/secondary/tertiary servers, skipping blanks', () => {
    expect(DNS_SECTION.toCli({ primary: '1.1.1.1' })).toEqual(['dns server-ip 1.1.1.1'])
    expect(
      DNS_SECTION.toCli({ domainName: 'ex.com', primary: '1.1.1.1', secondary: '8.8.8.8', tertiary: '9.9.9.9' }),
    ).toEqual([
      'dns domain-name ex.com',
      'dns server-ip 1.1.1.1',
      'dns server-ip 8.8.8.8 second',
      'dns server-ip 9.9.9.9 third',
    ])
  })
})

describe('NTP_SECTION', () => {
  it('builds server + interval + enable', () => {
    expect(NTP_SECTION.toCli({ server: 'pool.ntp.org', interval: '120' })).toEqual([
      'ntp server pool.ntp.org',
      'ntp interval 120',
      'ntp enable',
    ])
  })
  it('emits nothing without a server or interval', () => {
    expect(NTP_SECTION.toCli({})).toEqual([])
  })
})

describe('MONITORING_SECTIONS (telemetry config)', () => {
  it('are SNMP and Syslog', () => {
    expect(MONITORING_SECTIONS.map((s) => s.id)).toEqual(['snmp', 'syslog'])
  })
  it('SNMP builds location + contact', () => {
    expect(byId('snmp').toCli({ location: 'Rack 3', contact: 'noc' })).toEqual([
      'snmp location Rack 3',
      'snmp contact noc',
    ])
  })
  it('SNMP builds a v2c read community + trap host (with optional trap community)', () => {
    expect(byId('snmp').toCli({ community: 'public', trapHost: '192.168.1.50', trapCommunity: 'secret' })).toEqual([
      'snmp reader version v2c community public',
      'snmp trap-host v2c 192.168.1.50 community secret',
    ])
    expect(byId('snmp').toCli({ trapHost: '192.168.1.50' })).toEqual(['snmp trap-host v2c 192.168.1.50'])
  })
  it('Syslog builds the logging server line', () => {
    expect(byId('syslog').toCli({ server: '192.168.1.250' })).toEqual(['logging server 192.168.1.250'])
  })
  it('Syslog composes server + port + severity on one line, facility separately', () => {
    expect(byId('syslog').toCli({ server: '10.0.0.9', port: '514', severity: 'warning', facility: 'local0' })).toEqual([
      'logging server 10.0.0.9 port 514 level warning',
      'logging facility local0',
    ])
    expect(byId('syslog').toCli({ facility: 'local6' })).toEqual(['logging facility local6'])
  })
  it('every section declares id/label/icon/fields/toCli', () => {
    for (const s of MONITORING_SECTIONS) {
      expect(s.id && s.label && s.icon && Array.isArray(s.fields) && typeof s.toCli === 'function').toBeTruthy()
    }
  })
})
