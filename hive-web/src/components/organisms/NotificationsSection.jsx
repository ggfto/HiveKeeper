import { useCallback, useEffect, useState } from 'react'
import {
  MriButton,
  MriInput,
  MriSectionHeader,
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { Send } from 'lucide-react'
import { ConfirmButton } from '../molecules/ConfirmButton'

const SELECT = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'
const SEVERITIES = ['critical', 'warning', 'info']

/**
 * Server-side alert delivery config (the background poller): notification channels (webhook / email) and the
 * thresholds the poller evaluates. Distinct from the on-demand FleetAlertsPanel above it — this is what gets
 * delivered unattended. Reading needs viewer; changing channels/thresholds needs an org admin (the gateway
 * enforces, surfaced inline on a 403). Self-contained + handler-injected so it is unit-testable.
 */
export function NotificationsSection({
  loadSettings,
  onSaveSettings,
  loadChannels,
  onAddChannel,
  onToggleChannel,
  onRemoveChannel,
  busy,
}) {
  const [channels, setChannels] = useState(undefined) // undefined=loading, null=unreachable, 'forbidden', array
  const [settings, setSettings] = useState(null)
  const [form, setForm] = useState({ type: 'webhook', target: '', minSeverity: 'warning' })
  const [maxStations, setMaxStations] = useState('')
  const [pollEnabled, setPollEnabled] = useState(true)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')

  const reloadChannels = useCallback(() => {
    if (!loadChannels) return
    loadChannels()
      .then((list) => setChannels(Array.isArray(list) ? list : (list?.channels ?? [])))
      .catch((e) => setChannels(e?.status === 403 ? 'forbidden' : null))
  }, [loadChannels])

  useEffect(() => {
    reloadChannels()
  }, [reloadChannels])

  useEffect(() => {
    if (!loadSettings) return
    loadSettings()
      .then((s) => {
        setSettings(s)
        setMaxStations(String(s?.maxStations ?? 30))
        setPollEnabled(s?.pollEnabled !== false)
      })
      .catch(() => setSettings(null))
  }, [loadSettings])

  const act = async (fn) => {
    setWorking(true)
    setError('')
    try {
      return await fn()
    } catch (e) {
      setError(e?.status === 403 ? 'Managing notifications needs an organization admin role.' : e?.body?.detail || e?.message || 'Request failed')
      return null
    } finally {
      setWorking(false)
    }
  }

  const saveSettings = () =>
    act(async () => {
      const n = Number(maxStations)
      if (!Number.isFinite(n) || n < 1) {
        setError('Max clients must be a number ≥ 1.')
        return
      }
      const s = await onSaveSettings({ maxStations: n, pollEnabled })
      if (s) setSettings(s)
    })

  const addChannel = async () => {
    if (!form.target.trim() || !onAddChannel) return
    const r = await act(() => onAddChannel({ type: form.type, target: form.target.trim(), minSeverity: form.minSeverity }))
    if (r) {
      setForm({ type: form.type, target: '', minSeverity: form.minSeverity })
      reloadChannels()
    }
  }

  const toggle = async (c) => {
    await act(() => onToggleChannel(c.id, !c.enabled))
    reloadChannels()
  }

  const remove = async (c) => {
    await act(() => onRemoveChannel(c.id))
    reloadChannels()
  }

  const disabled = busy || working

  return (
    <div className="space-y-4 rounded-lg border border-border bg-card p-4">
      <MriSectionHeader
        icon={Send}
        title="Alert delivery"
        description="Background poller: scans the fleet on a schedule and delivers breaches to these channels."
      />

      <div className="space-y-2 rounded-md border border-border p-3">
        <span className="text-xs font-medium text-muted-foreground">Poller thresholds</span>
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">Max clients per AP</span>
            <MriInput type="number" value={maxStations} onChange={(e) => setMaxStations(e.target.value)} placeholder="30" />
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={pollEnabled} onChange={(e) => setPollEnabled(e.target.checked)} />
            <span>Background polling enabled</span>
          </label>
          <MriButton size="sm" disabled={disabled} onClick={saveSettings}>
            Save thresholds
          </MriButton>
        </div>
      </div>

      <div className="space-y-2 rounded-md border border-border p-3">
        <span className="text-xs font-medium text-muted-foreground">Add a channel</span>
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">Type</span>
            <select className={SELECT} value={form.type} onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))} aria-label="Channel type">
              <option value="webhook">Webhook</option>
              <option value="email">Email</option>
            </select>
          </label>
          <label className="flex flex-1 flex-col gap-1">
            <span className="text-xs text-muted-foreground">{form.type === 'email' ? 'Email address' : 'Webhook URL'}</span>
            <MriInput value={form.target} onChange={(e) => setForm((f) => ({ ...f, target: e.target.value }))} placeholder={form.type === 'email' ? 'ops@acme.com' : 'https://hooks.example/...'} />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">Min severity</span>
            <select className={SELECT} value={form.minSeverity} onChange={(e) => setForm((f) => ({ ...f, minSeverity: e.target.value }))} aria-label="Min severity">
              {SEVERITIES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <MriButton size="sm" disabled={disabled || !form.target.trim()} onClick={addChannel}>
            Add channel
          </MriButton>
        </div>
      </div>

      {error && <p className="text-xs text-destructive">{error}</p>}

      {channels === 'forbidden' && (
        <p className="text-sm text-muted-foreground">Managing notifications needs an organization admin role.</p>
      )}
      {channels === null && <p className="text-sm text-muted-foreground">Gateway unreachable.</p>}
      {Array.isArray(channels) && channels.length === 0 && (
        <p className="text-sm text-muted-foreground">No channels yet — add a webhook or email above.</p>
      )}
      {Array.isArray(channels) && channels.length > 0 && (
        <MriTable>
          <MriTableHeader>
            <MriTableRow>
              <MriTableHead>Type</MriTableHead>
              <MriTableHead>Target</MriTableHead>
              <MriTableHead>Min severity</MriTableHead>
              <MriTableHead>Status</MriTableHead>
              <MriTableHead> </MriTableHead>
            </MriTableRow>
          </MriTableHeader>
          <MriTableBody>
            {channels.map((c) => (
              <MriTableRow key={c.id}>
                <MriTableCell>{c.type}</MriTableCell>
                <MriTableCell className="font-mono text-xs">{c.target}</MriTableCell>
                <MriTableCell>{c.minSeverity}</MriTableCell>
                <MriTableCell>
                  <MriStatusBadge variant={c.enabled ? 'success' : 'muted'}>
                    {c.enabled ? 'enabled' : 'disabled'}
                  </MriStatusBadge>
                </MriTableCell>
                <MriTableCell>
                  <div className="flex justify-end gap-2">
                    <MriButton size="sm" variant="secondary" disabled={disabled} onClick={() => toggle(c)}>
                      {c.enabled ? 'Disable' : 'Enable'}
                    </MriButton>
                    <ConfirmButton size="sm" disabled={disabled} onConfirm={() => remove(c)}>
                      Remove
                    </ConfirmButton>
                  </div>
                </MriTableCell>
              </MriTableRow>
            ))}
          </MriTableBody>
        </MriTable>
      )}
    </div>
  )
}
