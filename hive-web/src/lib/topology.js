/**
 * Build the infrastructure map's graph from the data we already have: the fleet structure (sites + devices,
 * each device carrying its site and the agent that fronts it) plus a live status per device (from inventory —
 * client count, hive, cloud, reachability). Pure: it returns React Flow `nodes` + `edges` (with computed
 * positions), so the whole layout is unit-testable without a canvas.
 *
 * Shape: Site nodes in the left column, their APs in the right column; a solid edge site -> AP, and a dashed
 * edge between APs that share a hive (the mesh grouping — the closest mesh signal we have without per-link
 * neighbor data). The agent is carried on the AP node (a badge), not a separate column, to keep the graph
 * readable. Devices with no site bucket under a synthetic "Unassigned" site.
 */

const COL = { site: 0, ap: 340 }
const ROW_H = 104

export function buildTopology({ sites = [], agents = [], devices = [], statuses = {} } = {}) {
  const connected = new Set(Array.isArray(agents) ? agents : [])
  const nodes = []
  const edges = []

  // Bucket devices by site; siteless devices fall under a synthetic key.
  const bySite = new Map()
  for (const d of devices) {
    const key = d.siteId || '__none__'
    if (!bySite.has(key)) bySite.set(key, [])
    bySite.get(key).push(d)
  }

  // Every site is shown (even empty ones), then a synthetic "Unassigned" bucket if any device has no site.
  const siteList = sites.map((s) => ({ siteId: s.siteId, name: s.name }))
  if (bySite.has('__none__')) siteList.push({ siteId: '__none__', name: 'Unassigned' })

  let row = 0
  for (const site of siteList) {
    const own = bySite.get(site.siteId) || []
    const span = Math.max(1, own.length)
    nodes.push({
      id: `site:${site.siteId}`,
      type: 'site',
      position: { x: COL.site, y: (row + (span - 1) / 2) * ROW_H },
      data: { label: site.name, apCount: own.length },
    })
    own.forEach((d, i) => {
      const st = statuses[d.deviceId] || {}
      const apId = `ap:${d.deviceId}`
      nodes.push({
        id: apId,
        type: 'ap',
        position: { x: COL.ap, y: (row + i) * ROW_H },
        data: {
          deviceId: d.deviceId,
          label: d.label || d.serial || d.deviceId,
          model: d.model || null,
          agentId: d.agentId || null,
          hive: st.hive || null,
          clientCount: st.clientCount ?? null,
          cloud: st.cloud ?? null,
          // Reachable if its live status said so; otherwise fall back to whether its agent is connected at all.
          online: st.online ?? connected.has(d.agentId),
        },
      })
      edges.push({ id: `e:${site.siteId}:${d.deviceId}`, source: `site:${site.siteId}`, target: apId })
    })
    row += span
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
      edges.push({
        id: `mesh:${hive}:${i}`,
        source: aps[i - 1],
        target: aps[i],
        data: { mesh: true },
      })
    }
  }

  return { nodes, edges }
}
