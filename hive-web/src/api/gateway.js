/**
 * The HiveKeeper control-plane (hive-gateway) client. Every call is tenant-scoped by the auth it carries:
 * signed in -> a bearer JWT + the active org (X-Org), so the gateway enforces the user's scoped role; signed
 * out -> the X-Tenant-Key service principal (owner), the dev fallback. Pure + injectable (fetch + auth) so the
 * whole surface is unit-testable without a browser.
 */

/** Build the auth headers for a request from the current auth state, merging any extras. */
export function buildHeaders(auth, extra = {}) {
  const headers = { ...extra }
  if (auth?.accessToken) {
    headers['Authorization'] = `Bearer ${auth.accessToken}`
    if (auth.org) headers['X-Org'] = auth.org
  } else if (auth?.tenantKey) {
    headers['X-Tenant-Key'] = auth.tenantKey
  }
  return headers
}

/** Map a bulk target ({kind:'org'|'site'|'group', id}) to the request body the gateway expects. */
export function bulkBody(target) {
  if (target?.kind === 'site') return { siteId: target.id }
  if (target?.kind === 'group') return { groupId: target.id }
  return {}
}

/**
 * Create a gateway client.
 * @param getAuth   () => ({ accessToken, org } | { tenantKey }) — read at call time so org switches take effect.
 * @param fetchImpl the fetch to use (injected for tests).
 * @param baseUrl   the same-origin proxy prefix (default '/gw').
 */
