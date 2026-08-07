import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AddAgentForm } from './AddAgentForm'

describe('AddAgentForm', () => {
  it('enrolls an agent and shows the one-time token + agent .env', async () => {
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    render(<AddAgentForm sites={[]} createEnrollment={createEnrollment} />)
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(createEnrollment).toHaveBeenCalledWith('lab-agent', null)
    expect(await screen.findByText(/enroll-abc123/)).toBeInTheDocument() // the token, embedded in the .env
    expect(screen.getByText(/HIVEKEEPER_AGENT_ID/)).toBeInTheDocument()
  })

  it('templates the connection URLs from the entered agent domain', async () => {
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    render(<AddAgentForm sites={[]} createEnrollment={createEnrollment} />)
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.change(screen.getByPlaceholderText(/agents\.example\.org/i), {
      target: { value: 'agents.gf2.in' },
    })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(await screen.findByText(/wss:\/\/agents\.gf2\.in:9443\/agent/)).toBeInTheDocument()
    expect(screen.getByText(/https:\/\/agents\.gf2\.in:9443/)).toBeInTheDocument()
  })

  it('shows the CA certificate with a download when the gateway returns one', async () => {
    const caPem = '-----BEGIN CERTIFICATE-----\nMIIBcaFake\n-----END CERTIFICATE-----\n'
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123', caPem })
    render(<AddAgentForm sites={[]} createEnrollment={createEnrollment} />)
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(await screen.findByText(/MIIBcaFake/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /download ca\.pem/i })).toBeInTheDocument()
  })

  it('prefills the agent domain from the gateway instead of asking for it', async () => {
    // The gateway reads this off its own server certificate's SAN. Typing it by hand is how you get a value
    // that does not match the certificate, which fails as a TLS error on a machine you are not looking at.
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    render(
      <AddAgentForm
        sites={[]}
        createEnrollment={createEnrollment}
        agentEndpoint={{ host: 'agents.gf2.in', port: 9443 }}
      />,
    )
    expect(screen.getByPlaceholderText(/agents\.example\.org/i)).toHaveValue('agents.gf2.in')

    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(await screen.findByText(/wss:\/\/agents\.gf2\.in:9443\/agent/)).toBeInTheDocument()
  })

  it('lets the operator override the prefilled domain', async () => {
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    render(
      <AddAgentForm sites={[]} createEnrollment={createEnrollment} agentEndpoint={{ host: 'agents.gf2.in' }} />,
    )
    fireEvent.change(screen.getByPlaceholderText(/agents\.example\.org/i), {
      target: { value: 'agents.override.example' },
    })
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(await screen.findByText(/wss:\/\/agents\.override\.example:9443\/agent/)).toBeInTheDocument()
  })

  it('offers the ready-to-run install bundle once the agent is enrolled', async () => {
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    const downloadBundle = vi.fn().mockResolvedValue({})
    render(
      <AddAgentForm
        sites={[]}
        createEnrollment={createEnrollment}
        downloadBundle={downloadBundle}
        agentEndpoint={{ host: 'agents.gf2.in' }}
      />,
    )
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))

    const download = await screen.findByRole('button', { name: /download install bundle/i })
    fireEvent.click(download)
    // The token goes with it: the bundle endpoint packages an existing enrollment, it does not mint a second one.
    expect(downloadBundle).toHaveBeenCalledWith('lab-agent', 'enroll-abc123', 'agents.gf2.in')
  })

  it('surfaces a failure to build the install bundle', async () => {
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    const downloadBundle = vi.fn().mockRejectedValue(new Error('could not build the install bundle'))
    render(
      <AddAgentForm
        sites={[]}
        createEnrollment={createEnrollment}
        downloadBundle={downloadBundle}
        agentEndpoint={{ host: 'agents.gf2.in' }}
      />,
    )
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    fireEvent.click(await screen.findByRole('button', { name: /download install bundle/i }))

    expect(await screen.findByText(/could not build the install bundle/i)).toBeInTheDocument()
    // The agent IS enrolled and its token is still on screen — a failed download must not hide the fallback.
    expect(screen.getByText(/enroll-abc123/)).toBeInTheDocument()
  })

  it('falls back to asking for the domain when the gateway cannot resolve one', () => {
    // No mtls profile, no HIVEKEEPER_AGENT_DOMAIN: an empty field the operator fills beats a guessed hostname.
    render(<AddAgentForm sites={[]} createEnrollment={vi.fn()} agentEndpoint={{ host: null, port: 9443 }} />)
    expect(screen.getByPlaceholderText(/agents\.example\.org/i)).toHaveValue('')
  })

  it('disables submit until an agent id is entered', () => {
    render(<AddAgentForm sites={[]} createEnrollment={vi.fn()} />)
    expect(screen.getByRole('button', { name: /add agent/i })).toBeDisabled()
  })

  it('surfaces a server error (e.g. a duplicate agent id)', async () => {
    const createEnrollment = vi.fn().mockRejectedValue(new Error("an agent 'lab-agent' is already enrolled"))
    render(<AddAgentForm sites={[]} createEnrollment={createEnrollment} />)
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(await screen.findByText(/already enrolled/i)).toBeInTheDocument()
  })
})
