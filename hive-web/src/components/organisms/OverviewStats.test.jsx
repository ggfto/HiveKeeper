import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { OverviewStats } from './OverviewStats'

describe('OverviewStats', () => {
  it('renders a metric card per fleet dimension with its count', () => {
    render(<OverviewStats counts={{ agents: 1, devices: 2, sites: 3, groups: 4 }} />)
    expect(screen.getByText('Agents')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('Devices')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('Sites')).toBeInTheDocument()
    expect(screen.getByText('Groups')).toBeInTheDocument()
  })

  it('defaults missing counts to zero', () => {
    render(<OverviewStats counts={{}} />)
    expect(screen.getAllByText('0').length).toBe(4)
  })
})
