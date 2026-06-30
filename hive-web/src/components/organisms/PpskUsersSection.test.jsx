import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { PpskUsersSection } from './PpskUsersSection'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1' }

const userRow = (over = {}) => ({
  id: 'ppsk-1',
  securityObject: 'Corp',
  userGroup: 'staff',
  username: 'alice',
  vlanId: 30,
  status: 'active',
  ...over,
})

describe('PpskUsersSection', () => {
  it('lists existing PPSK users', async () => {
    const loadPpskUsers = vi.fn().mockResolvedValue([userRow()])
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} />)
    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(screen.getByText('Corp')).toBeInTheDocument()
  })

  it('mints a user and reveals the one-time PSK', async () => {
    const loadPpskUsers = vi.fn().mockResolvedValue([])
    const onCreate = vi.fn().mockResolvedValue({ user: userRow(), psk: 'Gen3ratedKeyXYZ' })
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} onCreate={onCreate} />)
    await screen.findByRole('button', { name: /generate psk/i })

    fireEvent.change(screen.getByPlaceholderText('Corp'), { target: { value: 'Corp' } })
    fireEvent.change(screen.getByPlaceholderText('alice'), { target: { value: 'alice' } })
    fireEvent.change(screen.getByPlaceholderText('staff'), { target: { value: 'staff' } })
    const [vlanField] = screen.getAllByPlaceholderText('optional')
    fireEvent.change(vlanField, { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: /generate psk/i }))

    expect(onCreate).toHaveBeenCalledWith(device, {
      securityObject: 'Corp',
      userGroup: 'staff',
      username: 'alice',
      vlanId: 30,
      userProfileAttr: null,
    })
    expect(await screen.findByText('Gen3ratedKeyXYZ')).toBeInTheDocument()
  })

  it('does not mint without a security object and username', async () => {
    const loadPpskUsers = vi.fn().mockResolvedValue([])
    const onCreate = vi.fn()
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} onCreate={onCreate} />)
    await screen.findByRole('button', { name: /generate psk/i })
    fireEvent.click(screen.getByRole('button', { name: /generate psk/i }))
    expect(onCreate).not.toHaveBeenCalled()
  })

  it('rotates a user and reveals the new PSK', async () => {
    const loadPpskUsers = vi.fn().mockResolvedValue([userRow()])
    const onRotate = vi.fn().mockResolvedValue({ user: userRow(), psk: 'R0tatedKey999' })
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} onRotate={onRotate} />)
    fireEvent.click(await screen.findByRole('button', { name: /rotate/i }))
    expect(onRotate).toHaveBeenCalledWith(device, 'ppsk-1')
    expect(await screen.findByText('R0tatedKey999')).toBeInTheDocument()
  })

  it('revokes a user only after a confirming second click', async () => {
    const loadPpskUsers = vi.fn().mockResolvedValue([userRow()])
    const onRevoke = vi.fn().mockResolvedValue(undefined)
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} onRevoke={onRevoke} />)
    fireEvent.click(await screen.findByRole('button', { name: /revoke/i }))
    expect(onRevoke).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }))
    expect(onRevoke).toHaveBeenCalledWith(device, 'ppsk-1')
  })

  it('shows a forbidden note when the caller lacks the role', async () => {
    const loadPpskUsers = vi.fn().mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 }))
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} />)
    expect(await screen.findByText(/needs an operator role/i)).toBeInTheDocument()
  })

  it('surfaces a create error inline', async () => {
    const loadPpskUsers = vi.fn().mockResolvedValue([])
    const onCreate = vi.fn().mockRejectedValue(Object.assign(new Error('boom'), { body: { detail: 'already exists' } }))
    render(<PpskUsersSection device={device} loadPpskUsers={loadPpskUsers} onCreate={onCreate} />)
    await screen.findByRole('button', { name: /generate psk/i })
    fireEvent.change(screen.getByPlaceholderText('Corp'), { target: { value: 'Corp' } })
    fireEvent.change(screen.getByPlaceholderText('alice'), { target: { value: 'alice' } })
    fireEvent.click(screen.getByRole('button', { name: /generate psk/i }))
    await waitFor(() => expect(screen.getByText('already exists')).toBeInTheDocument())
  })
})
