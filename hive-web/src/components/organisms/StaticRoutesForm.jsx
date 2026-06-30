import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriStatusBadge, MriSectionHeader } from '@mriqbox/ui-kit'
import { Route } from 'lucide-react'
import { staticRouteCommands } from '../../lib/hiveosCli'

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

function routeLabel(r) {
  if (r.type === 'default') return `default → ${r.gateway}`
  const dest = r.type === 'net' ? `${r.dest}/${r.netmask}` : r.dest
  return `${r.type} ${dest} → ${r.gateway}${r.metric != null ? ` (metric ${r.metric})` : ''}`
}

/**
 * Static IP routes. Lists the routes read from the AP (default/net/host) and lets the operator add a net or host
 * route or remove one. Grammar confirmed live on an AP230: `ip route net <ip> <mask> gateway <gw> [metric <m>]`,
 * `ip route host <ip> gateway <gw> [metric <m>]`. DANGER-adjacent — a wrong route can blackhole traffic (and the
 * gateway must be on a directly-connected subnet or HiveOS drops it) — so add and remove are confirm-gated. The
 * default route is shown read-only here; it is edited in the management form. `loadRoutes` is optional (no list
 * without it). Dispatched through apply-config; the list re-reads after each change.
 */
export function StaticRoutesForm({ device, loadRoutes, onApply, busy }) {
  const [routes, setRoutes] = useState(loadRoutes ? null : [])
  const empty = { type: 'net', dest: '', netmask: '', gateway: '', metric: '' }
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const refresh = useCallback(() => {
    if (!loadRoutes) return Promise.resolve()
    setRoutes(null)
    return loadRoutes(device)
      .then((list) => setRoutes(Array.isArray(list) ? list : []))
      .catch(() => setRoutes([]))
  }, [loadRoutes, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  const dispatch = (commands) => {
    if (commands.length === 0 || !onApply) return
    Promise.resolve(onApply(device, { commands, save: true })).then(refresh)
  }
  const add = () => {
    const commands = staticRouteCommands(form)
    if (commands.length === 0) return
    if (!window.confirm(`Add route "${commands[0]}"? A wrong route can blackhole traffic.`)) return
    dispatch(commands)
    setForm(empty)
  }
  const remove = (r) => {
    const commands = staticRouteCommands({ ...r, metric: r.metric, remove: true })
    if (commands.length === 0) return
    if (!window.confirm(`Remove route "${routeLabel(r)}"?`)) return
    dispatch(commands)
  }

  const ready = form.dest.trim() && form.gateway.trim() && (form.type === 'host' || form.netmask.trim())

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Route} title="Static routes" />
      <p className="text-xs text-muted-foreground">
        Extra routes beyond the default gateway. The gateway must be on a directly-connected subnet. A wrong route
        can blackhole traffic, so add and remove are confirm-gated.
      </p>

      {routes === null ? (
        <p className="text-sm text-muted-foreground">Loading routes from the AP…</p>
      ) : routes.length === 0 ? (
        <p className="text-sm text-muted-foreground">No routes configured.</p>
      ) : (
        <ul className="space-y-1" data-testid="route-list">
          {routes.map((r, i) => (
            <li key={`${r.type}-${r.dest}-${i}`} className="flex items-center gap-2 text-xs">
              <MriStatusBadge label={r.type} variant="outline" size="xs" />
              <span className="font-mono">{routeLabel(r)}</span>
              {r.type !== 'default' && (
                <MriButton size="sm" variant="ghost" className="ml-auto" disabled={busy} onClick={() => remove(r)}>
                  Remove
                </MriButton>
              )}
            </li>
          ))}
        </ul>
      )}

      <div className="grid gap-2 sm:grid-cols-3">
        <label className="flex flex-col gap-1" htmlFor="route-type">
          <span className="text-xs text-muted-foreground">Type</span>
          <select id="route-type" className={SELECT_CLASS} value={form.type} onChange={(e) => set('type')(e.target.value)}>
            <option value="net">Network</option>
            <option value="host">Host</option>
          </select>
        </label>
        <Field label="Destination IP" value={form.dest} onChange={set('dest')} placeholder="10.9.9.0" />
        {form.type === 'net' && (
          <Field label="Netmask" value={form.netmask} onChange={set('netmask')} placeholder="255.255.255.0" />
        )}
        <Field label="Gateway" value={form.gateway} onChange={set('gateway')} placeholder="192.168.1.1" />
        <Field label="Metric (optional)" value={form.metric} onChange={set('metric')} placeholder="—" />
      </div>
      <MriButton size="sm" disabled={busy || !ready} onClick={add}>
        Add route
      </MriButton>
    </div>
  )
}
