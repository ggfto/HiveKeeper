import { useEffect, useState } from 'react'

const initialConn = { host: '', port: 22, user: 'admin', password: 'aerohive' }

/** Parse a fetch ReadableStream of SSE frames, invoking callbacks per named event. */
async function consumeSse(response, { onEvent, onResult, onError }) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let sep
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      let name = 'message'
      let data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) name = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (!data) continue
      const parsed = JSON.parse(data)
      if (name === 'event') onEvent?.(parsed)
      else if (name === 'result') onResult?.(parsed)
      else if (name === 'error') onError?.(parsed)
    }
  }
}

function Field({ label, ...props }) {
  return (
    <label className="field">
      <span>{label}</span>
      <input {...props} />
    </label>
  )
}

function DeviceCard({ device }) {
  if (!device) return null
  return (
    <section className="panel">
      <h2>{device.model || 'Device'} — {device.id?.value}</h2>
      <table className="kv">
        <tbody>
          <tr><td>Serial</td><td>{device.serial || '—'}</td></tr>
          <tr><td>Firmware</td><td>{device.firmwareVersion || '—'}</td></tr>
          <tr><td>Uptime</td><td>{device.uptime || '—'}</td></tr>
          <tr><td>Mgmt IP</td><td>{device.managementIp || '—'}</td></tr>
          <tr><td>Hive</td><td>{device.hiveName || '—'}</td></tr>
        </tbody>
      </table>
      <h3>Radios ({device.radios?.length || 0})</h3>
      <ul>
        {device.radios?.map((r, i) => (
          <li key={i}>{r.name} — {r.mode || '—'}{r.channel != null ? `, ch ${r.channel}` : ''}</li>
        ))}
      </ul>
      <h3>Stations ({device.stations?.length || 0})</h3>
      <ul>
        {device.stations?.map((s, i) => (
          <li key={i}>{s.mac} {s.ipAddress ? `(${s.ipAddress})` : ''} {s.ssid ? `on ${s.ssid}` : ''} {s.rssi != null ? `${s.rssi} dBm` : ''}</li>
        ))}
      </ul>
    </section>
  )
}

