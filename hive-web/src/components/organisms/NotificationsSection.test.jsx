import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { NotificationsSection } from './NotificationsSection'

const settings = { maxStations: 30, pollEnabled: true }
const channel = (over = {}) => ({
  id: 'ch-1',
  type: 'webhook',
  target: 'https://hook',
  minSeverity: 'warning',
  enabled: true,
  ...over,
})

function setup(over = {}) {
  const props = {
    loadSettings: vi.fn().mockResolvedValue(settings),
    onSaveSettings: vi.fn().mockResolvedValue(settings),
    loadChannels: vi.fn().mockResolvedValue([channel()]),
    onAddChannel: vi.fn().mockResolvedValue(channel({ id: 'ch-2' })),
    onToggleChannel: vi.fn().mockResolvedValue({}),
    onRemoveChannel: vi.fn().mockResolvedValue({}),
    ...over,
  }
  render(<NotificationsSection {...props} />)
  return props
}

describe('NotificationsSection', () => {
  it('lists configured channels', async () => {
    setup()
    expect(await screen.findByText('https://hook')).toBeInTheDocument()
  })

  it('adds a webhook channel', async () => {
    const props = setup({ loadChannels: vi.fn().mockResolvedValue([]) })
    await screen.findByRole('button', { name: /add channel/i })
    fireEvent.change(screen.getByPlaceholderText(/hooks\.example/i), { target: { value: 'https://new-hook' } })
    fireEvent.click(screen.getByRole('button', { name: /add channel/i }))
    expect(props.onAddChannel).toHaveBeenCalledWith({
      type: 'webhook',
      target: 'https://new-hook',
      minSeverity: 'warning',
    })
  })

  it('does not add a channel with a blank target', async () => {
    const props = setup({ loadChannels: vi.fn().mockResolvedValue([]) })
    await screen.findByRole('button', { name: /add channel/i })
    fireEvent.click(screen.getByRole('button', { name: /add channel/i }))
    expect(props.onAddChannel).not.toHaveBeenCalled()
  })

  it('saves thresholds', async () => {
    const props = setup()
    await screen.findByRole('button', { name: /save thresholds/i })
    fireEvent.change(screen.getByPlaceholderText('30'), { target: { value: '50' } })
    fireEvent.click(screen.getByRole('button', { name: /save thresholds/i }))
    expect(props.onSaveSettings).toHaveBeenCalledWith({ maxStations: 50, pollEnabled: true })
  })

  it('removes a channel after a confirming second click', async () => {
    const props = setup()
    fireEvent.click(await screen.findByRole('button', { name: /^remove$/i }))
    expect(props.onRemoveChannel).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }))
    expect(props.onRemoveChannel).toHaveBeenCalledWith('ch-1')
  })

  it('toggles a channel enabled state', async () => {
    const props = setup()
    fireEvent.click(await screen.findByRole('button', { name: /disable/i }))
    expect(props.onToggleChannel).toHaveBeenCalledWith('ch-1', false)
  })

  it('shows a forbidden note when listing is denied', async () => {
    setup({ loadChannels: vi.fn().mockRejectedValue(Object.assign(new Error('no'), { status: 403 })) })
    expect(await screen.findByText(/needs an organization admin/i)).toBeInTheDocument()
  })

  it('surfaces a 403 on mutation as an admin-needed message', async () => {
    setup({
      loadChannels: vi.fn().mockResolvedValue([]),
      onAddChannel: vi.fn().mockRejectedValue(Object.assign(new Error('no'), { status: 403 })),
    })
    await screen.findByRole('button', { name: /add channel/i })
    fireEvent.change(screen.getByPlaceholderText(/hooks\.example/i), { target: { value: 'https://x' } })
    fireEvent.click(screen.getByRole('button', { name: /add channel/i }))
    await waitFor(() => expect(screen.getByText(/needs an organization admin/i)).toBeInTheDocument())
  })
})
