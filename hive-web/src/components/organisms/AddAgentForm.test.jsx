import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AddAgentForm } from './AddAgentForm'

describe('AddAgentForm', () => {
  it('enrolls an agent and shows the one-time token + connect command', async () => {
    const createEnrollment = vi.fn().mockResolvedValue({ agentId: 'lab-agent', token: 'enroll-abc123' })
    render(<AddAgentForm sites={[]} createEnrollment={createEnrollment} />)
    fireEvent.change(screen.getByPlaceholderText(/lab-agent/i), { target: { value: 'lab-agent' } })
    fireEvent.click(screen.getByRole('button', { name: /add agent/i }))
    expect(createEnrollment).toHaveBeenCalledWith('lab-agent', null)
    expect(await screen.findByText(/enroll-abc123/)).toBeInTheDocument() // the token, embedded in the command
    expect(screen.getByText(/HIVEKEEPER_AGENT_ID/)).toBeInTheDocument()
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
