import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { GroupsPanel } from './GroupsPanel'

describe('GroupsPanel', () => {
  it('lists groups with their site or a cross-site label', () => {
    render(
      <GroupsPanel
        groups={[
          { groupId: 'g1', name: 'Floor 3', siteId: 's1' },
          { groupId: 'g2', name: 'All-APs', siteId: null },
        ]}
        sites={[{ siteId: 's1', name: 'HQ' }]}
      />,
    )
    expect(screen.getByText('Floor 3')).toBeInTheDocument()
    expect(screen.getByText('HQ')).toBeInTheDocument()
    expect(screen.getByText('All-APs')).toBeInTheDocument()
    expect(screen.getByText('cross-site tag')).toBeInTheDocument()
  })

  it('shows a forbidden note for non-org roles', () => {
    render(<GroupsPanel groups="forbidden" />)
    expect(screen.getByText(/viewer or admin role/i)).toBeInTheDocument()
  })

  it('creates a group from the form (cross-site by default)', () => {
    const onCreate = vi.fn()
    render(<GroupsPanel groups={[]} sites={[]} onCreate={onCreate} />)
    fireEvent.change(screen.getByPlaceholderText('Floor 3'), { target: { value: 'Roof' } })
    fireEvent.click(screen.getByRole('button', { name: /create group/i }))
    expect(onCreate).toHaveBeenCalledWith('Roof', null)
  })
})
