import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RecoveryLinkNotice } from './RecoveryLinkNotice'

describe('RecoveryLinkNotice', () => {
  const LINK = 'https://authentik.example/if/flow/recovery/?token=abc123'

  it('renders nothing without a link', () => {
    const { container } = render(<RecoveryLinkNotice link={null} username="bob" onDismiss={() => {}} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the whole link so it can be read and copied by hand', async () => {
    render(<RecoveryLinkNotice link={LINK} username="bob" onDismiss={() => {}} />)
    // Truncating it would make it unusable for anyone who cannot use the clipboard button.
    expect(await screen.findByText(LINK)).toBeInTheDocument()
    expect(screen.getByText(/bob/)).toBeInTheDocument()
  })

  it('says the link is one-time and will not be shown again', () => {
    render(<RecoveryLinkNotice link={LINK} username="bob" onDismiss={() => {}} />)
    // The gateway cannot re-issue it, so an admin who dismisses it without copying has to delete and re-add.
    expect(screen.getByText(/only shown once/i)).toBeInTheDocument()
  })

  it('copies the link to the clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    render(<RecoveryLinkNotice link={LINK} username="bob" onDismiss={() => {}} />)

    await userEvent.click(screen.getByRole('button', { name: /copy/i }))

    expect(writeText).toHaveBeenCalledWith(LINK)
  })

  it('dismisses', async () => {
    const onDismiss = vi.fn()
    render(<RecoveryLinkNotice link={LINK} username="bob" onDismiss={onDismiss} />)

    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }))

    expect(onDismiss).toHaveBeenCalled()
  })
})
