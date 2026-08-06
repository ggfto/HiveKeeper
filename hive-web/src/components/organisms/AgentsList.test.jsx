import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AgentsList } from './AgentsList'

describe('AgentsList', () => {
  it('shows a gateway-unreachable note when agents is null', () => {
    render(<AgentsList agents={null} />)
    expect(screen.getByText(/unreachable/i)).toBeInTheDocument()
  })

  it('shows a loading note (not unreachable) before the first response', () => {
    render(<AgentsList agents={null} loading />)
    expect(screen.getByText(/loading agents/i)).toBeInTheDocument()
    expect(screen.queryByText(/unreachable/i)).not.toBeInTheDocument()
  })

  it('shows an empty state when no agents are enrolled', () => {
    render(<AgentsList agents={[]} />)
    expect(screen.getByText(/no agents enrolled/i)).toBeInTheDocument()
  })

  it('shows each agent with its device count + site, and links to its devices', () => {
    const onView = vi.fn()
    const onDiscover = vi.fn()
    render(
      <AgentsList
        agents={[{ id: 'lab-agent', online: true, deviceCount: 2, site: 'HQ' }]}
        onView={onView}
        onDiscover={onDiscover}
      />,
    )
    expect(screen.getByText('lab-agent')).toBeInTheDocument()
    expect(screen.getByText(/2 devices/)).toBeInTheDocument()
    expect(screen.getByText(/HQ/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /view devices/i }))
    expect(onView).toHaveBeenCalledWith('lab-agent')
    fireEvent.click(screen.getByRole('button', { name: /discover/i }))
    expect(onDiscover).toHaveBeenCalledWith('lab-agent')
  })

  it('removes an agent only after the action is confirmed', () => {
    const onRemove = vi.fn()
    render(
      <AgentsList agents={[{ id: 'old-agent', online: false, deviceCount: 0 }]} onRemove={onRemove} />,
    )

    fireEvent.click(screen.getByRole('button', { name: /^remove$/i }))
    expect(onRemove).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /remove agent/i }))
    expect(onRemove).toHaveBeenCalledWith('old-agent')
  })

  it('warns on the confirm step when the agent is the only way to reach its devices', () => {
    // Deleting is irreversible and takes the reachability with it, so the count the operator is about to
    // strand belongs on the button they are about to press — not only in the docs.
    render(<AgentsList agents={[{ id: 'lab-agent', online: true, deviceCount: 3 }]} onRemove={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /^remove$/i }))

    expect(screen.getByRole('button', { name: /remove agent \+ 3 devices/i })).toBeInTheDocument()
  })

  it('offers no remove action when the caller cannot delete agents', () => {
    render(<AgentsList agents={[{ id: 'lab-agent', online: true, deviceCount: 1 }]} />)
    expect(screen.queryByRole('button', { name: /^remove$/i })).not.toBeInTheDocument()
  })
})
