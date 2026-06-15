import { describe, it, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithAuth, fakeGateway } from '../test/renderWithAuth'
import { OverviewPage } from './OverviewPage'

describe('OverviewPage', () => {
  it('shows fleet counts for the active organization', async () => {
    const gateway = fakeGateway({
      agents: () => Promise.resolve(['a1']),
      devices: () => Promise.resolve([{}, {}]),
      sites: () => Promise.resolve([{}]),
      groups: () => Promise.resolve([]),
      operations: () => Promise.resolve([]),
    })
    renderWithAuth(<OverviewPage />, { gateway })
    expect(await screen.findByText('Agents')).toBeInTheDocument()
    expect(await screen.findByText('2')).toBeInTheDocument() // devices count
  })
})
