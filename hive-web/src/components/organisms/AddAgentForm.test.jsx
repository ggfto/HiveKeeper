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
