import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CredentialForm } from './CredentialForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

afterEach(() => vi.restoreAllMocks())

describe('CredentialForm', () => {
  it('submits the username and password (vault-only by default)', () => {
    const onSetCredential = vi.fn()
    render(<CredentialForm device={device} onSetCredential={onSetCredential} />)
    fireEvent.change(screen.getByPlaceholderText('••••••••'), { target: { value: 's3cret' } })
    fireEvent.click(screen.getByRole('button', { name: /save credential/i }))
    expect(onSetCredential).toHaveBeenCalledWith(device, {
      username: 'admin',
      password: 's3cret',
      alsoSetOnDevice: false,
    })
  })

  it('does nothing without a password', () => {
    const onSetCredential = vi.fn()
    render(<CredentialForm device={device} onSetCredential={onSetCredential} />)
    fireEvent.click(screen.getByRole('button', { name: /save credential/i }))
    expect(onSetCredential).not.toHaveBeenCalled()
  })

  it('gates the on-AP change behind allowOnDevice (disabled until CLI is confirmed)', () => {
    render(<CredentialForm device={device} onSetCredential={vi.fn()} />)
    const checkbox = screen.getByRole('checkbox')
    expect(checkbox).toBeDisabled()
    expect(screen.getByText(/pending live CLI confirmation/i)).toBeTruthy()
  })

  it('requires confirmation before changing the password on the AP', () => {
    const onSetCredential = vi.fn()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<CredentialForm device={device} onSetCredential={onSetCredential} allowOnDevice />)
    fireEvent.change(screen.getByPlaceholderText('••••••••'), { target: { value: 'newpw' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: /save credential/i }))
    expect(confirm).toHaveBeenCalled()
    expect(onSetCredential).not.toHaveBeenCalled()   // declined -> no call
  })

  it('sends alsoSetOnDevice when confirmed', () => {
    const onSetCredential = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<CredentialForm device={device} onSetCredential={onSetCredential} allowOnDevice />)
    fireEvent.change(screen.getByPlaceholderText('••••••••'), { target: { value: 'newpw' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: /save credential/i }))
    expect(onSetCredential).toHaveBeenCalledWith(device, {
      username: 'admin',
      password: 'newpw',
      alsoSetOnDevice: true,
    })
  })
})
