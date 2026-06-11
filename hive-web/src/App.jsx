import { useState } from 'react'

const initialConn = { host: '', port: 22, user: 'admin', password: '' }

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

export default function App() {
  const [conn, setConn] = useState(initialConn)
  const [events, setEvents] = useState([])
  const [device, setDevice] = useState(null)
  const [discovered, setDiscovered] = useState([])
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)

  const update = (k) => (e) => setConn({ ...conn, [k]: e.target.value })

  async function runInventory() {
    setBusy(true); setEvents([]); setDevice(null); setStatus('Connecting…')
    try {
      const res = await fetch('/api/inventory/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(conn)
      })
      await consumeSse(res, {
        onEvent: (ev) => setEvents((prev) => [...prev, ev]),
        onResult: (r) => { setDevice(r.device); setStatus('Done.') },
        onError: (err) => setStatus(`Error: ${err.error} — ${err.detail || ''}`)
      })
    } catch (e) {
      setStatus(`Request failed: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  async function runBackup() {
    setBusy(true); setStatus('Backing up…')
    try {
      const res = await fetch('/api/backup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(conn)
      })
      const r = await res.json()
      setStatus(res.ok ? `Backup committed: ${r.ref?.commitId?.slice(0, 10)} (${r.configBytes} bytes)` : `Error: ${r.error}`)
    } catch (e) {
      setStatus(`Request failed: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  async function runDiscover() {
    setBusy(true); setDiscovered([]); setStatus('Scanning subnet…')
    try {
      const res = await fetch('/api/discover')
      const list = await res.json()
      setDiscovered(Array.isArray(list) ? list : [])
      setStatus(`${Array.isArray(list) ? list.length : 0} host(s) reachable.`)
    } catch (e) {
      setStatus(`Request failed: ${e.message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="app">
      <h1>🐝 HiveKeeper</h1>
      <p className="sub">Standalone Aerohive / Extreme HiveOS management — local server.</p>

      <section className="panel">
        <div className="grid">
          <Field label="Host / IP" value={conn.host} onChange={update('host')} placeholder="192.168.1.101" />
          <Field label="Port" type="number" value={conn.port} onChange={update('port')} />
          <Field label="User" value={conn.user} onChange={update('user')} />
          <Field label="Password" type="password" value={conn.password} onChange={update('password')} />
        </div>
        <div className="actions">
          <button onClick={runInventory} disabled={busy || !conn.host}>Inventory</button>
          <button onClick={runBackup} disabled={busy || !conn.host}>Backup</button>
          <button onClick={runDiscover} disabled={busy}>Discover LAN</button>
        </div>
        {status && <p className="status">{status}</p>}
      </section>

      {events.length > 0 && (
        <section className="panel">
          <h2>Progress</h2>
          <ul className="events">
            {events.map((ev, i) => (
              <li key={i}>
                {ev.percent != null ? `[${ev.percent}%] ` : ''}{ev.message || ev.line || ev['@type']}
              </li>
            ))}
          </ul>
        </section>
      )}

      {device && (
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
      )}

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
