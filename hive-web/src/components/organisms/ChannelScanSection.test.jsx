import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ChannelScanSection } from './ChannelScanSection'

// Shaped like parseChannelScans output. wifi0 sits on a costlier channel than the AP's own best pick.
const scans = [
  {
    iface: 'wifi0',
    state: 'RUN',
    currentChannel: 6,
    channels: [
      { channel: 1, cost: 6, reason: null, usable: true },
      { channel: 6, cost: 43, reason: null, usable: true },
      { channel: 11, cost: 20, reason: null, usable: true },
      { channel: 2, cost: 32767, reason: 'overlap', usable: false },
    ],
    neighbors: [
      { bssid: 'aaaa:bbbb:cccc', ssid: 'Vizinho', channel: 1, rssiDbm: -77, ourFleet: false, channelWidth: '20' },
      { bssid: 'dddd:eeee:ffff', ssid: '604_EN', channel: 11, rssiDbm: -27, ourFleet: true, channelWidth: '20' },
    ],
  },
]

describe('ChannelScanSection', () => {
  it('says nothing has been scanned yet rather than showing an empty table', () => {
    render(<ChannelScanSection scans={[]} onScan={vi.fn()} />)
    expect(screen.getByTestId('scan-empty')).toBeInTheDocument()
  })

  it('runs the scan on demand', () => {
    const onScan = vi.fn()
    render(<ChannelScanSection scans={[]} onScan={onScan} />)
    fireEvent.click(screen.getByRole('button', { name: /scan the air/i }))
    expect(onScan).toHaveBeenCalled()
  })

  it('recommends the cheapest channel when the radio is not already on it', () => {
    render(<ChannelScanSection scans={scans} onScan={vi.fn()} />)
    const rec = screen.getByTestId('recommendation-wifi0')
    expect(rec).toHaveTextContent(/suggested: channel 1/i)
    // The cost it would leave behind matters as much as the one it moves to.
    expect(rec).toHaveTextContent(/43/)
  })

  it('warns that changing a channel disconnects clients', () => {
    render(<ChannelScanSection scans={scans} onScan={vi.fn()} />)
    expect(screen.getByTestId('recommendation-wifi0')).toHaveTextContent(/reconnects/i)
  })

  it('says there is nothing to do when the radio already sits on the best channel', () => {
    const onBest = [{ ...scans[0], currentChannel: 1 }]
    render(<ChannelScanSection scans={onBest} onScan={vi.fn()} />)
    expect(screen.getByTestId('recommendation-wifi0')).toHaveTextContent(/nothing to change/i)
  })

  it('explains that excluded channels are unusable, not merely expensive', () => {
    render(<ChannelScanSection scans={scans} onScan={vi.fn()} />)
    expect(screen.getByTestId('scan-wifi0')).toHaveTextContent(/cannot centre on/i)
    expect(screen.getByTestId('scan-wifi0')).toHaveTextContent(/overlap/)
  })

  it('hides the neighbour list until asked, then shows it', () => {
    render(<ChannelScanSection scans={scans} onScan={vi.fn()} />)
    expect(screen.queryByText('Vizinho')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /show 2 neighbouring/i }))
    expect(screen.getByText('Vizinho')).toBeInTheDocument()
  })

  it('marks our own APs among the neighbours', () => {
    render(<ChannelScanSection scans={scans} onScan={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /show 2 neighbouring/i }))
    expect(screen.getByText('yours')).toBeInTheDocument()
  })
})
