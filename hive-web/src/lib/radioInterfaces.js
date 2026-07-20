/**
 * The radio interfaces to offer for a device, derived from what the device actually reported.
 *
 * Radio count is not a constant of the platform. An AP230 and an AP630 have two radios; an AP410C-1 has
 * three, and its second and third are BOTH 5 GHz; nothing stops a future model having four. Hardcoding a
 * wifi0/wifi1 pair means the extra radios are simply absent from the UI — no error, just capacity the
 * operator cannot see or configure.
 */

// Shown only when the device has not been inventoried yet, so the form still renders something sensible.
// Marked as a guess in the labels rather than presented as fact.
const UNKNOWN_FALLBACK = [
  { label: 'wifi0', value: 'wifi0' },
  { label: 'wifi1', value: 'wifi1' },
]

/**
 * The band a radio is on, read from the channel it is actually using.
 *
 * Deliberately not inferred from the interface name: the wifi0 = 2.4 / wifi1 = 5 convention holds on a
 * two-radio AP and breaks on the AP410C-1, where wifi1 and wifi2 are both 5 GHz. A radio that is down has
 * no channel, and then we say nothing rather than guess.
 */
export function bandForChannel(channel) {
  const ch = Number(channel)
  if (!Number.isFinite(ch) || ch <= 0) return null
  if (ch <= 14) return '2.4 GHz'
  return '5 GHz'
}

/** Select options for a device's radios: `[{ label, value }]`, value being the CLI name (lowercased). */
export function radioOptions(device) {
  const radios = device?.radios ?? []
  if (!radios.length) return UNKNOWN_FALLBACK
  return radios.map((radio) => {
    const value = String(radio.name || '').toLowerCase()
    const band = bandForChannel(radio.channel)
    return { label: band ? `${value} (${band})` : value, value }
  })
}

/** The first radio to preselect in a form, or `wifi0` when the device has not been inventoried. */
export function defaultRadio(device) {
  return radioOptions(device)[0]?.value ?? 'wifi0'
}
