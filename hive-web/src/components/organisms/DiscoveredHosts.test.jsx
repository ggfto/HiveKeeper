import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DiscoveredHosts } from './DiscoveredHosts'

const hosts = [{ host: '192.168.1.100', sshBanner: 'SSH-2.0-OpenSSH_8.0', looksLikeSsh: true }]

describe('DiscoveredHosts', () => {
  it('renders nothing when there are no hosts', () => {
    const { container } = render(<DiscoveredHosts hosts={[]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('lists discovered hosts and adopts one', () => {
    const onAdopt = vi.fn()
    render(<DiscoveredHosts hosts={hosts} onAdopt={onAdopt} />)
    expect(screen.getByText('192.168.1.100')).toBeInTheDocument()
    expect(screen.getByText('ssh')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /adopt/i }))
    expect(onAdopt).toHaveBeenCalledWith('192.168.1.100')
  })

  it('identifies a host when an onIdentify handler is provided', () => {
    const onIdentify = vi.fn()
    render(<DiscoveredHosts hosts={hosts} onAdopt={vi.fn()} onIdentify={onIdentify} />)
    fireEvent.click(screen.getByRole('button', { name: /identify/i }))
    expect(onIdentify).toHaveBeenCalledWith('192.168.1.100')
  })

  it('shows the model and a tested badge once a host is identified', () => {
    render(
      <DiscoveredHosts
        hosts={hosts}
        onAdopt={vi.fn()}
        identified={{ '192.168.1.100': { model: 'AP230', serial: 'SER', hiveOs: true } }}
      />,
    )
    expect(screen.getByText('AP230')).toBeInTheDocument()
    expect(screen.getByText('tested')).toBeInTheDocument()
  })

  it('shows an unsupported badge for a host that did not identify as HiveOS', () => {
    render(
      <DiscoveredHosts hosts={hosts} onAdopt={vi.fn()} identified={{ '192.168.1.100': { hiveOs: false } }} />,
    )
    expect(screen.getByText('unsupported')).toBeInTheDocument()
  })
})
