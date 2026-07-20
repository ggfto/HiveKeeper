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
 * band: '2.4' | '5' when the caller knows it; otherwise inferred from channel, then from iface.
 * channel/power/width may be numbers, numeric strings, 'auto', or
 * blank; non-numeric values (auto/blank) are skipped. width (MHz) is optional — only the live read knows it.
 * -> [{ level: 'warning' | 'info', code, message }], empty when everything is within best practice.
 */

const toNum = (v) => {
  if (v === null || v === undefined) return null
  const s = String(v).trim().toLowerCase()
  if (s === '' || s === 'auto') return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

// The only three non-overlapping 20 MHz channels in 2.4 GHz.
/**
 * Which band a radio is on, most reliable signal first.
 *
 * The channel decides it. The wifi0 = 2.4 / wifi1 = 5 naming convention is only a fallback, because it holds
 * on a two-radio AP and breaks on an AP410C-1, where wifi1 AND wifi2 are both 5 GHz. Keying solely off the
 * interface name meant every advisory silently returned nothing for a third radio — no warning, no error,
 * just an AP quietly exempt from every best-practice check.
 */
const band = ({ band: explicit, iface, channel }) => {
  if (explicit === '2.4' || explicit === '5') return explicit
  const ch = toNum(channel)
  if (ch !== null && ch > 0) return ch <= 14 ? '2.4' : '5'
  return iface === 'wifi0' ? '2.4' : iface === 'wifi1' ? '5' : null
}

const NON_OVERLAPPING_24 = new Set([1, 6, 11])
// HiveOS radio power is 1-20 dBm; at/above this we consider the cell large enough to warn about.
const HIGH_POWER_DBM = 18

export function radioAdvisories({ iface, channel, power, width, band: explicitBand } = {}) {
  const out = []
  const b = band({ band: explicitBand, iface, channel })
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
