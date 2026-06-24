import { describe, it, expect } from 'vitest'
import { radioAdvisories } from './radioAdvisories'

// Wi-Fi is a shared half-duplex medium (CSMA/CA): one station transmits per channel at a time. Wide channels
// and high TX power don't add capacity, they shrink the non-overlapping channel pool and enlarge cells, which
// raises airtime contention / co-channel interference and hurts latency once many clients share the air. These
// advisories flag the radio settings most likely to cause that.
describe('radioAdvisories', () => {
  it('is silent for a sensible 2.4 GHz config (ch 6, 20 MHz, moderate power)', () => {
    expect(radioAdvisories({ iface: 'wifi0', channel: '6', power: '12', width: 20 })).toEqual([])
  })

  it('is silent for a sensible 5 GHz config (ch 36, 40 MHz, moderate power)', () => {
    expect(radioAdvisories({ iface: 'wifi1', channel: '36', power: '14', width: 40 })).toEqual([])
  })

  it('warns about a wide channel on 2.4 GHz', () => {
    const out = radioAdvisories({ iface: 'wifi0', width: 40 })
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ level: 'warning', code: 'width-24ghz' })
  })

  it('warns about a non-overlapping channel on 2.4 GHz (not 1/6/11)', () => {
    const out = radioAdvisories({ iface: 'wifi0', channel: '3' })
    expect(out.map((a) => a.code)).toContain('channel-24-overlap')
    expect(out.find((a) => a.code === 'channel-24-overlap').level).toBe('warning')
  })

  it('accepts 1, 6 and 11 on 2.4 GHz without complaint', () => {
    for (const ch of ['1', '6', '11']) {
      expect(radioAdvisories({ iface: 'wifi0', channel: ch })).toEqual([])
    }
  })

  it('flags 80 MHz on 5 GHz as info and 160 MHz as a warning', () => {
    const eighty = radioAdvisories({ iface: 'wifi1', width: 80 })
    expect(eighty).toEqual([{ level: 'info', code: 'width-80', message: expect.any(String) }])
    const oneSixty = radioAdvisories({ iface: 'wifi1', width: 160 })
    expect(oneSixty).toEqual([{ level: 'warning', code: 'width-160', message: expect.any(String) }])
  })

  it('does not flag channel width on 5 GHz at 20/40 MHz', () => {
    expect(radioAdvisories({ iface: 'wifi1', width: 20 })).toEqual([])
    expect(radioAdvisories({ iface: 'wifi1', width: 40 })).toEqual([])
  })

  it('warns about high TX power on either band', () => {
    expect(radioAdvisories({ iface: 'wifi1', power: '20' }).map((a) => a.code)).toContain('high-power')
    expect(radioAdvisories({ iface: 'wifi0', power: '18' }).map((a) => a.code)).toContain('high-power')
  })

  it('does not warn about power below the high-power threshold', () => {
    expect(radioAdvisories({ iface: 'wifi1', power: '17' })).toEqual([])
  })

  it('treats auto and blank channel/power/width as nothing to check', () => {
    expect(radioAdvisories({ iface: 'wifi0', channel: 'auto', power: 'auto' })).toEqual([])
    expect(radioAdvisories({ iface: 'wifi0', channel: '', power: '', width: undefined })).toEqual([])
    expect(radioAdvisories({})).toEqual([])
  })

  it('stacks multiple advisories for a worst-case 2.4 GHz config', () => {
    const out = radioAdvisories({ iface: 'wifi0', channel: '3', power: '20', width: 40 })
    expect(out.map((a) => a.code).sort()).toEqual(['channel-24-overlap', 'high-power', 'width-24ghz'])
  })
})
