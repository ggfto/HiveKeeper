import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DiscoveredHosts } from './DiscoveredHosts'

describe('DiscoveredHosts', () => {
  it('renders nothing when there are no hosts', () => {
    const { container } = render(<DiscoveredHosts hosts={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('lists discovered hosts and adopts one', () => {
    const onAdopt = vi.fn()
    render(
      <DiscoveredHosts
        hosts={[{ host: '192.168.1.100', sshBanner: 'SSH-2.0-OpenSSH_8.0', looksLikeSsh: true }]}
        onAdopt={onAdopt}
      />,
    )
    expect(screen.getByText('192.168.1.100')).toBeInTheDocument()
    expect(screen.getByText('ssh')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /adopt/i }))
    expect(onAdopt).toHaveBeenCalledWith('192.168.1.100')
  })
})
