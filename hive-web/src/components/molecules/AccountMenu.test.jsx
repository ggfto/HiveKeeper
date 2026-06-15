import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AccountMenu } from './AccountMenu'

describe('AccountMenu', () => {
  it('shows a sign-in button when signed out', () => {
    const onSignIn = vi.fn()
    render(<AccountMenu onSignIn={onSignIn} />)
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }))
    expect(onSignIn).toHaveBeenCalled()
  })

  it('shows the account and a sign-out button when signed in', () => {
    const onSignOut = vi.fn()
    render(
      <AccountMenu
        user={{ profile: { name: 'Owner', email: 'o@token' } }}
        me={{ name: 'Owner', email: 'o@acme' }}
        onSignOut={onSignOut}
      />,
    )
    expect(screen.getByText('Owner')).toBeInTheDocument()
    expect(screen.getByText('o@acme')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /sign out/i }))
    expect(onSignOut).toHaveBeenCalled()
  })
})
