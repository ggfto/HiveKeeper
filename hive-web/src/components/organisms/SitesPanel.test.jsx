import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SitesPanel } from './SitesPanel'

const sites = [{ siteId: 's1', name: 'HQ' }]

describe('SitesPanel', () => {
  it('lists sites and creates a new one', () => {
    const onCreate = vi.fn()
    render(<SitesPanel sites={sites} onCreate={onCreate} />)
    expect(screen.getByText('HQ')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('HQ'), { target: { value: 'Branch' } })
    fireEvent.click(screen.getByRole('button', { name: /create site/i }))
    expect(onCreate).toHaveBeenCalledWith('Branch')
  })

  it('renames a site inline', () => {
    const onRename = vi.fn()
    render(<SitesPanel sites={sites} onRename={onRename} />)
    fireEvent.click(screen.getByRole('button', { name: /rename/i }))
    fireEvent.change(screen.getByLabelText('Rename HQ'), { target: { value: 'HQ2' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    expect(onRename).toHaveBeenCalledWith(sites[0], 'HQ2')
  })

  it('deletes a site only after confirming', () => {
    const onDelete = vi.fn()
    render(<SitesPanel sites={sites} onDelete={onDelete} />)
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(onDelete).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /delete\?/i }))
    expect(onDelete).toHaveBeenCalledWith(sites[0])
  })

  it('shows an empty note when there are no sites', () => {
    render(<SitesPanel sites={[]} />)
    expect(screen.getByText(/no sites yet/i)).toBeInTheDocument()
  })
})
