import { describe, it, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth } from '../test/renderWithAuth'
import { HelpPage } from './HelpPage'

describe('HelpPage', () => {
  it('renders the selected doc body and a nav to the other docs', async () => {
    renderWithAuth(<HelpPage />, { route: '/help/getting-started', path: '/help/:docId' })
    // a heading from the getting-started doc body
    expect(await screen.findByRole('heading', { name: 'Build' })).toBeInTheDocument()
    // the left nav links to sibling docs (Architecture appears only in the nav, not in this doc's body)
    expect(screen.getByRole('link', { name: 'Architecture' })).toBeInTheDocument()
  })
})
