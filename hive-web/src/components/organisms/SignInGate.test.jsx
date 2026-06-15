import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SignInGate } from './SignInGate'

describe('SignInGate', () => {
  it('signs in via the identity provider', () => {
    const onSignIn = vi.fn()
    render(<SignInGate onSignIn={onSignIn} onDevMode={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /^sign in$/i }))
    expect(onSignIn).toHaveBeenCalled()
  })

  it('offers an explicit dev owner-key escape hatch', () => {
    const onDevMode = vi.fn()
    render(<SignInGate onSignIn={() => {}} onDevMode={onDevMode} />)
    fireEvent.click(screen.getByRole('button', { name: /dev owner key/i }))
    expect(onDevMode).toHaveBeenCalled()
  })
})