export function createGateway({ getAuth = () => ({}), fetchImpl = fetch, baseUrl = '/gw' } = {}) {
  async function req(path, { method = 'GET', body } = {}) {
    const headers = buildHeaders(getAuth(), body !== undefined ? { 'Content-Type': 'application/json' } : {})
    const res = await fetchImpl(baseUrl + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })
    const text = await res.text()
    const data = text ? JSON.parse(text) : null
    if (!res.ok) {
      const error = new Error((data && (data.error || data.detail)) || `HTTP ${res.status}`)
      error.status = res.status
      error.body = data
      throw error
    }
    return data
  }

  return {
    req,
    // first-run setup (unauthenticated; gated server-side by a setup token + the uninitialized state)
    setupStatus: () => req('/api/setup/status'),
    setup: (body) => req('/api/setup', { method: 'POST', body }),
    // deployment shape, read once on boot (solo = single-user, single-AP local mode)
    mode: () => req('/api/mode'),
    // identity
    me: () => req('/api/me'),
    // organization members (admin; owner-gated for owner changes — the gateway enforces)
    members: () => req('/api/members'),
    addMember: (body) => req('/api/members', { method: 'POST', body }),
    setMemberRole: (userId, role) => req(`/api/members/${userId}`, { method: 'PATCH', body: { role } }),
    removeMember: (userId) => req(`/api/members/${userId}`, { method: 'DELETE' }),
    // fleet structure
    agents: () => req('/api/agents'),
    // every enrolled agent by durable identity (connected or not) — the source for the agent list; `agents`
    // above stays the currently-connected set used to compute online badges.
    agentsAll: () => req('/api/agents/all'),
    sites: () => req('/api/sites'),
    groups: () => req('/api/groups'),
    devices: () => req('/api/devices'),
    operations: () => req('/api/operations'),
    createEnrollment: (body) => req('/api/enrollments', { method: 'POST', body }),
    createSite: (name) => req('/api/sites', { method: 'POST', body: { name } }),
    renameSite: (siteId, name) => req(`/api/sites/${siteId}`, { method: 'PATCH', body: { name } }),
    deleteSite: (siteId) => req(`/api/sites/${siteId}`, { method: 'DELETE' }),
    createGroup: (name, siteId) => req('/api/groups', { method: 'POST', body: { name, siteId: siteId || null } }),
    updateGroup: (groupId, body) => req(`/api/groups/${groupId}`, { method: 'PATCH', body }),
    deleteGroup: (groupId) => req(`/api/groups/${groupId}`, { method: 'DELETE' }),
    tagDevice: (deviceId, groupId) => req(`/api/devices/${deviceId}/groups`, { method: 'POST', body: { groupId } }),
    untagDevice: (deviceId, groupId) => req(`/api/devices/${deviceId}/groups/${groupId}`, { method: 'DELETE' }),
    updateDevice: (deviceId, body) => req(`/api/devices/${deviceId}`, { method: 'PATCH', body }),
    // device reachability: which agents can drive this device. Adopting adds one; the operator can also
    // add/remove agents by hand so a second agent can serve the same AP (active/standby, load, migration).
    deviceAgents: (deviceId) => req(`/api/devices/${deviceId}/agents`),
    addDeviceAgent: (deviceId, agentId) => req(`/api/devices/${deviceId}/agents`, { method: 'POST', body: { agentId } }),
    removeDeviceAgent: (deviceId, agentId) => req(`/api/devices/${deviceId}/agents/${agentId}`, { method: 'DELETE' }),
    // agent operations (the cloud sends intent only; the agent holds credentials)
    agentOp: (agentId, op, body = {}) => req(`/api/agents/${agentId}/${op}`, { method: 'POST', body }),
    inventory: (agentId, host, port = 22) => req(`/api/agents/${agentId}/inventory`, { method: 'POST', body: { host, port } }),
    backup: (agentId, host, port = 22) => req(`/api/agents/${agentId}/backup`, { method: 'POST', body: { host, port } }),
    // re-apply a saved running-config (additive replay); `save` persists it with `save config`.
    restore: (agentId, host, runningConfig, { save = true, port = 22 } = {}) =>
      req(`/api/agents/${agentId}/restore`, { method: 'POST', body: { host, port, runningConfig, save } }),
    // upgrade firmware from a URL the AP can reach; `reboot` activates it. LAB/UNTESTED — see HiveOsDriver.
    firmwareUpgrade: (agentId, host, imageUrl, { reboot = true, port = 22 } = {}) =>
      req(`/api/agents/${agentId}/firmware-upgrade`, { method: 'POST', body: { host, port, imageUrl, reboot } }),
    discover: (agentId, cidr, port = 22) =>
      req(`/api/agents/${agentId}/discover`, { method: 'POST', body: { cidr, port, timeoutMillis: 600 } }),
    adopt: (agentId, body) => req(`/api/agents/${agentId}/adopt`, { method: 'POST', body }),
    // set/rotate a device's SSH credential. The secret is sealed to the agent's key at the gateway and never
    // persisted in the cloud; `alsoSetOnDevice` also changes the admin password on the AP itself.
    setCredential: (agentId, body) => req(`/api/agents/${agentId}/set-credential`, { method: 'POST', body }),
    // The organization's backup destination: one git repository the whole fleet pushes its config history to.
    // The token is write-only — it goes up, is sealed to each agent, and is never returned by the read.
    getBackupDestination: () => req('/api/backup-destination'),
    setBackupDestination: (body) => req('/api/backup-destination', { method: 'POST', body }),
    clearBackupDestination: () => req('/api/backup-destination', { method: 'DELETE' }),
    // PPSK users (Caminho B): mint/rotate/revoke per-user Private PSKs owned on-prem by the agent's RADIUS.
    // list returns metadata only; create/rotate return the generated PSK ONCE (the cloud never stores it).
    ppskUsers: (agentId) => req(`/api/agents/${agentId}/ppsk-users`),
    createPpskUser: (agentId, body) => req(`/api/agents/${agentId}/ppsk-users`, { method: 'POST', body }),
    rotatePpskUser: (agentId, id) => req(`/api/agents/${agentId}/ppsk-users/${id}/rotate`, { method: 'POST' }),
    revokePpskUser: (agentId, id) => req(`/api/agents/${agentId}/ppsk-users/${id}`, { method: 'DELETE' }),
    // fleet alerting: notification channels + thresholds for the background poller, and the current firing set.
    alertSettings: () => req('/api/alerts/settings'),
    saveAlertSettings: (body) => req('/api/alerts/settings', { method: 'POST', body }),
    alertChannels: () => req('/api/alerts/channels'),
    addAlertChannel: (body) => req('/api/alerts/channels', { method: 'POST', body }),
    setAlertChannelEnabled: (id, enabled) =>
      req(`/api/alerts/channels/${id}`, { method: 'POST', body: { enabled } }),
    removeAlertChannel: (id) => req(`/api/alerts/channels/${id}`, { method: 'DELETE' }),
    firingAlerts: () => req('/api/alerts/firing'),
    // bulk read ops across a scope
    bulk: (op, target) => req(`/api/fleet/bulk/${op}`, { method: 'POST', body: bulkBody(target) }),
    // bulk WRITE: apply the same CLI lines (a config template) across a scope; `save` persists with `save config`.
    bulkApplyConfig: (target, commands, save = true) =>
      req('/api/fleet/bulk/apply-config', { method: 'POST', body: { ...bulkBody(target), commands, save } }),
  }
}
