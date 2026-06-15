import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { BulkOpsPanel } from './BulkOpsPanel'

describe('BulkOpsPanel', () => {
  it('runs an inventory bulk op against the whole org by default', () => {
    const onRun = vi.fn()
    render(<BulkOpsPanel onRun={onRun} />)
    fireEvent.click(screen.getByRole('button', { name: /inventory all/i }))
    expect(onRun).toHaveBeenCalledWith('inventory', { kind: 'org' })
  })

  it('runs a backup bulk op', () => {
    const onRun = vi.fn()
    render(<BulkOpsPanel onRun={onRun} />)
    fireEvent.click(screen.getByRole('button', { name: /backup all/i }))
    expect(onRun).toHaveBeenCalledWith('backup', { kind: 'org' })
  })

  it('summarizes the response and lists per-device outcomes', () => {
    const result = {
      op: 'inventory',
      total: 2,
      ok: 1,
      failed: 1,
      results: [
        { host: '10.0.0.1', serial: 'A', status: 'ok' },
        { host: '10.0.0.2', serial: 'B', status: 'failed', detail: 'timeout' },
      ],
    }
    render(<BulkOpsPanel result={result} />)
    expect(screen.getByText('inventory: 1/2 ok, 1 failed')).toBeInTheDocument()
    expect(screen.getByText('10.0.0.1')).toBeInTheDocument()
    expect(screen.getByText('timeout')).toBeInTheDocument()
  })
})
