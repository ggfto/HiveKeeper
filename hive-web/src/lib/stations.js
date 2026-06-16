/**
 * Pure helpers for the AP's connected-clients (station) table: free-text search, sort by signal, and a
 * per-SSID breakdown. Kept out of the component so they are fully unit-testable.
 */

/** Filter stations by a query matched (case-insensitive substring) against MAC, IP, SSID, and hostname. */
export function filterStations(stations, query = '') {
  const q = (query || '').trim().toLowerCase()
  if (!q) return stations || []
  return (stations || []).filter((s) =>
    [s.mac, s.ipAddress, s.ssid, s.hostname].some((v) => (v || '').toLowerCase().includes(q)),
  )
}

/** Sort stations by RSSI — strongest first by default ('desc'). A missing RSSI always sorts last. New array. */
export function sortBySignal(stations, dir = 'desc') {
  const sign = dir === 'asc' ? 1 : -1
  return [...(stations || [])].sort((a, b) => {
    if (a.rssi == null && b.rssi == null) return 0
    if (a.rssi == null) return 1 // nulls last, regardless of direction
    if (b.rssi == null) return -1
    return (a.rssi - b.rssi) * sign
  })
}

/** Count stations per SSID, most-populated first. Unknown SSID buckets under an em dash. -> [{ ssid, count }]. */
export function countBySsid(stations) {
  const counts = new Map()
  for (const s of stations || []) {
    const key = s.ssid || '—'
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return [...counts.entries()]
    .map(([ssid, count]) => ({ ssid, count }))
    .sort((a, b) => b.count - a.count)
}
