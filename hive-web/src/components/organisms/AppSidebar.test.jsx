import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AppSidebar } from './AppSidebar'

describe('AppSidebar', () => {
  it('renders the six console sections', () => {
    render(<AppSidebar activeRoute="/overview" onNavigate={() => {}} />)
    for (const label of ['Overview', 'Agents', 'Devices', 'Sites & Groups', 'Bulk ops', 'Audit log']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
  })

  it('navigates to a section route on click', () => {
    const onNavigate = vi.fn()
    render(<AppSidebar activeRoute="/overview" onNavigate={onNavigate} />)
    fireEvent.click(screen.getByText('Devices'))
    expect(onNavigate).toHaveBeenCalledWith('/devices')
  })

  it('shows the brand at the top and renders the footer slot at the bottom', () => {
    render(
      <AppSidebar activeRoute="/overview" onNavigate={() => {}} footer={<span>footer-content</span>} />,
    )
    expect(screen.getByText('HiveKeeper')).toBeInTheDocument()
    expect(screen.getByText('footer-content')).toBeInTheDocument()
  })
})
