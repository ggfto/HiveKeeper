import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { NetworkSection } from './NetworkSection'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('NetworkSection', () => {
  it('composes management, DNS and NTP on one screen', () => {
    render(<NetworkSection device={device} onApply={() => {}} />)
    expect(screen.getByText(/management \(mgt0\)/i)).toBeInTheDocument()
    expect(screen.getByText('DNS')).toBeInTheDocument()
    expect(screen.getByText('NTP')).toBeInTheDocument()
  })
})
