import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { BackupDestinationForm } from './BackupDestinationForm'

function gatewayWith(overrides = {}) {
  return {
    getBackupDestination: vi.fn().mockResolvedValue({ configured: false }),
    setBackupDestination: vi.fn().mockResolvedValue({ agents: [] }),
    clearBackupDestination: vi.fn().mockResolvedValue({ agents: [] }),
    ...overrides,
  }
}

const configured = {
  configured: true,
  repoUrl: 'https://github.com/acme/hk-backups.git',
  branch: 'main',
  username: 'hivekeeper',
  updatedAt: '2026-07-20T18:00:00Z',
}

describe('BackupDestinationForm', () => {
  it('shows the current destination when one is configured', async () => {
    const gateway = gatewayWith({ getBackupDestination: vi.fn().mockResolvedValue(configured) })
    render(<BackupDestinationForm gateway={gateway} />)

    expect(await screen.findByText('https://github.com/acme/hk-backups.git')).toBeInTheDocument()
    expect(screen.getByText('configured')).toBeInTheDocument()
  })

  it('never shows the token, not even masked, because the gateway cannot return it', async () => {
    const gateway = gatewayWith({ getBackupDestination: vi.fn().mockResolvedValue(configured) })
    render(<BackupDestinationForm gateway={gateway} />)
    await screen.findByText('configured')

    // A masked value would imply we could reveal it. Empty is the honest state.
    expect(screen.getByLabelText(/access token/i)).toHaveValue('')
  })

  it('will not save without both a repository and a token', async () => {
    render(<BackupDestinationForm gateway={gatewayWith()} />)

    const save = await screen.findByRole('button', { name: /save destination/i })
    expect(save).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/repository url/i), { target: { value: 'https://x/y.git' } })
    expect(save).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/access token/i), { target: { value: 'ghp_x' } })
    expect(save).toBeEnabled()
  })

  it('saves and clears the token field afterwards', async () => {
    const gateway = gatewayWith()
    render(<BackupDestinationForm gateway={gateway} />)
    await screen.findByRole('button', { name: /save destination/i })

    fireEvent.change(screen.getByLabelText(/repository url/i), { target: { value: 'https://x/y.git' } })
    fireEvent.change(screen.getByLabelText(/access token/i), { target: { value: 'ghp_secret' } })
    fireEvent.click(screen.getByRole('button', { name: /save destination/i }))

    await waitFor(() => expect(gateway.setBackupDestination).toHaveBeenCalled())
    expect(gateway.setBackupDestination.mock.calls[0][0]).toMatchObject({
      repoUrl: 'https://x/y.git',
      token: 'ghp_secret',
    })
    await waitFor(() => expect(screen.getByLabelText(/access token/i)).toHaveValue(''))
  })

  it('names the agents that did not take it rather than reporting a bare success', async () => {
    const gateway = gatewayWith({
      setBackupDestination: vi.fn().mockResolvedValue({
        agents: [
          { agentId: 'site-a', delivered: true },
          { agentId: 'site-b', delivered: false, error: 'agent is not connected' },
        ],
      }),
    })
    render(<BackupDestinationForm gateway={gateway} />)
    await screen.findByRole('button', { name: /save destination/i })

    fireEvent.change(screen.getByLabelText(/repository url/i), { target: { value: 'https://x/y.git' } })
    fireEvent.change(screen.getByLabelText(/access token/i), { target: { value: 'ghp_x' } })
    fireEvent.click(screen.getByRole('button', { name: /save destination/i }))

    const delivery = await screen.findByTestId('bd-delivery')
    expect(delivery).toHaveTextContent(/1 of 2 agents did not take it/i)
    expect(delivery).toHaveTextContent('site-b')
  })

  it('says so plainly when no agent is connected', async () => {
    render(<BackupDestinationForm gateway={gatewayWith()} />)
    await screen.findByRole('button', { name: /save destination/i })

    fireEvent.change(screen.getByLabelText(/repository url/i), { target: { value: 'https://x/y.git' } })
    fireEvent.change(screen.getByLabelText(/access token/i), { target: { value: 'ghp_x' } })
    fireEvent.click(screen.getByRole('button', { name: /save destination/i }))

    expect(await screen.findByTestId('bd-no-agents')).toBeInTheDocument()
  })

  it('offers to stop pushing only when a destination exists', async () => {
    const { unmount } = render(<BackupDestinationForm gateway={gatewayWith()} />)
    await screen.findByRole('button', { name: /save destination/i })
    expect(screen.queryByRole('button', { name: /stop pushing/i })).not.toBeInTheDocument()
    unmount()

    const gateway = gatewayWith({ getBackupDestination: vi.fn().mockResolvedValue(configured) })
    render(<BackupDestinationForm gateway={gateway} />)
    fireEvent.click(await screen.findByRole('button', { name: /stop pushing/i }))
    await waitFor(() => expect(gateway.clearBackupDestination).toHaveBeenCalled())
  })

  it('surfaces a failure instead of pretending it saved', async () => {
    const gateway = gatewayWith({
      setBackupDestination: vi.fn().mockRejectedValue(new Error('repo_url_required')),
    })
    render(<BackupDestinationForm gateway={gateway} />)
    await screen.findByRole('button', { name: /save destination/i })

    fireEvent.change(screen.getByLabelText(/repository url/i), { target: { value: 'https://x/y.git' } })
    fireEvent.change(screen.getByLabelText(/access token/i), { target: { value: 'ghp_x' } })
    fireEvent.click(screen.getByRole('button', { name: /save destination/i }))

    expect(await screen.findByTestId('bd-error')).toHaveTextContent('repo_url_required')
  })
})
