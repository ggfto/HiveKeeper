import { Globe2, Clock, Activity, ScrollText } from 'lucide-react'

/**
 * Declarative config categories whose CLI is a simple set of field -> line mappings, rendered by one generic
 * SchemaConfigForm. Each section's grammar was confirmed live on the AP230 via `?` (scripts/hk-cli-explore.py)
 * and applies through apply-config. A section: { id, label, icon, hint?, fields[], toCli(values)->string[] }.
 *
 * DNS and NTP are exported individually because they live inside the Network section (alongside the management
 * IP); SNMP and Syslog are grouped under the Monitoring tab (MONITORING_SECTIONS), next to the live snapshot.
 */
const t = (s) => (s || '').trim()

export const DNS_SECTION = {
  id: 'dns',
  label: 'DNS',
  icon: Globe2,
  hint: 'Domain suffix and up to three DNS servers.',
  fields: [
    { key: 'domainName', label: 'Domain name', placeholder: 'example.com' },
    { key: 'primary', label: 'DNS server (primary)', placeholder: '1.1.1.1' },
    { key: 'secondary', label: 'DNS server (secondary)', placeholder: '8.8.8.8' },
    { key: 'tertiary', label: 'DNS server (tertiary)', placeholder: '' },
  ],
  toCli: (v) => {
    const c = []
    if (t(v.domainName)) c.push(`dns domain-name ${t(v.domainName)}`)
    if (t(v.primary)) c.push(`dns server-ip ${t(v.primary)}`)
    if (t(v.secondary)) c.push(`dns server-ip ${t(v.secondary)} second`)
    if (t(v.tertiary)) c.push(`dns server-ip ${t(v.tertiary)} third`)
    return c
  },
}

export const NTP_SECTION = {
  id: 'ntp',
  label: 'NTP',
  icon: Clock,
  hint: 'Time sync server and interval.',
  fields: [
    { key: 'server', label: 'NTP server', placeholder: 'pool.ntp.org' },
    { key: 'interval', label: 'Sync interval (min, 60-10080)', placeholder: '180' },
  ],
  toCli: (v) => {
    const c = []
    if (t(v.server)) c.push(`ntp server ${t(v.server)}`)
    if (t(v.interval)) c.push(`ntp interval ${t(v.interval)}`)
    if (t(v.server) || t(v.interval)) c.push('ntp enable')
    return c
  },
}

const SNMP_SECTION = {
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
}

const SYSLOG_SECTION = {
  id: 'syslog',
  label: 'Syslog',
  icon: ScrollText,
  hint: 'Forward logs to a syslog server.',
  fields: [{ key: 'server', label: 'Syslog server', placeholder: '192.168.1.250' }],
  toCli: (v) => (t(v.server) ? [`logging server ${t(v.server)}`] : []),
}

/** The telemetry-config sections shown under the Monitoring tab (DNS/NTP live in the Network section instead). */
export const MONITORING_SECTIONS = [SNMP_SECTION, SYSLOG_SECTION]
