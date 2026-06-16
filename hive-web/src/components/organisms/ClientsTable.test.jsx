import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ClientsTable } from './ClientsTable'

const stations = [
  { mac: 'aa:bb:cc', ipAddress: '10.0.0.9', ssid: 'guest', hostname: 'phone', osType: 'iOS', rssi: -55 },
  { mac: 'dd:ee:ff', ipAddress: '10.0.0.7', ssid: 'corp', hostname: 'laptop', osType: 'macOS', rssi: -70 },
]

describe('ClientsTable', () => {
  it('lists clients with a total and a per-SSID breakdown', () => {
    render(<ClientsTable stations={stations} />)
    expect(screen.getByText('phone')).toBeInTheDocument()
    expect(screen.getByText('10.0.0.9')).toBeInTheDocument()
    expect(screen.getByText('guest: 1')).toBeInTheDocument()
    expect(screen.getByText('corp: 1')).toBeInTheDocument()
  })

  it('filters the table by the search box', () => {
    render(<ClientsTable stations={stations} />)
    fireEvent.change(screen.getByPlaceholderText(/search clients/i), { target: { value: 'laptop' } })
    expect(screen.getByText('laptop')).toBeInTheDocument()
    expect(screen.queryByText('phone')).not.toBeInTheDocument()
  })

  it('shows a no-match note when the search excludes everything', () => {
    render(<ClientsTable stations={stations} />)
    fireEvent.change(screen.getByPlaceholderText(/search clients/i), { target: { value: 'zzz-nope' } })
    expect(screen.getByText(/no clients match/i)).toBeInTheDocument()
  })

  it('shows the empty state when there are no clients', () => {
    render(<ClientsTable stations={[]} />)
    expect(screen.getByText(/no clients associated/i)).toBeInTheDocument()
  })

  it('toggles the signal sort direction', () => {
    render(<ClientsTable stations={stations} />)
    const sort = screen.getByRole('button', { name: /signal/i })
    expect(sort).toHaveTextContent('↓') // strongest-first by default
    fireEvent.click(sort)
    expect(sort).toHaveTextContent('↑')
  })
})
