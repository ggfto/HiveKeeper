import { describe, it, expect } from 'vitest'
import { radioOptions, defaultRadio, bandForChannel } from './radioInterfaces'

// The AP410C-1 as it actually reports itself: three radios, the second and third both on 5 GHz.
const ap410c = {
  radios: [
    { name: 'Wifi0', channel: 6 },
    { name: 'Wifi1', channel: 165 },
    { name: 'Wifi2', channel: 44 },
  ],
}

describe('bandForChannel', () => {
  it('reads the band from the channel', () => {
    expect(bandForChannel(6)).toBe('2.4 GHz')
    expect(bandForChannel(14)).toBe('2.4 GHz')
    expect(bandForChannel(36)).toBe('5 GHz')
    expect(bandForChannel(165)).toBe('5 GHz')
  })

  it('says nothing when the radio is down or the channel is unknown', () => {
    expect(bandForChannel(null)).toBeNull()
    expect(bandForChannel(0)).toBeNull()
    expect(bandForChannel('auto')).toBeNull()
  })
})

describe('radioOptions', () => {
  it('offers every radio the device reported, not a fixed pair', () => {
    expect(radioOptions(ap410c).map((o) => o.value)).toEqual(['wifi0', 'wifi1', 'wifi2'])
  })

  it('labels each radio with the band it is actually on', () => {
    // wifi1 AND wifi2 are 5 GHz here — the wifi1-is-5GHz convention cannot express that.
    expect(radioOptions(ap410c).map((o) => o.label)).toEqual([
      'wifi0 (2.4 GHz)',
      'wifi1 (5 GHz)',
      'wifi2 (5 GHz)',
    ])
  })

  it('omits the band for a radio that is down', () => {
    expect(radioOptions({ radios: [{ name: 'Wifi0', channel: null }] })[0].label).toBe('wifi0')
  })

  it('falls back to two radios when the device has not been inventoried', () => {
    expect(radioOptions(undefined).map((o) => o.value)).toEqual(['wifi0', 'wifi1'])
    expect(radioOptions({ radios: [] }).map((o) => o.value)).toEqual(['wifi0', 'wifi1'])
  })
})

describe('defaultRadio', () => {
  it('preselects the first radio the device reported', () => {
    expect(defaultRadio(ap410c)).toBe('wifi0')
    expect(defaultRadio(undefined)).toBe('wifi0')
  })
})
