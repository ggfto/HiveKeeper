/**
 * Best-practice advisories for an AP radio config. Pure, so both the config form (the channel/power a user is
 * about to apply) and the monitoring panel (the channel/width/power read back from `show acsp`) can flag
 * settings likely to hurt latency under client density.
 *
 * Why these rules: Wi-Fi is a shared, half-duplex medium (CSMA/CA) — only one station transmits per channel at
 * a time. Wide channels and high TX power don't add capacity; they shrink the pool of non-overlapping channels
 * and enlarge cells, raising airtime contention and co-channel interference once many clients/APs share the
 * air. None of these block an apply — the operator may have a reason — they just surface the trade-off.
 *
 * iface: 'wifi0' (2.4 GHz) | 'wifi1' (5 GHz). channel/power/width may be numbers, numeric strings, 'auto', or
 * blank; non-numeric values (auto/blank) are skipped. width (MHz) is optional — only the live read knows it.
 * -> [{ level: 'warning' | 'info', code, message }], empty when everything is within best practice.
 */

const band = (iface) => (iface === 'wifi0' ? '2.4' : iface === 'wifi1' ? '5' : null)

const toNum = (v) => {
  if (v === null || v === undefined) return null
  const s = String(v).trim().toLowerCase()
  if (s === '' || s === 'auto') return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

// The only three non-overlapping 20 MHz channels in 2.4 GHz.
const NON_OVERLAPPING_24 = new Set([1, 6, 11])
// HiveOS radio power is 1-20 dBm; at/above this we consider the cell large enough to warn about.
const HIGH_POWER_DBM = 18

export function radioAdvisories({ iface, channel, power, width } = {}) {
  const out = []
  const b = band(iface)
  const ch = toNum(channel)
  const pw = toNum(power)
  const w = toNum(width)

  // Channel width: anything wider than 20 MHz on 2.4 GHz overlaps the band; wide channels on 5 GHz eat reuse.
  if (b === '2.4' && w !== null && w > 20) {
    out.push({
      level: 'warning',
      code: 'width-24ghz',
      message:
        `${w} MHz on 2.4 GHz: the band only fits three non-overlapping 20 MHz channels (1, 6, 11), so a wider ` +
        `channel overlaps neighbors and raises co-channel interference and latency under load. Use 20 MHz on 2.4 GHz.`,
    })
  } else if (b === '5' && w !== null && w >= 160) {
    out.push({
      level: 'warning',
      code: 'width-160',
      message:
        `160 MHz uses eight 20 MHz channels, leaving little room for channel reuse. With several APs or many ` +
        `clients this drives up contention; 40-80 MHz is usually a better trade-off in dense deployments.`,
    })
  } else if (b === '5' && w !== null && w >= 80) {
    out.push({
      level: 'info',
      code: 'width-80',
      message:
        `80 MHz consumes four 20 MHz channels. In dense deployments (many APs/clients) a narrower channel ` +
        `(40 MHz) gives more channel reuse and lower latency.`,
    })
  }

  // 2.4 GHz channel overlap: only 1/6/11 don't step on each other.
  if (b === '2.4' && ch !== null && !NON_OVERLAPPING_24.has(ch)) {
    out.push({
      level: 'warning',
      code: 'channel-24-overlap',
      message:
        `Channel ${ch} on 2.4 GHz overlaps adjacent channels. Use 1, 6 or 11 (the only non-overlapping 20 MHz ` +
        `channels) to avoid interference with neighboring APs.`,
    })
  }

  // High TX power: large cells cause AP<->client asymmetry and co-channel interference between APs.
  if (pw !== null && pw >= HIGH_POWER_DBM) {
    out.push({
      level: 'warning',
      code: 'high-power',
      message:
        `TX power ${pw} dBm is near maximum. Large cells cause AP<->client asymmetry (sticky clients, weak ` +
        `uplink) and co-channel interference between APs, raising airtime contention and latency as clients ` +
        `connect. In dense areas lower the power or use auto (ACSP).`,
    })
  }

  return out
}
