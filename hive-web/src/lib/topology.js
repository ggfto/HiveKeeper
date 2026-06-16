import { sortBySignal } from './stations'

/**
 * Build the infrastructure map's graph from the data we already have: the fleet structure (sites + devices,
 * each device carrying its site and the agent that fronts it) plus a live status per device (from inventory —
 * client count, hive, cloud, reachability, and the stations themselves). Pure: it returns React Flow `nodes` +
 * `edges` with computed positions, so the whole layout is unit-testable without a canvas.
 *
 * Columns: Site (left) -> AP (middle) -> client (right, when `showClients`). A solid edge site -> AP, a dashed
 * edge between APs that share a hive (the closest mesh signal we have without per-link neighbor data), and an
 * edge AP -> each of its connected clients. To stay readable, clients per AP are capped (strongest signal
 * first) with a single "+N more" node for the remainder. Devices with no site bucket under "Unassigned".
 */

const COL = { site: 0, ap: 340, client: 680 }
const ROW_H = 104
const CLIENT_ROW_H = 60

export function buildTopology(
  { sites = [], agents = [], devices = [], statuses = {} } = {},
  { showClients = false, clientCap = 6 } = {},
) {
  const connected = new Set(Array.isArray(agents) ? agents : [])
  const nodes = []
  const edges = []

  const bySite = new Map()
  for (const d of devices) {
    const key = d.siteId || '__none__'
    if (!bySite.has(key)) bySite.set(key, [])
    bySite.get(key).push(d)
  }
  const siteList = sites.map((s) => ({ siteId: s.siteId, name: s.name }))
  if (bySite.has('__none__')) siteList.push({ siteId: '__none__', name: 'Unassigned' })

  let y = 0 // running vertical cursor, in pixels
  for (const site of siteList) {
    const own = bySite.get(site.siteId) || []
    const siteTop = y

    if (own.length === 0) {
      y += ROW_H // an empty site still occupies a slot
    } else {
      for (const d of own) {
        const st = statuses[d.deviceId] || {}
        const apId = `ap:${d.deviceId}`
        const all = showClients ? sortBySignal(st.stations || []) : []
        const shown = all.slice(0, clientCap)
        const overflow = showClients ? Math.max(0, all.length - shown.length) : 0
        const rows = Math.max(1, shown.length + (overflow > 0 ? 1 : 0))
        const blockTop = y

        nodes.push({
          id: apId,
          type: 'ap',
          position: { x: COL.ap, y: blockTop + ((rows - 1) * CLIENT_ROW_H) / 2 },
          data: {
            deviceId: d.deviceId,
            label: d.label || d.serial || d.deviceId,
            model: d.model || null,
            agentId: d.agentId || null,
            hive: st.hive || null,
            clientCount: st.clientCount ?? (st.stations ? st.stations.length : null),
            cloud: st.cloud ?? null,
            online: st.online ?? connected.has(d.agentId),
          },
        })
        edges.push({ id: `e:${site.siteId}:${d.deviceId}`, source: `site:${site.siteId}`, target: apId })

        shown.forEach((c, j) => {
          const cid = `client:${d.deviceId}:${c.mac || j}`
          nodes.push({
            id: cid,
            type: 'client',
            position: { x: COL.client, y: blockTop + j * CLIENT_ROW_H },
            data: { label: c.mac || '—', mac: c.mac || null, ip: c.ipAddress || null, ssid: c.ssid || null, rssi: c.rssi ?? null },
          })
          edges.push({ id: `e:${apId}:${cid}`, source: apId, target: cid, data: { client: true } })
        })
        if (overflow > 0) {
          const oid = `more:${d.deviceId}`
          nodes.push({
            id: oid,
            type: 'client',
            position: { x: COL.client, y: blockTop + shown.length * CLIENT_ROW_H },
            data: { label: `+${overflow} more`, more: true },
          })
          edges.push({ id: `e:${apId}:${oid}`, source: apId, target: oid, data: { client: true } })
        }
        y = blockTop + rows * CLIENT_ROW_H
      }
    }

    const siteBottom = y
    nodes.push({
      id: `site:${site.siteId}`,
      type: 'site',
      position: { x: COL.site, y: Math.max(siteTop, (siteTop + siteBottom) / 2 - ROW_H / 2) },
      data: { label: site.name, apCount: own.length },
    })
  }

  // Mesh: chain APs that share a hive (i -> i+1, not a full clique) so the mesh group reads as one strand.
  const byHive = new Map()
  for (const d of devices) {
    const hive = statuses[d.deviceId]?.hive
    if (!hive) continue
    if (!byHive.has(hive)) byHive.set(hive, [])
    byHive.get(hive).push(`ap:${d.deviceId}`)
  }
  for (const [hive, aps] of byHive) {
    for (let i = 1; i < aps.length; i += 1) {
      edges.push({ id: `mesh:${hive}:${i}`, source: aps[i - 1], target: aps[i], data: { mesh: true } })
    }
  }

  return { nodes, edges }
}
