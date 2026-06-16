import { describe, it, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { MembersPage } from './MembersPage'

describe('MembersPage', () => {
  it('lists the org members and marks the signed-in person', async () => {
    const gateway = fakeGateway({
      members: () =>
        Promise.resolve([
          { userId: 'usr-1', name: 'Olivia Owner', email: 'o@acme', status: 'active', role: 'owner' },
          { userId: 'usr-2', name: 'Bob Builder', email: 'b@acme', status: 'active', role: 'viewer' },
        ]),
    })
    renderWithAuth(<MembersPage />, { gateway, auth: { me: { userId: 'usr-1' } } })
    expect(await screen.findByText('Olivia Owner')).toBeInTheDocument()
    expect(screen.getByText('Bob Builder')).toBeInTheDocument()
    expect(screen.getByText('(you)')).toBeInTheDocument()
  })

  it('shows a forbidden note when listing members is not allowed', async () => {
    const gateway = fakeGateway({
      members: () => Promise.reject(Object.assign(new Error('forbidden'), { status: 403 })),
    })
    renderWithAuth(<MembersPage />, { gateway })
    expect(await screen.findByText(/needs an organization admin/i)).toBeInTheDocument()
  })
})
