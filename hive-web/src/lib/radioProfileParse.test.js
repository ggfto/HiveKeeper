import { describe, it, expect } from 'vitest'
import { parseRadioProfiles } from './hiveosParse'
import { radioProfileCommands } from './hiveosCli'

describe('parseRadioProfiles', () => {
  // A real-shaped running-config fragment, like the AP630 stored after we applied an 11ax profile.
  const CONFIG = `
hostname AH-0d0640
radio profile hk_ax_5g
radio profile hk_ax_5g phymode 11ax-5g
radio profile hk_ax_5g channel-width 80
radio profile hk_ax_5g band-steering
radio profile hk_ax_5g max-client 100
radio profile hk_ax_5g 11ax bss-color 12
radio profile hk_ax_5g 11ax ofdma-dl
radio profile hk_ax_5g 11ax twt
radio profile hk_ax_5g mu-mimo enable
radio profile hk_ax_5g high-density enable
interface wifi1 radio profile hk_ax_5g
radio profile radio_axg0 phymode 11ax-2g
ssid 604_EN
`

  it('reads a profile back into the shape the form edits', () => {
    const p = parseRadioProfiles(CONFIG).find((x) => x.name === 'hk_ax_5g')
    expect(p).toMatchObject({
      phymode: '11ax-5g',
      channelWidth: '80',
      bandSteering: 'enable',
      maxClient: '100',
      bssColor: '12',
      ofdmaDl: 'enable',
      twt: 'enable',
      muMimo: 'enable',
      highDensity: 'enable',
    })
    expect(p.boundInterfaces).toEqual(['wifi1'])
    // A knob that was never set stays undefined — "unchanged", not a guessed default.
    expect(p.ofdmaUl).toBeUndefined()
  })

  it('lists every profile it saw', () => {
    expect(parseRadioProfiles(CONFIG).map((p) => p.name).sort()).toEqual(['hk_ax_5g', 'radio_axg0'])
  })

  it('round-trips: what the builder emits, this reads back', () => {
    const cmds = radioProfileCommands('rt', {
      phymode: '11ax-5g',
      channelWidth: '160',
      bandSteering: 'enable',
      receiveChain: '4',
      ofdmaDl: 'enable',
      twt: 'enable',
      bindInterface: 'wifi2',
    })
    const p = parseRadioProfiles(cmds.join('\n')).find((x) => x.name === 'rt')
    expect(p).toMatchObject({
      phymode: '11ax-5g',
      channelWidth: '160',
      bandSteering: 'enable',
      receiveChain: '4',
      ofdmaDl: 'enable',
      twt: 'enable',
    })
    expect(p.boundInterfaces).toEqual(['wifi2'])
  })

  it('degrades to an empty list on empty input', () => {
    expect(parseRadioProfiles('')).toEqual([])
    expect(parseRadioProfiles(null)).toEqual([])
  })
})
