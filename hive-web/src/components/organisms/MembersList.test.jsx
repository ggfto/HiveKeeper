import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MembersList } from './MembersList'

const members = [
  { userId: 'usr-1', name: 'Olivia Owner', email: 'o@acme', status: 'active', role: 'owner' },
  { userId: 'usr-2', name: 'Bob Builder', email: 'b@acme', status: 'active', role: 'viewer' },
]

describe('MembersList', () => {
  it('lists members with their role and marks the signed-in person', () => {
    render(<MembersList members={members} me={{ userId: 'usr-1' }} />)
    expect(screen.getByText('Olivia Owner')).toBeInTheDocument()
    expect(screen.getByText('Bob Builder')).toBeInTheDocument()
    expect(screen.getByText('(you)')).toBeInTheDocument()
    // the role select renders the current role's label in its trigger
    expect(screen.getByText('Owner')).toBeInTheDocument()
    expect(screen.getByText('Viewer')).toBeInTheDocument()
  })

  it('removes a member only after a confirming second click', () => {
    const onRemove = vi.fn()
    render(<MembersList members={members} onRemove={onRemove} />)
    fireEvent.click(screen.getAllByRole('button', { name: 'Remove' })[0]) // arm the first row
    expect(onRemove).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /remove\?/i }))
    expect(onRemove).toHaveBeenCalledWith(members[0])
  })

  it('shows a forbidden note for non-admins', () => {
    render(<MembersList members="forbidden" />)
    expect(screen.getByText(/needs an organization admin/i)).toBeInTheDocument()
  })

  it('shows a gateway-unreachable note when the list is null', () => {
    render(<MembersList members={null} />)
    expect(screen.getByText(/unreachable/i)).toBeInTheDocument()
  })
})
