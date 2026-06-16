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
    // identity
    me: () => req('/api/me'),
    // organization members (admin; owner-gated for owner changes — the gateway enforces)
    members: () => req('/api/members'),
    addMember: (body) => req('/api/members', { method: 'POST', body }),
    setMemberRole: (userId, role) => req(`/api/members/${userId}`, { method: 'PATCH', body: { role } }),
    removeMember: (userId) => req(`/api/members/${userId}`, { method: 'DELETE' }),
    // fleet structure
    agents: () => req('/api/agents'),
    sites: () => req('/api/sites'),
    groups: () => req('/api/groups'),
    devices: () => req('/api/devices'),
    operations: () => req('/api/operations'),
    createEnrollment: (body) => req('/api/enrollments', { method: 'POST', body }),
    createSite: (name) => req('/api/sites', { method: 'POST', body: { name } }),
    renameSite: (siteId, name) => req(`/api/sites/${siteId}`, { method: 'PATCH', body: { name } }),
    deleteSite: (siteId) => req(`/api/sites/${siteId}`, { method: 'DELETE' }),
    createGroup: (name, siteId) => req('/api/groups', { method: 'POST', body: { name, siteId: siteId || null } }),
    renameGroup: (groupId, name) => req(`/api/groups/${groupId}`, { method: 'PATCH', body: { name } }),
    deleteGroup: (groupId) => req(`/api/groups/${groupId}`, { method: 'DELETE' }),
    tagDevice: (deviceId, groupId) => req(`/api/devices/${deviceId}/groups`, { method: 'POST', body: { groupId } }),
    untagDevice: (deviceId, groupId) => req(`/api/devices/${deviceId}/groups/${groupId}`, { method: 'DELETE' }),
    updateDevice: (deviceId, body) => req(`/api/devices/${deviceId}`, { method: 'PATCH', body }),
    // agent operations (the cloud sends intent only; the agent holds credentials)
    agentOp: (agentId, op, body = {}) => req(`/api/agents/${agentId}/${op}`, { method: 'POST', body }),
    inventory: (agentId, host, port = 22) => req(`/api/agents/${agentId}/inventory`, { method: 'POST', body: { host, port } }),
    backup: (agentId, host, port = 22) => req(`/api/agents/${agentId}/backup`, { method: 'POST', body: { host, port } }),
    discover: (agentId, cidr, port = 22) =>
      req(`/api/agents/${agentId}/discover`, { method: 'POST', body: { cidr, port, timeoutMillis: 600 } }),
    adopt: (agentId, body) => req(`/api/agents/${agentId}/adopt`, { method: 'POST', body }),
    // bulk read ops across a scope
    bulk: (op, target) => req(`/api/fleet/bulk/${op}`, { method: 'POST', body: bulkBody(target) }),
  }
}
