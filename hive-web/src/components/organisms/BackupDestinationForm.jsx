import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriStatusBadge, MriSectionHeader } from '@mriqbox/ui-kit'
import { GitBranch, TriangleAlert, CircleCheck } from 'lucide-react'

/**
 * Where the organization's config backups are pushed.
 *
 * One repository for the whole fleet: the per-device directories inside it already keep sites from
 * colliding, so several sites share one history and one token to rotate rather than N.
 *
 * The token is write-only. It is sent once, encrypted at rest by the gateway, and sealed to each agent's own
 * key on the way out — the read never returns it, so this form can show that a destination exists without
 * ever being able to display the secret. That is why a saved destination shows an empty token field rather
 * than a masked one: a masked value implies we could show it.
 */
export function BackupDestinationForm({ gateway, busy }) {
  const [current, setCurrent] = useState(null)
  const [repoUrl, setRepoUrl] = useState('')
  const [branch, setBranch] = useState('main')
  const [username, setUsername] = useState('')
  const [token, setToken] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [delivery, setDelivery] = useState(null)

  const load = useCallback(async () => {
    try {
      const d = await gateway.getBackupDestination()
      setCurrent(d)
      if (d?.configured) {
        setRepoUrl(d.repoUrl || '')
        setBranch(d.branch || 'main')
        setUsername(d.username || '')
      }
    } catch (e) {
      setError(e.message)
    }
  }, [gateway])

  useEffect(() => {
    load()
  }, [load])

  const save = async () => {
    setSaving(true)
    setError(null)
    setDelivery(null)
    try {
      const r = await gateway.setBackupDestination({ repoUrl, branch, username, token })
      setDelivery(r.agents || [])
      setToken('')
      await load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  const clear = async () => {
    setSaving(true)
    setError(null)
    setDelivery(null)
    try {
      const r = await gateway.clearBackupDestination()
      setDelivery(r.agents || [])
      setRepoUrl('')
      setToken('')
      await load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  const disabled = busy || saving
  const failed = (delivery || []).filter((d) => !d.delivered)

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={GitBranch} title="Backup destination" />
      <p className="text-xs text-muted-foreground">
        Every agent in this organization pushes its config history to this repository, so the history
        survives the machine that captured it. Backups keep working without one — they just stay on the
        agent.
      </p>

      {current?.configured && (
        <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
          <MriStatusBadge label="configured" variant="success" />
          <span className="font-mono">{current.repoUrl}</span>
          <span>on {current.branch}</span>
          {current.updatedAt && <span>· updated {current.updatedAt}</span>}
        </div>
      )}

      <div className="grid gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1" htmlFor="bd-repo">
          <span className="text-xs text-muted-foreground">Repository URL</span>
          <MriInput
            id="bd-repo"
            value={repoUrl}
            onChange={(e) => setRepoUrl(e.target.value)}
            placeholder="https://github.com/acme/hivekeeper-backups.git"
          />
        </label>
        <label className="flex flex-col gap-1" htmlFor="bd-branch">
          <span className="text-xs text-muted-foreground">Branch</span>
          <MriInput id="bd-branch" value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" />
        </label>
        <label className="flex flex-col gap-1" htmlFor="bd-token">
          <span className="text-xs text-muted-foreground">
            Access token {current?.configured && '(leave blank to keep the current one out of reach)'}
          </span>
          <MriInput
            id="bd-token"
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="ghp_…"
          />
        </label>
        <label className="flex flex-col gap-1" htmlFor="bd-user">
          <span className="text-xs text-muted-foreground">Username (blank = hivekeeper)</span>
          <MriInput
            id="bd-user"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder="hivekeeper"
          />
        </label>
      </div>

      <div className="rounded-md border border-border bg-muted/40 p-2 text-xs text-muted-foreground">
        Give the token access to <strong>this repository only</strong>. HiveKeeper stores it encrypted so it
        can hand it to an agent that is offline today or enrolled next month, which means anyone holding both
        the database and the encryption key could read it.
      </div>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-2 text-xs">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span data-testid="bd-error">{error}</span>
        </div>
      )}

      {delivery && delivery.length > 0 && (
        <div className="space-y-1 text-xs" data-testid="bd-delivery">
          {failed.length === 0 ? (
            <div className="flex items-center gap-2 text-muted-foreground">
              <CircleCheck className="h-4 w-4 shrink-0" />
              <span>
                Delivered to all {delivery.length} connected agent{delivery.length === 1 ? '' : 's'}.
              </span>
            </div>
          ) : (
            <div className="flex items-start gap-2 rounded-md border border-warning/40 bg-warning/10 p-2">
              <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
              <span>
                {failed.length} of {delivery.length} agents did not take it: {failed.map((d) => d.agentId).join(', ')}.
                They will pick it up the next time they connect.
              </span>
            </div>
          )}
        </div>
      )}

      {delivery && delivery.length === 0 && (
        <p className="text-xs text-muted-foreground" data-testid="bd-no-agents">
          No agents are connected right now. They will pick this up when they connect.
        </p>
      )}

      <div className="flex gap-2">
        <MriButton size="sm" disabled={disabled || !repoUrl || !token} onClick={save}>
          {saving ? 'Saving…' : 'Save destination'}
        </MriButton>
        {current?.configured && (
          <MriButton size="sm" variant="destructive" disabled={disabled} onClick={clear}>
            Stop pushing
          </MriButton>
        )}
      </div>
    </div>
  )
}
