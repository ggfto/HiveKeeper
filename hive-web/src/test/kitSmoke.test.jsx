import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MriButton } from '@mriqbox/ui-kit'

// Proves the @mriqbox/ui-kit barrel imports and renders under jsdom (the main integration unknown):
// the kit pulls Radix/cmdk/lucide, and we need it to mount without touching browser-only APIs at import.
describe('kit integration', () => {
  it('renders an MriButton from the kit', () => {
    render(<MriButton>Click</MriButton>)
    expect(screen.getByRole('button', { name: 'Click' })).toBeInTheDocument()
  })
})
