import { useEffect, useRef, useState } from 'react'
import { MriButton, MriInput, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { Plus, Download, Package } from 'lucide-react'

/**
 * The agent's `.env`, ready to paste — the fallback for an operator installing by hand rather than unpacking
 * the bundle. The URLs are templated on :9443 from the agent domain, which the gateway now supplies (it reads
 * it off its own server certificate); a blank domain leaves a clear placeholder rather than a wrong value.
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
 * Register a new agent: pick an id (and optionally pin it to a site), mint a one-time enrollment token, and hand
 * the operator a **ready-to-run install bundle** — a zip with the compose, a `.env` filled in with the token,
 * the URLs and generated secrets, the CA certificate and a README. The token, the URLs and the CA are still
 * shown on screen underneath, because an operator installing the agent as a native service rather than a
 * container needs the values themselves, and because a download that fails must not take the token with it (it
 * is shown ONCE — the agent appears in the list above only after it dials in).
 *
 * `createEnrollment(agentId, siteId)` resolves to { agentId, token, caPem }; `downloadBundle(agentId, token,
 * domain)` fetches and saves the zip; `agentEndpoint` is { host, port } as the gateway resolved it, whose host
 * may be null when it cannot tell.
 */
export function AddAgentForm({ sites = [], createEnrollment, downloadBundle, agentEndpoint, busy }) {
  const [agentId, setAgentId] = useState('')
  const [agentDomain, setAgentDomain] = useState('')
  const [siteId, setSiteId] = useState('')
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [working, setWorking] = useState(false)
  const [downloading, setDownloading] = useState(false)
  // Once the operator types a domain it is theirs; a late-arriving endpoint response must not overwrite it.
  const domainEdited = useRef(false)

  useEffect(() => {
    if (!domainEdited.current && agentEndpoint?.host) setAgentDomain(agentEndpoint.host)
  }, [agentEndpoint?.host])

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

  const getBundle = async () => {
    if (!result || !downloadBundle) return
    setDownloading(true)
    setError('')
    try {
      await downloadBundle(result.agentId, result.token, agentDomain.trim())
    } catch (e) {
      setError(e.message || 'Could not build the install bundle.')
    } finally {
      setDownloading(false)
    }
  }

  return (
    <section className="space-y-3 rounded-md border border-border p-3">
      <MriSectionHeader icon={Plus} title="Add agent" />
      <p className="text-xs text-muted-foreground">
        Register an on-prem agent and download its install bundle — the compose, its configuration and the CA,
        ready to run. It appears in the list above right away (offline) and flips to online once it connects.
      </p>
      <div className="grid items-end gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Agent id</span>
          <MriInput value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="lab-agent" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">
            Agent domain {agentEndpoint?.host ? '(from the gateway certificate)' : '(for the URLs)'}
          </span>
          <MriInput
            value={agentDomain}
            onChange={(e) => {
              domainEdited.current = true
              setAgentDomain(e.target.value)
            }}
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
        <div className="space-y-3 rounded-md border border-border bg-card p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-muted-foreground">
              Agent <span className="font-mono">{result.agentId}</span> enrolled. Unpack this on a machine on the
              same network as your APs and run <span className="font-mono">docker compose up -d</span>.
            </p>
            {downloadBundle && (
              <MriButton size="sm" disabled={downloading} onClick={getBundle}>
                <Package className="mr-1 h-3.5 w-3.5" />
                {downloading ? 'Building…' : 'Download install bundle'}
              </MriButton>
            )}
          </div>

          {/* Everything below is the same enrollment, in the raw — for a native-service install, and so a
              failed download never costs the operator the token, which is shown exactly once. */}
          <details className="space-y-2">
            <summary className="cursor-pointer text-xs text-muted-foreground">
              Installing by hand instead? Token, URLs and CA certificate (shown once)
            </summary>
            <div className="space-y-2 pt-2">
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
          </details>
        </div>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </section>
  )
}
