import { describe, it, expect } from 'vitest'
import { SCHEMA_SECTIONS } from './configSchema'

const byId = (id) => SCHEMA_SECTIONS.find((s) => s.id === id)

// CLI confirmed live on the AP230 via `?`: dns domain-name/server-ip, ntp server, snmp location/contact,
// logging server.
describe('configSchema toCli builders', () => {
  it('ntp builds the server line + enable', () => {
    expect(byId('ntp').toCli({ server: 'pool.ntp.org' })).toEqual(['ntp server pool.ntp.org', 'ntp enable'])
  })
  it('ntp emits nothing without a server', () => {
    expect(byId('ntp').toCli({})).toEqual([])
  })
  it('dns builds domain + server-ip, skipping blanks', () => {
    expect(byId('dns').toCli({ serverIp: '1.1.1.1' })).toEqual(['dns server-ip 1.1.1.1'])
    expect(byId('dns').toCli({ domainName: 'ex.com', serverIp: '1.1.1.1' })).toEqual([
      'dns domain-name ex.com',
      'dns server-ip 1.1.1.1',
    ])
  })
  it('snmp builds location + contact', () => {
    expect(byId('snmp').toCli({ location: 'Rack 3', contact: 'noc' })).toEqual([
      'snmp location Rack 3',
      'snmp contact noc',
    ])
  })
  it('syslog builds the logging server line', () => {
    expect(byId('syslog').toCli({ server: '192.168.1.250' })).toEqual(['logging server 192.168.1.250'])
  })

  it('every section declares id/label/icon/fields/toCli', () => {
    for (const s of SCHEMA_SECTIONS) {
      expect(s.id && s.label && s.icon && Array.isArray(s.fields) && typeof s.toCli === 'function').toBeTruthy()
    }
  })
})
