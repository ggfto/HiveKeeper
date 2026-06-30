import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { ConfigTemplatePanel } from './ConfigTemplatePanel'

const sites = [{ siteId: 's1', name: 'HQ' }]
const groups = [{ groupId: 'g1', name: 'Lobby' }]

beforeEach(() => {
  window.localStorage.clear()
  vi.restoreAllMocks()
})

describe('ConfigTemplatePanel', () => {
  it('applies the typed CLI lines across the chosen scope after confirmation', async () => {
    const onApply = vi.fn().mockResolvedValue({ op: 'apply-config', total: 2, ok: 2, failed: 0, results: [] })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ConfigTemplatePanel sites={sites} groups={groups} onApply={onApply} busy={false} />)
    fireEvent.change(screen.getByLabelText(/CLI lines/i), { target: { value: '# comment\nhostname AP-1\nled off' } })
    fireEvent.click(screen.getByRole('button', { name: /apply to devices/i }))
    expect(onApply).toHaveBeenCalledWith({ kind: 'org' }, ['hostname AP-1', 'led off'], true)
  })

  it('does not apply when there are no commands', async () => {
    const onApply = vi.fn()
    render(<ConfigTemplatePanel sites={sites} groups={groups} onApply={onApply} busy={false} />)
    // textarea empty -> the apply button is disabled
    expect(screen.getByRole('button', { name: /apply to devices/i })).toBeDisabled()
    expect(onApply).not.toHaveBeenCalled()
  })

  it('does not apply when the confirmation is dismissed', async () => {
    const onApply = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    render(<ConfigTemplatePanel sites={sites} groups={groups} onApply={onApply} busy={false} />)
    fireEvent.change(screen.getByLabelText(/CLI lines/i), { target: { value: 'hostname AP-1' } })
    fireEvent.click(screen.getByRole('button', { name: /apply to devices/i }))
    expect(onApply).not.toHaveBeenCalled()
  })

  it('passes save=false when "save config" is unchecked', async () => {
    const onApply = vi.fn().mockResolvedValue({ results: [] })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ConfigTemplatePanel sites={sites} groups={groups} onApply={onApply} busy={false} />)
    fireEvent.change(screen.getByLabelText(/CLI lines/i), { target: { value: 'hostname AP-1' } })
    fireEvent.click(screen.getByLabelText(/save config/i))
    fireEvent.click(screen.getByRole('button', { name: /apply to devices/i }))
    expect(onApply).toHaveBeenCalledWith({ kind: 'org' }, ['hostname AP-1'], false)
  })

  it('saves a named template and reloads it into the editor', async () => {
    render(<ConfigTemplatePanel sites={sites} groups={groups} onApply={vi.fn()} busy={false} />)
    fireEvent.change(screen.getByLabelText(/CLI lines/i), { target: { value: 'hostname AP-1' } })
    fireEvent.change(screen.getByLabelText(/template name/i), { target: { value: 'Baseline' } })
    fireEvent.click(screen.getByRole('button', { name: /save template/i }))
    // clear the editor, then load the saved template back
    fireEvent.change(screen.getByLabelText(/CLI lines/i), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText(/saved templates/i), { target: { value: 'Baseline' } })
    expect(screen.getByLabelText(/CLI lines/i).value).toBe('hostname AP-1')
  })

  it('renders the per-device outcomes table from the result', async () => {
    const result = {
      op: 'apply-config',
      total: 2,
      ok: 1,
      failed: 1,
      results: [
        { deviceId: 'd1', serial: 'SER-A', host: '10.0.0.1', status: 'ok', detail: null },
        { deviceId: 'd2', serial: 'SER-B', host: '10.0.0.2', status: 'failed', detail: 'boom' },
      ],
    }
    render(<ConfigTemplatePanel sites={sites} groups={groups} onApply={vi.fn()} result={result} busy={false} />)
    const table = within(screen.getByTestId('bulk-config-results'))
    expect(table.getByText('10.0.0.1')).toBeInTheDocument()
    expect(table.getByText('boom')).toBeInTheDocument()
    expect(screen.getByText(/1\/2 ok, 1 failed/)).toBeInTheDocument()
  })
})
