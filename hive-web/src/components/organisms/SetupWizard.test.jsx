import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SetupWizard } from './SetupWizard'

function fakeGateway(setup = vi.fn().mockResolvedValue({ tenantId: 'acme-corp' })) {
  return { setup }
}

describe('SetupWizard', () => {
  it('walks org -> admin -> create, posting the right payload, then offers sign-in', async () => {
    const setup = vi.fn().mockResolvedValue({ tenantId: 'acme-corp' })
    const onSignIn = vi.fn()
    render(<SetupWizard gateway={fakeGateway(setup)} onSignIn={onSignIn} defaultToken="tok-123" />)

    // step 1: org (token pre-filled from the prop)
    fireEvent.change(screen.getByPlaceholderText(/acme corp/i), { target: { value: 'Acme Corp' } })
    fireEvent.click(screen.getByRole('button', { name: /next/i }))

    // step 2: admin
    fireEvent.change(screen.getByPlaceholderText(/olivia/i), { target: { value: 'Olivia Owner' } })
    fireEvent.change(screen.getByPlaceholderText(/^admin$/i), { target: { value: 'admin' } })
    fireEvent.change(screen.getByPlaceholderText(/@/), { target: { value: 'admin@acme.test' } })
    const pwds = screen.getAllByPlaceholderText(/password/i)
    fireEvent.change(pwds[0], { target: { value: 'hunter2!' } })
    fireEvent.change(pwds[1], { target: { value: 'hunter2!' } })
    fireEvent.click(screen.getByRole('button', { name: /create organization/i }))

    expect(setup).toHaveBeenCalledWith({
      setupToken: 'tok-123',
      orgName: 'Acme Corp',
      username: 'admin',
      password: 'hunter2!',
      email: 'admin@acme.test',
      name: 'Olivia Owner',
    })
    // done step -> sign in
    fireEvent.click(await screen.findByRole('button', { name: /sign in/i }))
    expect(onSignIn).toHaveBeenCalled()
  })

  it('blocks mismatched passwords and surfaces a server error', async () => {
    const setup = vi.fn().mockRejectedValue(new Error('invalid or missing setup token'))
    render(<SetupWizard gateway={fakeGateway(setup)} onSignIn={vi.fn()} defaultToken="bad" />)
    fireEvent.change(screen.getByPlaceholderText(/acme corp/i), { target: { value: 'Acme' } })
    fireEvent.click(screen.getByRole('button', { name: /next/i }))
    fireEvent.change(screen.getByPlaceholderText(/^admin$/i), { target: { value: 'admin' } })
    const pwds = screen.getAllByPlaceholderText(/password/i)
    fireEvent.change(pwds[0], { target: { value: 'a' } })
    fireEvent.change(pwds[1], { target: { value: 'b' } })
    fireEvent.click(screen.getByRole('button', { name: /create organization/i }))
    expect(await screen.findByText(/do not match/i)).toBeInTheDocument()
    expect(setup).not.toHaveBeenCalled() // never posted with a mismatch

    // fix the confirm, now the server rejects the token
    fireEvent.change(pwds[1], { target: { value: 'a' } })
    fireEvent.click(screen.getByRole('button', { name: /create organization/i }))
    expect(await screen.findByText(/setup token/i)).toBeInTheDocument()
  })
})
