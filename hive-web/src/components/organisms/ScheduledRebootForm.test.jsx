import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ScheduledRebootForm } from './ScheduledRebootForm'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }

describe('ScheduledRebootForm', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('shows the current scheduled reboot loaded from the AP', async () => {
    const loadRebootSchedule = vi.fn().mockResolvedValue({ scheduledAt: '2026-07-12 02:59:12', weekday: 'Sunday' })
    render(<ScheduledRebootForm device={device} loadRebootSchedule={loadRebootSchedule} onApply={vi.fn()} />)
    expect(await screen.findByText(/2026-07-12 02:59:12/)).toBeInTheDocument()
    expect(screen.getByText(/Sunday/)).toBeInTheDocument()
    expect(loadRebootSchedule).toHaveBeenCalledWith(device)
  })

  it('shows an empty state when no reboot is scheduled', async () => {
    render(<ScheduledRebootForm device={device} loadRebootSchedule={vi.fn().mockResolvedValue(null)} onApply={vi.fn()} />)
    expect(await screen.findByText(/no reboot scheduled/i)).toBeInTheDocument()
  })

  it('schedules a daily reboot after confirmation (applied without save)', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ScheduledRebootForm device={device} loadRebootSchedule={vi.fn().mockResolvedValue(null)} onApply={onApply} />)
    await screen.findByText(/no reboot scheduled/i)
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '04:30' } })
    fireEvent.click(screen.getByRole('button', { name: /schedule reboot/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['reboot schedule daily every 1 day(s) time 04:30:00'],
      save: false,
    })
  })

  it('schedules a weekly reboot with a weekday', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ScheduledRebootForm device={device} loadRebootSchedule={vi.fn().mockResolvedValue(null)} onApply={onApply} />)
    await screen.findByText(/no reboot scheduled/i)
    fireEvent.change(screen.getByLabelText('Frequency'), { target: { value: 'weekly' } })
    fireEvent.change(screen.getByLabelText('Weekday'), { target: { value: 'Sunday' } })
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '03:00' } })
    fireEvent.click(screen.getByRole('button', { name: /schedule reboot/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['reboot schedule weekly every 1 week(s) Sunday time 03:00:00'],
      save: false,
    })
  })

  it('does not schedule when the confirmation is dismissed', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<ScheduledRebootForm device={device} loadRebootSchedule={vi.fn().mockResolvedValue(null)} onApply={onApply} />)
    await screen.findByText(/no reboot scheduled/i)
    fireEvent.change(screen.getByLabelText('Time'), { target: { value: '04:30' } })
    fireEvent.click(screen.getByRole('button', { name: /schedule reboot/i }))
    expect(onApply).not.toHaveBeenCalled()
  })

  it('cancels a scheduled reboot after confirmation', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const loadRebootSchedule = vi.fn().mockResolvedValue({ scheduledAt: '2026-07-12 02:59:12', weekday: 'Sunday' })
    render(<ScheduledRebootForm device={device} loadRebootSchedule={loadRebootSchedule} onApply={onApply} />)
    await screen.findByText(/2026-07-12 02:59:12/)
    fireEvent.click(screen.getByRole('button', { name: /cancel scheduled reboot/i }))
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['no reboot schedule'], save: false })
  })
})
