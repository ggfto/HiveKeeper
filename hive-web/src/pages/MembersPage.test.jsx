import { describe, it, expect, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

  it('surfaces the recovery link the IdP returned for a new teammate', async () => {
    // Under Authentik the link is the only way in for the new person, and the gateway cannot re-issue it, so
    // it has to survive on screen rather than flash past in a toast.
    const gateway = fakeGateway({
      members: () => Promise.resolve([]),
      addMember: () =>
        Promise.resolve({ userId: 'usr-bob', recoveryLink: 'https://authentik/if/flow/recovery/?token=abc' }),
    })
    renderWithAuth(<MembersPage />, { gateway })

    await userEvent.type(await screen.findByLabelText(/username/i), 'bob')
    await userEvent.type(screen.getByLabelText(/password/i), 'throwaway')
    await userEvent.click(screen.getByRole('button', { name: /add member/i }))

    expect(await screen.findByText('https://authentik/if/flow/recovery/?token=abc')).toBeInTheDocument()
  })

  it('shows no recovery panel when the IdP forces the change itself', async () => {
    const gateway = fakeGateway({
      members: () => Promise.resolve([]),
      addMember: () => Promise.resolve({ userId: 'usr-bob' }),   // Keycloak: no link
    })
    renderWithAuth(<MembersPage />, { gateway })

    await userEvent.type(await screen.findByLabelText(/username/i), 'bob')
    await userEvent.type(screen.getByLabelText(/password/i), 'throwaway')
    await userEvent.click(screen.getByRole('button', { name: /add member/i }))

    expect(await screen.findByText(/added bob/i)).toBeInTheDocument()
    expect(screen.queryByText(/only shown once/i)).not.toBeInTheDocument()
  })
})
