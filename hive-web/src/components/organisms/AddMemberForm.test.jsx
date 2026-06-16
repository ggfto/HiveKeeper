import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { AddMemberForm } from './AddMemberForm'

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
})
