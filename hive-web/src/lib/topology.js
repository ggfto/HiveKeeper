import { sortBySignal } from './stations'

/**
 * Build the infrastructure map's graph from the data we already have: the fleet structure (sites + devices,
 * each device carrying its site and the agent that fronts it) plus a live status per device (from inventory —
 * client count, hive, cloud, reachability, and the stations themselves). Pure: it returns React Flow `nodes` +
 * `edges` with computed positions, so the whole layout is unit-testable without a canvas.
 *
 * Columns: Site (left) -> AP (middle) -> client (right, when `showClients`). Within a site the APs are ordered
 * so same-hive ones sit together, and a translucent "hiveGroup" box is drawn behind each cluster of 2+ APs in
 * the same hive (the closest mesh signal we have without per-link neighbor data). A solid edge links site -> AP,
 * a dashed edge chains same-hive APs, and an edge links AP -> each connected client. Clients per AP are capped
 * (strongest signal first) with a single "+N more" node, unless that AP is in `expanded`. Siteless devices
 * bucket under "Unassigned".
 */

const COL = { site: 0, ap: 340, client: 680 }
const ROW_H = 104
const CLIENT_ROW_H = 60
const AP_W = 200
const AP_H = 86
const GROUP_PAD = 16
const GROUP_LABEL_H = 22

/** Order a site's devices so same-hive APs are contiguous (first-seen hive order; hive-less APs last). */
function orderByHive(devs, statuses) {
  const order = []
  const buckets = new Map()
  const noHive = []
  for (const d of devs) {
    const hive = statuses[d.deviceId]?.hive
    if (!hive) {
      noHive.push(d)
      continue
    }
    if (!buckets.has(hive)) {
      buckets.set(hive, [])
      order.push(hive)
    }
    buckets.get(hive).push(d)
  }
  return [...order.flatMap((h) => buckets.get(h)), ...noHive]
}

export function buildTopology(
  { sites = [], agents = [], devices = [], statuses = {} } = {},
  { showClients = false, clientCap = 6, expanded = [] } = {},
) {
  const connected = new Set(Array.isArray(agents) ? agents : [])
  const expandedSet = new Set(expanded)
  const nodes = []
  const edges = []
  const groups = [] // hive boxes; prepended so they render behind the rest

  const bySite = new Map()
  for (const d of devices) {
    const key = d.siteId || '__none__'
    if (!bySite.has(key)) bySite.set(key, [])
    bySite.get(key).push(d)
  }
  const siteList = sites.map((s) => ({ siteId: s.siteId, name: s.name }))
  if (bySite.has('__none__')) siteList.push({ siteId: '__none__', name: 'Unassigned' })

  let y = 0
  for (const site of siteList) {
    const own = orderByHive(bySite.get(site.siteId) || [], statuses)
    const siteTop = y
    const hiveExtent = new Map() // hive -> { count, minY, maxY } within this site

    if (own.length === 0) {
      y += ROW_H
    } else {
      for (const d of own) {
        const st = statuses[d.deviceId] || {}
        const apId = `ap:${d.deviceId}`
        const cap = expandedSet.has(d.deviceId) ? Infinity : clientCap
        const all = showClients ? sortBySignal(st.stations || []) : []
        const shown = cap === Infinity ? all : all.slice(0, cap)
        const overflow = showClients ? Math.max(0, all.length - shown.length) : 0
        const rows = Math.max(1, shown.length + (overflow > 0 ? 1 : 0))
        const blockTop = y
        const apY = blockTop + ((rows - 1) * CLIENT_ROW_H) / 2

        nodes.push({
          id: apId,
          type: 'ap',
          position: { x: COL.ap, y: apY },
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
            data: { label: `+${overflow} more`, more: true, deviceId: d.deviceId },
          })
          edges.push({ id: `e:${apId}:${oid}`, source: apId, target: oid, data: { client: true } })
        }

        if (st.hive) {
          const e = hiveExtent.get(st.hive) || { count: 0, minY: Infinity, maxY: -Infinity }
          e.count += 1
          e.minY = Math.min(e.minY, apY)
          e.maxY = Math.max(e.maxY, apY)
          hiveExtent.set(st.hive, e)
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

    // A box behind each cluster of 2+ same-hive APs in this site.
    for (const [hive, e] of hiveExtent) {
      if (e.count < 2) continue
      groups.push({
        id: `hive:${site.siteId}:${hive}`,
        type: 'hiveGroup',
        position: { x: COL.ap - GROUP_PAD, y: e.minY - GROUP_PAD - GROUP_LABEL_H },
        zIndex: -1,
        selectable: false,
        draggable: false,
        style: { width: AP_W + GROUP_PAD * 2, height: e.maxY - e.minY + AP_H + GROUP_PAD * 2 + GROUP_LABEL_H },
        data: { label: hive },
      })
    }
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

  return { nodes: [...groups, ...nodes], edges }
}
