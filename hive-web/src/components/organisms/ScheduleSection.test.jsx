import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { ScheduleSection } from './ScheduleSection'

const device = { deviceId: 'd1', agentId: 'lab-agent', mgmtIp: '10.0.0.1', serial: 'SER' }
const schedules = [
  { name: 'work-hours', type: 'recurrent', detail: 'weekday-range Monday to Friday time-range 08:00 to 17:00' },
  { name: 'xmas', type: 'once', detail: '2026-12-25 08:00 to 2026-12-26 17:00' },
]
const loadOk = (data = schedules) => vi.fn().mockResolvedValue(data)

describe('ScheduleSection', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('lists the schedules loaded from the AP with their type and detail', async () => {
    const loadSchedules = loadOk()
    render(<ScheduleSection device={device} loadSchedules={loadSchedules} onApply={vi.fn()} />)
    const list = within(await screen.findByTestId('schedule-list'))
    expect(list.getByText('work-hours')).toBeInTheDocument()
    expect(list.getByText('xmas')).toBeInTheDocument()
    expect(list.getByText('recurrent')).toBeInTheDocument()
    expect(list.getByText('once')).toBeInTheDocument()
    expect(list.getByText(/weekday-range Monday to Friday/)).toBeInTheDocument()
    expect(loadSchedules).toHaveBeenCalledWith(device)
  })

  it('shows an empty state when there are no schedules', async () => {
    render(<ScheduleSection device={device} loadSchedules={loadOk([])} onApply={vi.fn()} />)
    expect(await screen.findByText(/no schedules/i)).toBeInTheDocument()
  })

  it('creates a recurrent weekday + time schedule', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<ScheduleSection device={device} loadSchedules={loadOk([])} onApply={onApply} />)
    await screen.findByText(/no schedules/i)
    fireEvent.change(screen.getByPlaceholderText('work-hours'), { target: { value: 'work-hours' } })
    fireEvent.change(screen.getByLabelText('From weekday'), { target: { value: 'Monday' } })
    fireEvent.change(screen.getByLabelText('To weekday'), { target: { value: 'Friday' } })
    fireEvent.change(screen.getByLabelText('Start time'), { target: { value: '08:00' } })
    fireEvent.change(screen.getByLabelText('End time'), { target: { value: '17:00' } })
    fireEvent.click(screen.getByRole('button', { name: /create schedule/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['schedule work-hours recurrent weekday-range Monday to Friday time-range 08:00 to 17:00'],
      save: true,
    })
  })

  it('creates a one-time schedule when that type is picked', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    render(<ScheduleSection device={device} loadSchedules={loadOk([])} onApply={onApply} />)
    await screen.findByText(/no schedules/i)
    fireEvent.change(screen.getByLabelText('Type'), { target: { value: 'once' } })
    fireEvent.change(screen.getByPlaceholderText('work-hours'), { target: { value: 'xmas' } })
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-12-25' } })
    fireEvent.change(screen.getByLabelText('Start time'), { target: { value: '08:00' } })
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2026-12-26' } })
    fireEvent.change(screen.getByLabelText('End time'), { target: { value: '17:00' } })
    fireEvent.click(screen.getByRole('button', { name: /create schedule/i }))
    expect(onApply).toHaveBeenCalledWith(device, {
      commands: ['schedule xmas once 2026-12-25 08:00 to 2026-12-26 17:00'],
      save: true,
    })
  })

  it('removes a schedule after confirmation', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ScheduleSection device={device} loadSchedules={loadOk()} onApply={onApply} />)
    await screen.findByTestId('schedule-list')
    fireEvent.click(within(screen.getByTestId('schedule-list')).getAllByRole('button', { name: /^remove$/i })[0])
    expect(onApply).toHaveBeenCalledWith(device, { commands: ['no schedule work-hours'], save: true })
  })

  it('does not remove when the confirmation is dismissed', async () => {
    const onApply = vi.fn().mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<ScheduleSection device={device} loadSchedules={loadOk()} onApply={onApply} />)
    await screen.findByTestId('schedule-list')
    fireEvent.click(within(screen.getByTestId('schedule-list')).getAllByRole('button', { name: /^remove$/i })[0])
    expect(onApply).not.toHaveBeenCalled()
  })
})
