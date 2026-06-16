import { describe, it, expect } from 'vitest'
import { filterStations, sortBySignal, countBySsid } from './stations'

const stations = [
  { mac: 'aa:bb', ipAddress: '10.0.0.9', ssid: 'guest', hostname: 'phone', rssi: -55 },
  { mac: 'cc:dd', ipAddress: '10.0.0.7', ssid: 'corp', hostname: 'laptop', rssi: -70 },
  { mac: 'ee:ff', ipAddress: '10.0.0.3', ssid: 'guest', hostname: null, rssi: null },
]

describe('filterStations', () => {
  it('returns all when the query is blank', () => expect(filterStations(stations, '')).toHaveLength(3))

  it('matches MAC, IP, SSID or hostname (case-insensitive)', () => {
    expect(filterStations(stations, 'CORP').map((s) => s.mac)).toEqual(['cc:dd'])
    expect(filterStations(stations, '10.0.0.9').map((s) => s.mac)).toEqual(['aa:bb'])
    expect(filterStations(stations, 'phone').map((s) => s.mac)).toEqual(['aa:bb'])
    expect(filterStations(stations, 'guest')).toHaveLength(2)
  })

  it('is null-safe', () => expect(filterStations(null, 'x')).toEqual([]))
})

describe('sortBySignal', () => {
  it('puts the strongest signal first by default, nulls last', () => {
    expect(sortBySignal(stations).map((s) => s.mac)).toEqual(['aa:bb', 'cc:dd', 'ee:ff'])
  })

  it('ascending puts the weakest (non-null) first, nulls still last', () => {
    expect(sortBySignal(stations, 'asc').map((s) => s.mac)).toEqual(['cc:dd', 'aa:bb', 'ee:ff'])
  })

  it('does not mutate the input', () => {
    const before = [...stations]
    sortBySignal(stations)
    expect(stations).toEqual(before)
  })
})

describe('countBySsid', () => {
  it('counts per SSID, most-populated first', () => {
    expect(countBySsid(stations)).toEqual([
      { ssid: 'guest', count: 2 },
      { ssid: 'corp', count: 1 },
    ])
  })
})
