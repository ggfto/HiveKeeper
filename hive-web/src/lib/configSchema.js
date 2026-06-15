import { Globe2, Clock, Activity, ScrollText } from 'lucide-react'

/**
 * Declarative config categories whose CLI is a simple set of field -> line mappings. Adding a category here is
 * one entry (no new component) — the scalable answer to the AP's large config surface. Each section's grammar
 * was confirmed live on the AP230 via `?` (see scripts/hk-cli-explore.py). The lines apply through apply-config.
 *
 * A section: { id, label, icon, hint?, fields: [{ key, label, placeholder? }], toCli(values) -> string[] }.
 */
const t = (s) => (s || '').trim()

export const SCHEMA_SECTIONS = [
  {
    id: 'dns',
    label: 'DNS',
    icon: Globe2,
    hint: 'Domain suffix and DNS server.',
    fields: [
      { key: 'domainName', label: 'Domain name', placeholder: 'example.com' },
      { key: 'serverIp', label: 'DNS server IP', placeholder: '1.1.1.1' },
    ],
    toCli: (v) => {
      const c = []
      if (t(v.domainName)) c.push(`dns domain-name ${t(v.domainName)}`)
      if (t(v.serverIp)) c.push(`dns server-ip ${t(v.serverIp)}`)
      return c
    },
  },
  {
    id: 'ntp',
    label: 'NTP',
    icon: Clock,
    hint: 'Time sync against an NTP server.',
    fields: [{ key: 'server', label: 'NTP server', placeholder: 'pool.ntp.org' }],
    toCli: (v) => (t(v.server) ? [`ntp server ${t(v.server)}`, 'ntp enable'] : []),
  },
  {
    id: 'snmp',
    label: 'SNMP',
    icon: Activity,
    hint: 'SNMP location and contact.',
    fields: [
      { key: 'location', label: 'Location', placeholder: 'Rack 3' },
      { key: 'contact', label: 'Contact', placeholder: 'noc@example.com' },
    ],
    toCli: (v) => {
      const c = []
      if (t(v.location)) c.push(`snmp location ${t(v.location)}`)
      if (t(v.contact)) c.push(`snmp contact ${t(v.contact)}`)
      return c
    },
  },
  {
    id: 'syslog',
    label: 'Syslog',
    icon: ScrollText,
    hint: 'Forward logs to a syslog server.',
    fields: [{ key: 'server', label: 'Syslog server', placeholder: '192.168.1.250' }],
    toCli: (v) => (t(v.server) ? [`logging server ${t(v.server)}`] : []),
  },
]
