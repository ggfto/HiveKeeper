import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AddMemberForm } from './AddMemberForm'

const pickExisting = () => fireEvent.click(screen.getByRole('radio', { name: /existing account/i }))

describe('AddMemberForm', () => {
  it('submits the new member (defaulting to viewer) and clears on success', async () => {
    const onAdd = vi.fn().mockResolvedValue(true)
    render(<AddMemberForm onAdd={onAdd} />)
    fireEvent.change(screen.getByPlaceholderText('bob'), { target: { value: 'bob' } })
    fireEvent.change(screen.getByPlaceholderText('temp pass'), { target: { value: 'pw123' } })
    fireEvent.click(screen.getByRole('button', { name: /add member/i }))
    await waitFor(() =>
      expect(onAdd).toHaveBeenCalledWith({
        username: 'bob',
        email: null,
        name: null,
        password: 'pw123',
        role: 'viewer',
      }),
    )
    await waitFor(() => expect(screen.getByPlaceholderText('bob').value).toBe(''))
  })

  it('does not submit without a username and password', () => {
    const onAdd = vi.fn()
    render(<AddMemberForm onAdd={onAdd} />)
    fireEvent.click(screen.getByRole('button', { name: /add member/i }))
    expect(onAdd).not.toHaveBeenCalled()
  })

  it('fills a temporary password with the Generate button', () => {
    render(<AddMemberForm onAdd={vi.fn()} />)
    const pass = screen.getByPlaceholderText('temp pass')
    expect(pass.value).toBe('')
    fireEvent.click(screen.getByRole('button', { name: /generate/i }))
    expect(pass.value.length).toBeGreaterThan(0)
  })

  describe('admitting an account that already exists (federated sign-in)', () => {
    it('sends no password, so the gateway admits rather than creates', async () => {
      // Somebody who signs in with GitHub has no password and no account until their first sign-in creates
      // one. Sending a password here would try to CREATE a second login for them, which is not what is meant.
      const onAdd = vi.fn().mockResolvedValue(true)
      render(<AddMemberForm onAdd={onAdd} />)

      pickExisting()
      fireEvent.change(screen.getByPlaceholderText('octocat or bob@acme.com'), {
        target: { value: 'octocat' },
      })
      fireEvent.click(screen.getByRole('button', { name: /add member/i }))

      await waitFor(() =>
        expect(onAdd).toHaveBeenCalledWith({
          username: 'octocat',
          email: null,
          name: null,
          password: null,
          role: 'viewer',
        }),
      )
    })

    it('asks for no password at all', () => {
      render(<AddMemberForm onAdd={vi.fn()} />)
      pickExisting()

      expect(screen.queryByPlaceholderText('temp pass')).toBeNull()
    })

    it('submits on a username alone', () => {
      const onAdd = vi.fn().mockResolvedValue(true)
      render(<AddMemberForm onAdd={onAdd} />)

      pickExisting()
      fireEvent.change(screen.getByPlaceholderText('octocat or bob@acme.com'), {
        target: { value: 'octocat' },
      })
      fireEvent.click(screen.getByRole('button', { name: /add member/i }))

      expect(onAdd).toHaveBeenCalled()
    })

    it('still needs a username', () => {
      const onAdd = vi.fn()
      render(<AddMemberForm onAdd={onAdd} />)

      pickExisting()
      fireEvent.click(screen.getByRole('button', { name: /add member/i }))

      expect(onAdd).not.toHaveBeenCalled()
    })
  })
})
