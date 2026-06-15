import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DevicesTable } from './DevicesTable'

const devices = [
  { deviceId: 'd1', label: 'lab-ap', serial: 'SER1', model: 'AP230', mgmtIp: '10.0.0.1', siteId: 's1', agentId: 'a1', groups: ['g1'], online: true },
  { deviceId: 'd2', label: 'roof', serial: 'SER2', model: 'AP250', mgmtIp: '10.0.0.2', siteId: 's1', agentId: 'a2', groups: [], online: false },
]
const sites = [{ siteId: 's1', name: 'HQ' }]
const groups = [{ groupId: 'g1', name: 'Floor 3' }]

describe('DevicesTable', () => {
  it('shows an empty state when there are no devices', () => {
    render(<DevicesTable devices={[]} onOpen={() => {}} />)
    expect(screen.getByText(/no devices yet/i)).toBeInTheDocument()
  })

  it('shows live status, the agent, and the resolved site per device', () => {
    render(<DevicesTable devices={devices} sites={sites} groups={groups} onOpen={() => {}} />)
    expect(screen.getByText('online')).toBeInTheDocument()
    expect(screen.getByText('offline')).toBeInTheDocument()
    expect(screen.getByText('SER1')).toBeInTheDocument()
    expect(screen.getByText('a1')).toBeInTheDocument() // agent column
    expect(screen.getAllByText('HQ').length).toBe(2) // site column for both rows
    expect(screen.getByText('Floor 3')).toBeInTheDocument() // group name
  })

  it('opens the device page when the row is clicked', () => {
    const onOpen = vi.fn()
    render(<DevicesTable devices={devices} sites={sites} groups={groups} onOpen={onOpen} />)
    fireEvent.click(screen.getByText('SER1'))
    expect(onOpen).toHaveBeenCalledWith(devices[0])
  })
})
