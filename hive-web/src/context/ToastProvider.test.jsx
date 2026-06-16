import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ToastProvider, useToast } from './ToastProvider'
import { Toaster } from '../components/molecules/Toaster'

function Trigger() {
  const { toast } = useToast()
  return (
    <button type="button" onClick={() => toast('Saved!', 'success')}>
      fire
    </button>
  )
}

describe('ToastProvider + Toaster', () => {
  it('shows a toast on demand and dismisses it', () => {
    render(
      <ToastProvider>
        <Trigger />
        <Toaster />
      </ToastProvider>,
    )
    expect(screen.queryByText('Saved!')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('fire'))
    expect(screen.getByText('Saved!')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText(/dismiss notification/i))
    expect(screen.queryByText('Saved!')).not.toBeInTheDocument()
  })
})
