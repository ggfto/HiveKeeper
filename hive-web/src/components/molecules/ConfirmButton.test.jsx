import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ConfirmButton } from './ConfirmButton'

describe('ConfirmButton', () => {
  it('runs onConfirm only after a second, confirming click', () => {
    const onConfirm = vi.fn()
    render(<ConfirmButton onConfirm={onConfirm}>Delete</ConfirmButton>)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(onConfirm).not.toHaveBeenCalled() // first click only arms it
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('can be cancelled without confirming, returning to the initial state', () => {
    const onConfirm = vi.fn()
    render(
      <ConfirmButton confirmLabel="Remove?" onConfirm={onConfirm}>
        Remove
      </ConfirmButton>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onConfirm).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument()
  })
})
