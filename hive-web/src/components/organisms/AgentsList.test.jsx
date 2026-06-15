import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AgentsList } from './AgentsList'

describe('AgentsList', () => {
  it('shows a gateway-unreachable note when agents is null', () => {
    render(<AgentsList agents={null} />)
    expect(screen.getByText(/unreachable/i)).toBeInTheDocument()
  })

  it('shows an empty state when no agents are connected', () => {
    render(<AgentsList agents={[]} />)
    expect(screen.getByText(/no agents connected/i)).toBeInTheDocument()
  })

  it('shows each agent with its device count + site, and links to its devices', () => {
    const onView = vi.fn()
    const onDiscover = vi.fn()
    render(
      <AgentsList agents={[{ id: 'lab-agent', deviceCount: 2, site: 'HQ' }]} onView={onView} onDiscover={onDiscover} />,
    )
    expect(screen.getByText('lab-agent')).toBeInTheDocument()
    expect(screen.getByText(/2 devices/)).toBeInTheDocument()
    expect(screen.getByText(/HQ/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /view devices/i }))
    expect(onView).toHaveBeenCalledWith('lab-agent')
    fireEvent.click(screen.getByRole('button', { name: /discover/i }))
    expect(onDiscover).toHaveBeenCalledWith('lab-agent')
  })
})
