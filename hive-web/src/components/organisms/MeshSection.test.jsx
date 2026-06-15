import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MeshSection } from './MeshSection'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('MeshSection', () => {
  it('lists the hives read from the AP', async () => {
    const loadHives = vi.fn().mockResolvedValue([
      { name: 'hive0', nativeVlan: 1 },
      { name: 'hk-mesh', nativeVlan: 1 },
    ])
    render(<MeshSection device={device} loadHives={loadHives} applyMesh={vi.fn()} />)
    expect(await screen.findByText('hive0')).toBeInTheDocument()
    expect(screen.getByText('hk-mesh')).toBeInTheDocument()
    expect(loadHives).toHaveBeenCalledWith(device)
  })

  it('applies a hive bound to mgt0 by default', async () => {
    const loadHives = vi.fn().mockResolvedValue([])
    const applyMesh = vi.fn().mockResolvedValue(undefined)
    render(<MeshSection device={device} loadHives={loadHives} applyMesh={applyMesh} />)
    await screen.findByText(/no hives/i)
    fireEvent.change(screen.getByPlaceholderText('hk-mesh'), { target: { value: 'mesh1' } })
    fireEvent.change(screen.getByPlaceholderText('shared key'), { target: { value: 'secret12' } })
    fireEvent.click(screen.getByRole('button', { name: /apply hive/i }))
    expect(applyMesh).toHaveBeenCalledWith(device, { name: 'mesh1', password: 'secret12', interfaces: ['mgt0'] })
  })

  it('lets you add a radio interface to the backhaul', async () => {
    const loadHives = vi.fn().mockResolvedValue([])
    const applyMesh = vi.fn().mockResolvedValue(undefined)
    render(<MeshSection device={device} loadHives={loadHives} applyMesh={applyMesh} />)
    await screen.findByText(/no hives/i)
    fireEvent.change(screen.getByPlaceholderText('hk-mesh'), { target: { value: 'mesh1' } })
    fireEvent.click(screen.getByText('wifi1 (5G)')) // toggle the 5GHz radio into the hive
    fireEvent.click(screen.getByRole('button', { name: /apply hive/i }))
    const spec = applyMesh.mock.calls[0][1]
    expect(spec.name).toBe('mesh1')
    expect([...spec.interfaces].sort()).toEqual(['mgt0', 'wifi1'])
  })
})
