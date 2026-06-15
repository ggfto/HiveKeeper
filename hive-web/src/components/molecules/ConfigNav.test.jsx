import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ConfigNav } from './ConfigNav'

const sections = [
  { id: 'overview', label: 'Overview' },
  { id: 'wifi', label: 'Wi-Fi' },
  { id: 'radio', label: 'Radio' },
]

describe('ConfigNav', () => {
  it('renders every category', () => {
    render(<ConfigNav sections={sections} active="overview" onSelect={() => {}} />)
    expect(screen.getByText('Overview')).toBeInTheDocument()
    expect(screen.getByText('Wi-Fi')).toBeInTheDocument()
    expect(screen.getByText('Radio')).toBeInTheDocument()
  })

  it('selects a category on click', () => {
    const onSelect = vi.fn()
    render(<ConfigNav sections={sections} active="overview" onSelect={onSelect} />)
    fireEvent.click(screen.getByText('Radio'))
    expect(onSelect).toHaveBeenCalledWith('radio')
  })
})
