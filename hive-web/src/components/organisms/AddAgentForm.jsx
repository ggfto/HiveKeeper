import { useState } from 'react'
import { MriButton, MriInput, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { Plus } from 'lucide-react'

/** The agent's WebSocket endpoint on this deployment. The web app can't know the gateway's external URL, so we
 *  template it from the current host on :8090 (the dev default) and tell the operator to adjust it. */
function connectCommand(agentId, token) {
  const host = typeof window !== 'undefined' ? window.location.hostname : 'your-gateway-host'
  return [`HIVEKEEPER_AGENT_ID=${agentId}`, `HIVEKEEPER_GATEWAY_URL=ws://${host}:8090/agent?token=${token}`].join('\n')
}

/**
 * Register a new agent: pick an id (and optionally pin it to a site), mint a one-time enrollment token, and
 * show the connect command the operator drops into their on-prem agent. The token is shown ONCE; the agent
 * appears in the connected list above only after it dials in. `createEnrollment(agentId, siteId)` resolves to
 * { agentId, token }.
 */
export function AddAgentForm({ sites = [], createEnrollment, busy }) {
  const [agentId, setAgentId] = useState('')
  const [siteId, setSiteId] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [working, setWorking] = useState(false)

  const siteOptions = [{ label: '(no site)', value: '' }, ...sites.map((s) => ({ label: s.name, value: s.siteId }))]

  const submit = async () => {
    if (!agentId.trim()) return
    setWorking(true)
    setError('')
    setResult(null)
    try {
      setResult(await createEnrollment(agentId.trim(), siteId || null))
    } catch (e) {
      setError(e.message || 'Enrollment failed.')
    } finally {
      setWorking(false)
    }
  }

  return (
    <section className="space-y-3 rounded-md border border-border p-3">
      <MriSectionHeader icon={Plus} title="Add agent" />
      <p className="text-xs text-muted-foreground">
        Register an on-prem agent to get its enrollment token, then configure the agent with it. It will appear
        above once it connects.
      </p>
      <div className="grid items-end gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Agent id</span>
          <MriInput value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="lab-agent" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Site (optional)</span>
          <MriSelect options={siteOptions} value={siteId} onChange={setSiteId} placeholder="(no site)" />
        </label>
        <MriButton size="sm" disabled={busy || working || !agentId.trim()} onClick={submit}>
          {working ? 'Enrolling…' : 'Add agent'}
        </MriButton>
      </div>

      {result && (
        <div className="space-y-2 rounded-md border border-border bg-card p-3">
          <p className="text-xs text-muted-foreground">
            Agent <span className="font-mono">{result.agentId}</span> enrolled. Configure your agent with this
            (shown once):
          </p>
          <pre className="overflow-x-auto whitespace-pre-wrap rounded bg-muted p-2 font-mono text-xs">
            {connectCommand(result.agentId, result.token)}
          </pre>
          <p className="text-xs text-muted-foreground">
            Adjust the host/port (and use <span className="font-mono">wss://</span> behind TLS) for your
            deployment.
          </p>
        </div>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </section>
  )
}
