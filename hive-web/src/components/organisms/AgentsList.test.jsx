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

  it('renders a card per agent with an online badge and a discover action', () => {
    const onDiscover = vi.fn()
    render(<AgentsList agents={['lab-agent']} onDiscover={onDiscover} />)
    expect(screen.getByText('lab-agent')).toBeInTheDocument()
    expect(screen.getByText('online')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /discover/i }))
    expect(onDiscover).toHaveBeenCalledWith('lab-agent')
  })
})
