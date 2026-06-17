import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { HelpPopover } from './HelpPopover'

describe('HelpPopover', () => {
  it('renders a help trigger affordance', () => {
    render(<HelpPopover title="Configuring this AP" body="some help" docId="device-configuration" />)
    expect(screen.getByRole('button', { name: 'Help' })).toBeInTheDocument()
  })
})