export default function App() {
  const [mode, setMode] = useState('direct')
  const [conn, setConn] = useState(initialConn)
  const [events, setEvents] = useState([])
  const [device, setDevice] = useState(null)
  const [discovered, setDiscovered] = useState([])
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  // gateway mode
  const [tenantKey, setTenantKey] = useState('acme-key')
  const [agents, setAgents] = useState(null)
  const [cidr, setCidr] = useState('192.168.1.0/24')

  const update = (k) => (e) => setConn({ ...conn, [k]: e.target.value })

  // -- direct (hive-server) ----------------------------------------------------
  async function runDiscover() {
    setBusy(true); setDiscovered([]); setStatus('Scanning subnet…')
    try {
      const res = await fetch('/api/discover')
      const list = await res.json()
      setDiscovered(Array.isArray(list) ? list : [])
      setStatus(`${Array.isArray(list) ? list.length : 0} host(s) reachable.`)
    } catch (e) {
      setStatus(`server unreachable: ${e.message}`)
    } finally { setBusy(false) }
  }

  async function runInventoryDirect() {
    setBusy(true); setEvents([]); setDevice(null); setStatus('Connecting…')
    try {
      const res = await fetch('/api/inventory/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(conn)
      })
      await consumeSse(res, {
        onEvent: (ev) => setEvents((p) => [...p, ev]),
        onResult: (r) => { setDevice(r.device); setStatus('Done.') },
        onError: (err) => setStatus(`Error: ${err.error} — ${err.detail || ''}`)
      })
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  async function runBackupDirect() {
    setBusy(true); setStatus('Backing up…')
    try {
      const res = await fetch('/api/backup', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(conn)
      })
      const r = await res.json()
      setStatus(res.ok ? `Backup committed: ${r.ref?.commitId?.slice(0, 10)} (${r.configBytes} bytes)` : `Error: ${r.error}`)
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  // -- gateway (hive-gateway -> agent) ----------------------------------------
  const gw = (path, opts = {}) =>
    fetch('/gw' + path, { ...opts, headers: { ...(opts.headers || {}), 'X-Tenant-Key': tenantKey } })

  async function refreshAgents() {
    try {
      const res = await gw('/api/agents')
      if (res.status === 401) { setAgents([]); setStatus('Invalid tenant key (401).'); return }
      const list = await res.json()
      setAgents(Array.isArray(list) ? list : [])
      setStatus(`${Array.isArray(list) ? list.length : 0} agent(s) for this tenant.`)
    } catch (e) { setAgents(null); setStatus(`gateway unreachable: ${e.message}`) }
  }

  async function dispatch(agentId, op) {
    setBusy(true); setDevice(null); setStatus(`${op} via ${agentId}…`)
    try {
      const res = await gw(`/api/agents/${agentId}/${op}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ host: conn.host, port: Number(conn.port) })
      })
      const r = await res.json()
      if (!res.ok) { setStatus(`Error ${res.status}: ${r.error} ${r.detail || ''}`); return }
      if (op === 'inventory') { setDevice(r.device); setStatus('Done (via agent).') }
      else setStatus(`Backup committed: ${r.ref?.commitId?.slice(0, 10)} (${r.configBytes} bytes)`)
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  async function dispatchDiscover(agentId) {
    setBusy(true); setDiscovered([]); setStatus(`Discovering ${cidr} via ${agentId}…`)
    try {
      const res = await gw(`/api/agents/${agentId}/discover`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cidr, port: 22, timeoutMillis: 600 })
      })
      const r = await res.json()
      if (!res.ok) { setStatus(`Error ${res.status}: ${r.error || ''}`); return }
      setDiscovered(r.hosts || [])
      setStatus(`${r.hosts?.length || 0} host(s) reachable (scanned by ${agentId}).`)
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  // populate on load so the dashboard isn't empty
  useEffect(() => { refreshAgents() }, []) // eslint-disable-line

  return (
    <main className="app">
      <header className="top">
        <h1>🐝 HiveKeeper</h1>
        <div className="modeswitch">
          <button className={mode === 'direct' ? 'on' : ''} onClick={() => setMode('direct')}>Direct (local server)</button>
          <button className={mode === 'gateway' ? 'on' : ''} onClick={() => setMode('gateway')}>Gateway (cloud + agent)</button>
        </div>
      </header>
      <p className="sub">
        {mode === 'direct'
          ? 'Mode B — the local server SSHes the AP directly. Discover the LAN, then inventory/backup.'
          : 'Mode C — the gateway dispatches to an on-prem agent (per tenant); credentials never leave the agent.'}
      </p>

      {mode === 'gateway' && (
        <section className="panel">
          <div className="row">
            <Field label="Tenant key" value={tenantKey} onChange={(e) => setTenantKey(e.target.value)} placeholder="acme-key" />
            <Field label="Scan CIDR" value={cidr} onChange={(e) => setCidr(e.target.value)} placeholder="192.168.1.0/24" />
            <button onClick={refreshAgents} disabled={busy}>Refresh agents</button>
          </div>
          <h3>Agents {agents == null ? '(gateway offline?)' : `(${agents.length})`}</h3>
          <ul className="events">
            {agents?.map((a) => (
              <li key={a}>
                <strong>{a}</strong>
                {' '}
                <button className="link" onClick={() => dispatch(a, 'inventory')} disabled={busy || !conn.host}>inventory</button>
                {' · '}
                <button className="link" onClick={() => dispatch(a, 'backup')} disabled={busy || !conn.host}>backup</button>
                {' · '}
                <button className="link" onClick={() => dispatchDiscover(a)} disabled={busy}>discover</button>
              </li>
            ))}
            {agents != null && agents.length === 0 && <li className="muted">no agents for this tenant (try acme-key, and start the agent)</li>}
          </ul>
        </section>
      )}

      <section className="panel">
        <div className="grid">
          <Field label="Host / IP" value={conn.host} onChange={update('host')} placeholder="192.168.1.101" />
          <Field label="Port" type="number" value={conn.port} onChange={update('port')} />
          {mode === 'direct' && <Field label="User" value={conn.user} onChange={update('user')} />}
          {mode === 'direct' && <Field label="Password" type="password" value={conn.password} onChange={update('password')} />}
        </div>
        <div className="actions">
          {mode === 'direct' ? (
            <>
              <button onClick={runInventoryDirect} disabled={busy || !conn.host}>Inventory</button>
              <button onClick={runBackupDirect} disabled={busy || !conn.host}>Backup</button>
              <button onClick={runDiscover} disabled={busy}>Discover LAN</button>
            </>
          ) : (
            <span className="muted">Pick an agent above to inventory/backup this host through it.</span>
          )}
        </div>
        {status && <p className="status">{status}</p>}
      </section>

      {events.length > 0 && (
        <section className="panel">
          <h2>Progress</h2>
          <ul className="events">
            {events.map((ev, i) => (
              <li key={i}>{ev.percent != null ? `[${ev.percent}%] ` : ''}{ev.message || ev.line || ev['@type']}</li>
            ))}
          </ul>
        </section>
      )}

      <DeviceCard device={device} />

      {discovered.length > 0 && (
        <section className="panel">
          <h2>Discovered hosts</h2>
          <ul className="events">
            {discovered.map((d, i) => (
              <li key={i}>
                <button className="link" onClick={() => setConn({ ...conn, host: d.host })}>{d.host}</button>
                {' — '}{d.sshBanner || '(open)'}{d.looksLikeSsh ? '  [ssh]' : ''}
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  )
}
