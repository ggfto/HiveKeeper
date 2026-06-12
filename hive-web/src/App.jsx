import { useEffect, useState } from 'react'
import { login, logout, resolveUser } from './auth'

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

  // config writes (gateway mode)
  const [selectedAgent, setSelectedAgent] = useState('')
  const [ssid, setSsid] = useState({ name: '', psk: '', vlan: '' })
  const [hive, setHive] = useState({ name: '', password: '' })
  const [configOut, setConfigOut] = useState(null)

  // fleet (registered devices)
  const [devices, setDevices] = useState(null)   // array | 'forbidden' | null

  // OIDC identity (SSO sign-in: who you are + which organizations you belong to)
  const [account, setAccount] = useState(null)   // the oidc User (access_token + profile claims)
  const [me, setMe] = useState(null)             // GET /api/me { userId, email, name, organizations }
  const [activeOrg, setActiveOrg] = useState('')

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
  // Signed in -> act as the user (bearer JWT + the active org), so the gateway enforces your scoped role.
  // Signed out -> the X-Tenant-Key service principal (owner), the pre-login behavior.
  const gw = (path, opts = {}) => {
    const headers = { ...(opts.headers || {}) }
    if (account?.access_token) {
      headers['Authorization'] = `Bearer ${account.access_token}`
      if (activeOrg) headers['X-Org'] = activeOrg
    } else {
      headers['X-Tenant-Key'] = tenantKey
    }
    return fetch('/gw' + path, { ...opts, headers })
  }

  // -- OIDC identity ----------------------------------------------------------
  async function loadMe(user) {
    try {
      const res = await fetch('/gw/api/me', { headers: { Authorization: `Bearer ${user.access_token}` } })
      if (!res.ok) return
      const m = await res.json()
      setMe(m)
      if (m.organizations?.length) setActiveOrg(m.organizations[0].tenantId)
    } catch { /* gateway may be running without the oidc profile; identity falls back to id-token claims */ }
  }

  function signOut() {
    logout()
    setAccount(null); setMe(null); setActiveOrg('')
  }

  async function refreshAgents() {
    try {
      const res = await gw('/api/agents')
      if (res.status === 401) { setAgents([]); setStatus('Invalid tenant key (401).'); return }
      const list = await res.json()
      setAgents(Array.isArray(list) ? list : [])
      setStatus(`${Array.isArray(list) ? list.length : 0} agent(s) for this tenant.`)
      loadDevices()
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

  async function dispatchInventoryStream(agentId) {
    setBusy(true); setEvents([]); setDevice(null); setStatus(`inventory via ${agentId}…`)
    try {
      const res = await gw(`/api/agents/${agentId}/inventory/stream`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ host: conn.host, port: Number(conn.port) })
      })
      if (!res.ok) {
        let err = {}
        try { err = await res.json() } catch { /* non-JSON */ }
        setStatus(`Error ${res.status}: ${err.error || ''} ${err.detail || ''}`)
        return
      }
      await consumeSse(res, {
        onEvent: (ev) => setEvents((p) => [...p, ev]),
        onResult: (r) => { setDevice(r.device); setStatus('Done (via agent, live).') },
        onError: (err) => setStatus(`Error: ${err.error} — ${err.detail || ''}`)
      })
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  // -- config writes (gateway -> agent) ---------------------------------------
  async function writeConfig(op, body, label) {
    if (!selectedAgent) { setStatus('Pick an agent first.'); return }
    if (!conn.host) { setStatus('Set the target host/IP first.'); return }
    setBusy(true); setConfigOut(null); setStatus(`${label} via ${selectedAgent}…`)
    try {
      const res = await gw(`/api/agents/${selectedAgent}/${op}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ host: conn.host, port: Number(conn.port), ...body })
      })
      const r = await res.json()
      if (!res.ok) { setStatus(`Error ${res.status}: ${r.error} ${r.detail || ''}`); return }
      setConfigOut(r)
      setStatus(`${label}: ${r.commands?.length || 0} command(s), saved=${r.saved}.`)
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  const configureSsid = (remove) => writeConfig('configure-ssid',
    { name: ssid.name, psk: ssid.psk, vlan: ssid.vlan ? Number(ssid.vlan) : null, remove },
    remove ? `Remove SSID '${ssid.name}'` : `Configure SSID '${ssid.name}'`)

  const configureHive = () => writeConfig('configure-hive',
    { name: hive.name, password: hive.password }, `Join hive '${hive.name}'`)

  const reboot = () => {
    if (!window.confirm(`Reboot ${conn.host}? It will be offline for ~1-2 minutes.`)) return
    writeConfig('reboot', {}, `Reboot ${conn.host}`)
  }

  // -- fleet (registered devices) ---------------------------------------------
  async function loadDevices() {
    try {
      const res = await gw('/api/devices')
      if (res.status === 403) { setDevices('forbidden'); return }
      if (!res.ok) { setDevices(null); return }
      setDevices(await res.json())
    } catch { setDevices(null) }
  }

  // Adopt a discovered host into the fleet: inventory it through the agent, register it by its real serial.
  async function adoptHost(host) {
    if (!selectedAgent) { setStatus('Pick a target agent in the Config panel first.'); return }
    setBusy(true); setStatus(`Adopting ${host} via ${selectedAgent}…`)
    try {
      const res = await gw(`/api/agents/${selectedAgent}/adopt`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ host })
      })
      const r = await res.json()
      if (!res.ok) { setStatus(`Error ${res.status}: ${r.error} ${r.detail || ''}`); return }
      setStatus(`Adopted ${host} as ${r.serial}${r.model ? ` (${r.model})` : ''}.`)
      loadDevices()
    } catch (e) { setStatus(`Request failed: ${e.message}`) } finally { setBusy(false) }
  }

  // resolve the OIDC session on load: complete an auth-code callback, or restore a stored session
  useEffect(() => {
    resolveUser().then((user) => { if (user) { setAccount(user); loadMe(user) } })
  }, []) // eslint-disable-line

  // re-list agents when you switch the active organization while signed in
  useEffect(() => { if (account && activeOrg) refreshAgents() }, [activeOrg]) // eslint-disable-line

  // populate on load so the dashboard isn't empty
  useEffect(() => { refreshAgents() }, []) // eslint-disable-line
  // default the config target to the first agent once they load
  useEffect(() => {
    if (agents?.length && !selectedAgent) setSelectedAgent(agents[0])
  }, [agents]) // eslint-disable-line

  return (
    <main className="app">
      <header className="top">
        <h1>🐝 HiveKeeper</h1>
        <div className="modeswitch">
          <button className={mode === 'direct' ? 'on' : ''} onClick={() => setMode('direct')}>Direct (local server)</button>
          <button className={mode === 'gateway' ? 'on' : ''} onClick={() => setMode('gateway')}>Gateway (cloud + agent)</button>
        </div>
        <div className="account">
          {account ? (
            <>
              <div className="who">
                <strong>{me?.name || account.profile?.name || account.profile?.preferred_username || 'Signed in'}</strong>
                <span className="muted">{me?.email || account.profile?.email}</span>
              </div>
              {(me?.organizations?.length > 0) && (
                <select value={activeOrg} onChange={(e) => setActiveOrg(e.target.value)} title="Active organization">
                  {me.organizations.map((o) => (
                    <option key={o.tenantId} value={o.tenantId}>{o.tenantName}</option>
                  ))}
                </select>
              )}
              <button className="link" onClick={signOut}>Sign out</button>
            </>
          ) : (
            <button onClick={login}>Sign in</button>
          )}
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
            {account ? (
              <span className="muted">Acting as <strong>{me?.name || account.profile?.name || account.profile?.preferred_username}</strong>
                {activeOrg ? <> in <strong>{me?.organizations?.find((o) => o.tenantId === activeOrg)?.tenantName || activeOrg}</strong></> : null}
                {' '}— the gateway enforces your role. Switch org in the top bar.</span>
            ) : (
              <Field label="Tenant key" value={tenantKey} onChange={(e) => setTenantKey(e.target.value)} placeholder="acme-key" />
            )}
            <button onClick={refreshAgents} disabled={busy}>Refresh agents</button>
          </div>
          <div className="grid">
            <Field label="Target AP host / IP" value={conn.host} onChange={update('host')} placeholder="192.168.1.101" />
            <Field label="Port" type="number" value={conn.port} onChange={update('port')} />
            <Field label="Scan CIDR" value={cidr} onChange={(e) => setCidr(e.target.value)} placeholder="192.168.1.0/24" />
          </div>
          <h3>Agents {agents == null ? '(gateway offline?)' : `(${agents.length})`}</h3>
          {!conn.host && agents?.length > 0 && (
            <p className="muted">Enter the target AP host above to enable inventory/backup (discover scans the agent's own LAN and needs no host).</p>
          )}
          <ul className="events">
            {agents?.map((a) => (
              <li key={a}>
                <strong>{a}</strong>
                {' '}
                <button className="link" onClick={() => dispatchInventoryStream(a)} disabled={busy || !conn.host}>inventory</button>
                {' · '}
                <button className="link" onClick={() => dispatch(a, 'backup')} disabled={busy || !conn.host}>backup</button>
                {' · '}
                <button className="link" onClick={() => dispatchDiscover(a)} disabled={busy}>discover</button>
              </li>
            ))}
            {agents != null && agents.length === 0 && <li className="muted">no agents for this tenant (try acme-key, and start the agent)</li>}
          </ul>
          {status && <p className="status">{status}</p>}
        </section>
      )}

      {mode === 'direct' && (
        <section className="panel">
          <div className="grid">
            <Field label="Host / IP" value={conn.host} onChange={update('host')} placeholder="192.168.1.101" />
            <Field label="Port" type="number" value={conn.port} onChange={update('port')} />
            <Field label="User" value={conn.user} onChange={update('user')} />
            <Field label="Password" type="password" value={conn.password} onChange={update('password')} />
          </div>
          <div className="actions">
            <button onClick={runInventoryDirect} disabled={busy || !conn.host}>Inventory</button>
            <button onClick={runBackupDirect} disabled={busy || !conn.host}>Backup</button>
            <button onClick={runDiscover} disabled={busy}>Discover LAN</button>
          </div>
          {status && <p className="status">{status}</p>}
        </section>
      )}

      {mode === 'gateway' && (
        <section className="panel">
          <h2>Config (writes)</h2>
          <div className="row">
            <label className="field">
              <span>Target agent</span>
              <select value={selectedAgent} onChange={(e) => setSelectedAgent(e.target.value)}>
                <option value="">— pick an agent —</option>
                {agents?.map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
            </label>
            <span className="muted">writes go to <strong>{conn.host || '(set host above)'}</strong> through the agent</span>
          </div>

          <h3>SSID (WPA2-PSK)</h3>
          <div className="grid">
            <Field label="SSID name" value={ssid.name} onChange={(e) => setSsid({ ...ssid, name: e.target.value })} placeholder="HK-DEMO" />
            <Field label="Passphrase" value={ssid.psk} onChange={(e) => setSsid({ ...ssid, psk: e.target.value })} placeholder="min 8 chars" />
            <Field label="VLAN (optional)" type="number" value={ssid.vlan} onChange={(e) => setSsid({ ...ssid, vlan: e.target.value })} placeholder="5" />
          </div>
          <div className="actions">
            <button onClick={() => configureSsid(false)} disabled={busy || !ssid.name || !ssid.psk}>Configure SSID</button>
            <button onClick={() => configureSsid(true)} disabled={busy || !ssid.name}>Remove SSID</button>
          </div>

          <h3>Hive (mesh)</h3>
          <div className="grid">
            <Field label="Hive name" value={hive.name} onChange={(e) => setHive({ ...hive, name: e.target.value })} placeholder="hk-hive" />
            <Field label="Hive password" value={hive.password} onChange={(e) => setHive({ ...hive, password: e.target.value })} placeholder="shared key" />
          </div>
          <div className="actions">
            <button onClick={configureHive} disabled={busy || !hive.name || !hive.password}>Join hive</button>
            <button onClick={reboot} disabled={busy || !conn.host}>Reboot device</button>
          </div>

          {configOut?.commands && (
            <>
              <h3>Applied {configOut.saved ? '(saved)' : ''}</h3>
              <ul className="events">
                {configOut.commands.map((c, i) => {
                  const out = (configOut.outputs?.[i] || '').trim()
                  const bad = /invalid input|unknown keyword|error|incomplete/i.test(out)
                  return (
                    <li key={i} className={bad ? 'err' : ''}>
                      <code>{c}</code>{out ? ` → ${out.replace(/\s+/g, ' ').slice(0, 120)}` : ''}
                    </li>
                  )
                })}
              </ul>
            </>
          )}
          {status && <p className="status">{status}</p>}
        </section>
      )}

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
                {mode === 'gateway' && (
                  <>{' · '}<button className="link" onClick={() => adoptHost(d.host)} disabled={busy || !selectedAgent}>adopt</button></>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {mode === 'gateway' && devices != null && (
        <section className="panel">
          <h2>Fleet — devices {Array.isArray(devices) ? `(${devices.length})` : ''}</h2>
          {devices === 'forbidden' ? (
            <p className="muted">The fleet list needs an organization-level viewer/admin role.</p>
          ) : devices.length === 0 ? (
            <p className="muted">No devices yet. Discover the LAN, then <strong>adopt</strong> a host (needs admin on the agent's site).</p>
          ) : (
            <table className="kv">
              <tbody>
                <tr><td><strong>Label</strong></td><td><strong>Serial</strong></td><td><strong>Model</strong></td><td><strong>Mgmt IP</strong></td><td><strong>Groups</strong></td></tr>
                {devices.map((d) => (
                  <tr key={d.deviceId}>
                    <td>{d.label || '—'}</td><td>{d.serial}</td><td>{d.model || '—'}</td>
                    <td>{d.mgmtIp || '—'}</td><td>{(d.groups || []).length}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </main>
  )
}
