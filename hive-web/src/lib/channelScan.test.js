import { describe, it, expect } from 'vitest'
import { parseChannelScans, rankedChannels, channelCrowding, UNUSABLE_COST } from './hiveosParse'

// Real output captured from the lab AP630 (HiveOS 10.6r6) on 2026-07-20 — a dense RF environment.
const CHANNEL_INFO = `
wifi0 (12):
State: RUN
Lowest cost channel: 1, lowest-cost: 6
Channel   1 Cost: 6
Channel   2 Cost: 32767 (overlap)
Channel   6 Cost: 43
Channel  11 Cost: 20

wifi1 (13):
State: RUN
Lowest cost channel: 149, lowest-cost: 0
Channel  36 Cost: 7
Channel  40 Cost: 32767 (offset)
Channel  44 Cost: 27
`

const NEIGHBORS = `
wifi0(12) ACSP neighbor list (104/384):
Bssid           Mode     Ssid/Hive                Chan   Rssi(dBm) Aerohive AP  CU  CRC STA Channel-width VID  NVID
a4c7:f621:5794  Access   604_EN                   6    -46       yes          42  116 0   20            -    -
d854:a282:7214  Access   604_EN                   11   -27       yes          55  96  0   20            1    1
6283:e75e:e128  Access                            1    -82       no           --  --  --  40+           -    -
58d9:d5bf:2358  Access   Portaria Zenith          1    -77       no           --  --  --  20            -    -
`

describe('parseChannelScans', () => {
  const scans = parseChannelScans(CHANNEL_INFO, NEIGHBORS)

  it('reads one scan per radio', () => {
    expect(scans.map((s) => s.iface)).toEqual(['wifi0', 'wifi1'])
  })

  it('reads the state and the channel the AP settled on', () => {
    expect(scans[0].state).toBe('RUN')
    expect(scans[0].currentChannel).toBe(1)
  })

  it('treats the sentinel cost as unusable rather than merely expensive', () => {
    const overlap = scans[0].channels.find((c) => c.channel === 2)
    expect(overlap.cost).toBe(UNUSABLE_COST)
    expect(overlap.usable).toBe(false)
    expect(overlap.reason).toBe('overlap')
  })

  it('marks a 5 GHz bonding offset unusable too', () => {
    expect(scans[1].channels.find((c) => c.channel === 40).reason).toBe('offset')
  })

  it('degrades to an empty list rather than throwing', () => {
    expect(parseChannelScans(null, null)).toEqual([])
    expect(parseChannelScans('', '')).toEqual([])
  })
})

describe('neighbours', () => {
  const scans = parseChannelScans(CHANNEL_INFO, NEIGHBORS)

  it('reads an SSID that contains a space', () => {
    expect(scans[0].neighbors.some((n) => n.ssid === 'Portaria Zenith')).toBe(true)
  })

  it('reads a hidden network as no SSID instead of shifting every column', () => {
    const hidden = scans[0].neighbors.find((n) => n.bssid === '6283:e75e:e128')
    expect(hidden.ssid).toBeNull()
    expect(hidden.channel).toBe(1)
    expect(hidden.rssiDbm).toBe(-82)
    expect(hidden.channelWidth).toBe('40+')
  })

  it('distinguishes our own APs from strangers, and does not parse -- as a number', () => {
    const ours = scans[0].neighbors.find((n) => n.channel === 11)
    expect(ours.ourFleet).toBe(true)
    expect(ours.utilization).toBe(55)

    const stranger = scans[0].neighbors.find((n) => n.bssid === '58d9:d5bf:2358')
    expect(stranger.ourFleet).toBe(false)
    expect(stranger.utilization).toBeNull()
  })

  it('attaches neighbours to the right radio', () => {
    expect(scans[1].neighbors).toEqual([])
  })
})

describe('rankedChannels', () => {
  it('puts the cheapest usable channel first and drops the unusable ones', () => {
    const scans = parseChannelScans(CHANNEL_INFO, NEIGHBORS)
    expect(rankedChannels(scans[0]).map((c) => c.channel)).toEqual([1, 11, 6])
  })

  it('survives a missing scan', () => {
    expect(rankedChannels(undefined)).toEqual([])
  })
})

describe('channelCrowding', () => {
  const scans = parseChannelScans(CHANNEL_INFO, NEIGHBORS)

  it('counts neighbours and finds the loudest', () => {
    expect(channelCrowding(scans[0], 1)).toEqual({ count: 2, loudestDbm: -77, ourFleet: 0 })
  })

  it('reports our own APs separately, since they cooperate over ACSP', () => {
    expect(channelCrowding(scans[0], 11)).toEqual({ count: 1, loudestDbm: -27, ourFleet: 1 })
  })

  it('says nothing rather than guessing on a clear channel', () => {
    expect(channelCrowding(scans[0], 13)).toEqual({ count: 0, loudestDbm: null, ourFleet: 0 })
  })
})
