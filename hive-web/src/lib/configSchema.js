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

const opts = (...vals) => vals.map((v) => ({ label: v, value: v }))
// Confirmed live on the AP230 via `?`: `logging trap level ?` and `logging facility ?`.
const SEVERITY = opts('emerg', 'alert', 'crit', 'err', 'warning', 'notice', 'info')
const FACILITY = opts('local0', 'local1', 'local2', 'local3', 'local4', 'local5', 'local6', 'local7', 'auth', 'authpriv', 'security', 'user')

const SNMP_SECTION = {
  id: 'snmp',
  label: 'SNMP',
  icon: Activity,
  hint: 'SNMP identity, a read-only community (v2c), and a trap host for your NMS.',
  fields: [
    { key: 'location', label: 'Location', placeholder: 'Rack 3' },
    { key: 'contact', label: 'Contact', placeholder: 'noc@example.com' },
    { key: 'community', label: 'Read community (v2c)', placeholder: 'public' },
    { key: 'trapHost', label: 'Trap host (NMS)', placeholder: '192.168.1.50' },
    { key: 'trapCommunity', label: 'Trap community', placeholder: 'public' },
  ],
  toCli: (v) => {
    const c = []
    if (t(v.location)) c.push(`snmp location ${t(v.location)}`)
    if (t(v.contact)) c.push(`snmp contact ${t(v.contact)}`)
    if (t(v.community)) c.push(`snmp reader version v2c community ${t(v.community)}`)
    if (t(v.trapHost)) {
      c.push(`snmp trap-host v2c ${t(v.trapHost)}${t(v.trapCommunity) ? ` community ${t(v.trapCommunity)}` : ''}`)
    }
    return c
  },
}

const SYSLOG_SECTION = {
  id: 'syslog',
  label: 'Syslog',
  icon: ScrollText,
  hint: 'Forward logs to a syslog server (optional port + severity floor) and set the syslog facility.',
  fields: [
    { key: 'server', label: 'Syslog server', placeholder: '192.168.1.250' },
    { key: 'port', label: 'Port', placeholder: '514' },
    { key: 'severity', label: 'Min severity', type: 'select', options: SEVERITY, placeholder: '(default: info)' },
    { key: 'facility', label: 'Facility', type: 'select', options: FACILITY, placeholder: '(default: local6)' },
  ],
  toCli: (v) => {
    const c = []
    if (t(v.server)) {
      let line = `logging server ${t(v.server)}`
      if (t(v.port)) line += ` port ${t(v.port)}`
      if (t(v.severity)) line += ` level ${t(v.severity)}`
      c.push(line)
    }
    if (t(v.facility)) c.push(`logging facility ${t(v.facility)}`)
    return c
  },
}

/** The telemetry-config sections shown under the Monitoring tab (DNS/NTP live in the Network section instead). */
export const MONITORING_SECTIONS = [SNMP_SECTION, SYSLOG_SECTION]
