import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { StaticRoutesForm } from './StaticRoutesForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1' }
const routes = [
  { type: 'default', dest: null, netmask: null, gateway: '192.168.1.1', metric: null },
  { type: 'net', dest: '10.9.9.0', netmask: '255.255.255.0', gateway: '192.168.1.1', metric: null },
]

describe('StaticRoutesForm', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('lists the routes loaded from the AP (default shown without a remove)', async () => {
    const loadRoutes = vi.fn().mockResolvedValue(routes)
    render(<StaticRoutesForm device={device} loadRoutes={loadRoutes} onApply={vi.fn()} />)
    const list = within(await screen.findByTestId('route-list'))
    expect(list.getByText(/default → 192.168.1.1/)).toBeInTheDocument()
    expect(list.getByText(/net 10.9.9.0\/255.255.255.0 → 192.168.1.1/)).toBeInTheDocument()
    // only the net route is removable (default is edited in the management form)
    expect(list.getAllByRole('button', { name: /^remove$/i })).toHaveLength(1)
  })

  it('adds a net route after confirmation', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const loadRoutes = vi.fn().mockResolvedValue([])
    render(<StaticRoutesForm device={device} loadRoutes={loadRoutes} onApply={onApply} />)
    await screen.findByText(/no routes configured/i)
    fireEvent.change(screen.getByLabelText(/destination ip/i), { target: { value: '10.8.0.0' } })
    fireEvent.change(screen.getByLabelText(/netmask/i), { target: { value: '255.255.0.0' } })
    fireEvent.change(screen.getByLabelText(/gateway/i), { target: { value: '192.168.1.2' } })
    fireEvent.click(screen.getByRole('button', { name: /add route/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['ip route net 10.8.0.0 255.255.0.0 gateway 192.168.1.2'],
      save: true,
    })
  })

  it('switches to a host route (no netmask field) and removes a listed route', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const loadRoutes = vi.fn().mockResolvedValue(routes)
    render(<StaticRoutesForm device={device} loadRoutes={loadRoutes} onApply={onApply} />)
    const list = within(await screen.findByTestId('route-list'))
    fireEvent.click(list.getByRole('button', { name: /^remove$/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['no ip route net 10.9.9.0 255.255.255.0 gateway 192.168.1.1'],
      save: true,
    })
  })

  it('does not add when the destination/gateway are incomplete', async () => {
    const onApply = vi.fn()
    const loadRoutes = vi.fn().mockResolvedValue([])
    render(<StaticRoutesForm device={device} loadRoutes={loadRoutes} onApply={onApply} />)
    await screen.findByText(/no routes configured/i)
    fireEvent.change(screen.getByLabelText(/destination ip/i), { target: { value: '10.8.0.0' } })
    // no gateway -> button stays disabled, click is a no-op
    fireEvent.click(screen.getByRole('button', { name: /add route/i }))
    expect(onApply).not.toHaveBeenCalled()
  })
})
