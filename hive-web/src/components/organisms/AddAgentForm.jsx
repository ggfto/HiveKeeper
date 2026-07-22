import { useState } from 'react'
import { MriButton, MriInput, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { Plus, Download } from 'lucide-react'

/**
 * The agent's `.env`, ready to paste. The web app can't know the gateway's external agent hostname (it is a
 * deployment fact, distinct from the console's own host), so the operator supplies it once and we template the
 * mTLS uplink URLs on :9443 from it. A blank domain leaves a clear placeholder rather than a wrong value.
 */
function agentEnv(agentId, token, domain) {
  const d = (domain || '').trim() || '<agent-domain>'
  return [
    `HIVEKEEPER_AGENT_ID=${agentId}`,
    `HIVEKEEPER_AGENT_DOMAIN=${d}`,
    `HIVEKEEPER_GATEWAY_URL=wss://${d}:9443/agent`,
    `HIVEKEEPER_ENROLLMENT_URL=https://${d}:9443`,
    `HIVEKEEPER_ENROLLMENT_TOKEN=${token}`,
    `HIVEKEEPER_ENROLLMENT_CACERT=/etc/hivekeeper/ca.pem`,
  ].join('\n')
}

/** Save the CA PEM as a ca.pem file the operator drops next to the compose — beats transcribing it from a log. */
function downloadCaPem(caPem) {
  if (typeof URL.createObjectURL !== 'function') return // non-browser (tests): the copyable block still shows it
  const url = URL.createObjectURL(new Blob([caPem], { type: 'application/x-pem-file' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'ca.pem'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * Register a new agent: pick an id (and optionally pin it to a site), mint a one-time enrollment token, and show
 * EVERYTHING the on-prem agent needs — the token, the connection URLs, and the CA certificate (with a download) —
 * so nothing has to be dug out of a container log. The token is shown ONCE; the agent appears in the connected
 * list above only after it dials in. `createEnrollment(agentId, siteId)` resolves to { agentId, token, caPem }.
 */
export function AddAgentForm({ sites = [], createEnrollment, busy }) {
  const [agentId, setAgentId] = useState('')
  const [agentDomain, setAgentDomain] = useState('')
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
        Register an on-prem agent to get its enrollment token, the connection URLs, and the CA certificate. It
        appears in the list above right away (offline) and flips to online once it connects.
      </p>
      <div className="grid items-end gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Agent id</span>
          <MriInput value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="lab-agent" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Agent domain (for the URLs)</span>
          <MriInput
            value={agentDomain}
            onChange={(e) => setAgentDomain(e.target.value)}
            placeholder="e.g. agents.example.org"
          />
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
            Agent <span className="font-mono">{result.agentId}</span> enrolled. Drop this into your agent&apos;s{' '}
            <span className="font-mono">.env</span> (shown once):
          </p>
          <pre className="overflow-x-auto whitespace-pre-wrap rounded bg-muted p-2 font-mono text-xs">
            {agentEnv(result.agentId, result.token, agentDomain)}
          </pre>
          {!agentDomain.trim() && (
            <p className="text-xs text-primary">Fill in the agent domain above to complete the URLs.</p>
          )}
          {result.caPem ? (
            <div className="space-y-1">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs text-muted-foreground">
                  CA certificate — save as <span className="font-mono">ca.pem</span> next to the agent compose:
                </span>
                <MriButton size="sm" variant="outline" onClick={() => downloadCaPem(result.caPem)}>
                  <Download className="mr-1 h-3.5 w-3.5" />
                  Download ca.pem
                </MriButton>
              </div>
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded bg-muted p-2 font-mono text-[10px] leading-tight">
                {result.caPem}
              </pre>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">
              This gateway returned no CA — copy <span className="font-mono">ca.pem</span> from the pki-init
              container log.
            </p>
          )}
        </div>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </section>
  )
}
