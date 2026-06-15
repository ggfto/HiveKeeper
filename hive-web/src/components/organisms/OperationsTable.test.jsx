import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { OperationsTable } from './OperationsTable'

describe('OperationsTable', () => {
  it('shows an empty state', () => {
    render(<OperationsTable operations={[]} />)
    expect(screen.getByText(/no operations recorded/i)).toBeInTheDocument()
  })

  it('renders an audit row (agent, op type, host, summary)', () => {
    render(
      <OperationsTable
        operations={[
          {
            id: 1,
            agentId: 'lab-agent',
            opType: 'bulk-inventory',
            host: '192.168.1.100',
            summary: 'Inventory',
            createdAt: '2026-06-15T13:00:00Z',
          },
        ]}
      />,
    )
    expect(screen.getByText('lab-agent')).toBeInTheDocument()
    expect(screen.getByText('bulk-inventory')).toBeInTheDocument()
    expect(screen.getByText('192.168.1.100')).toBeInTheDocument()
    expect(screen.getByText('Inventory')).toBeInTheDocument()
  })
})
