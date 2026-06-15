import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { HiveForm } from './HiveForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('HiveForm', () => {
  it('joins a hive/mesh', () => {
    const onConfigureHive = vi.fn()
    render(<HiveForm device={device} onConfigureHive={onConfigureHive} />)
    fireEvent.change(screen.getByPlaceholderText('hk-hive'), { target: { value: 'mesh1' } })
    fireEvent.change(screen.getByPlaceholderText('shared key'), { target: { value: 'secret12' } })
    fireEvent.click(screen.getByRole('button', { name: /join hive/i }))
    expect(onConfigureHive).toHaveBeenCalledWith(device, { name: 'mesh1', password: 'secret12' })
  })
})
