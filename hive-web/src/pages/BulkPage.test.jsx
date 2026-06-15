import { describe, it, expect, vi } from 'vitest'
import { screen, fireEvent } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { BulkPage } from './BulkPage'

describe('BulkPage', () => {
  it('runs a bulk inventory against the org and renders the outcome', async () => {
    const bulk = vi.fn().mockResolvedValue({
      op: 'inventory',
      total: 1,
      ok: 1,
      failed: 0,
      results: [{ host: '10.0.0.1', serial: 'A', status: 'ok' }],
    })
    const gateway = fakeGateway({ sites: () => Promise.resolve([]), groups: () => Promise.resolve([]), bulk })
    renderWithAuth(<BulkPage />, { gateway })
    fireEvent.click(await screen.findByRole('button', { name: /inventory all/i }))
    expect(await screen.findByText('inventory: 1/1 ok, 0 failed')).toBeInTheDocument()
    expect(bulk).toHaveBeenCalledWith('inventory', { kind: 'org' })
  })
})
