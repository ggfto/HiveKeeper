import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NoOrganizationGate } from './NoOrganizationGate'

describe('NoOrganizationGate', () => {
  it('shows the identity the admin will need to add them by', () => {
    // The whole reason this screen exists: they have to tell an admin WHICH account to admit, and the account
    // is whatever the identity provider gave us — not something they chose here.
    render(<NoOrganizationGate me={{ email: 'octocat@github.test', name: 'The Octocat' }} />)

    expect(screen.getByTestId('identity').textContent).toBe('octocat@github.test')
  })

  it('falls back to the display name when the provider gave no email', () => {
    render(<NoOrganizationGate me={{ name: 'The Octocat' }} />)

    expect(screen.getByTestId('identity').textContent).toBe('The Octocat')
  })

  it('renders without an identity rather than crashing', () => {
    render(<NoOrganizationGate me={{}} />)

    expect(screen.queryByTestId('identity')).toBeNull()
    expect(screen.getByText(/not a member of any organization/i)).toBeTruthy()
  })

  it('lets them sign out, so they can try another account', () => {
    const onSignOut = vi.fn()
    render(<NoOrganizationGate me={{ email: 'x@y.test' }} onSignOut={onSignOut} />)

    fireEvent.click(screen.getByRole('button', { name: /sign out/i }))

    expect(onSignOut).toHaveBeenCalled()
  })
})
