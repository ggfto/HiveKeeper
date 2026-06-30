import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriStatusBadge, MriSectionHeader } from '@mriqbox/ui-kit'
import { ShieldUser, Link2, Flame, Gauge } from 'lucide-react'
import {
  userProfileCommands,
  bindUserProfileCommands,
  removeUserProfileCommands,
  userProfilePolicyCommands,
  ipPolicyCommands,
  macPolicyCommands,
  qosPolicyCommands,
} from '../../lib/hiveosCli'

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

// A labelled <select>; options is [{ value, label }]. Associates the label via htmlFor/id for accessible queries.
function Select({ id, label, value, onChange, options }) {
  return (
    <label className="flex flex-col gap-1" htmlFor={id}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

const KEEP = { value: '', label: '(keep)' }
const NONE = { value: '', label: '(none)' }
const TOGGLE = [KEEP, { value: 'enable', label: 'Enable' }, { value: 'disable', label: 'Disable' }]
// A name picker built from existing object names plus a "(none)" — the operator creates the objects in the
// Firewall / QoS blocks below, then binds them here.
const named = (names) => [NONE, ...names.map((n) => ({ value: n, label: n }))]

/**
 * Create or overwrite a user profile. HiveOS keys a profile by its numeric attribute (0-4095) — re-applying the
 * same name overwrites it — and a profile carries a default VLAN (a single id OR a VLAN group), an optional QoS
 * policy and an optional schedule. The attribute is what an SSID binds to (the bind block below). Each setting
 * goes out as its own CLI line (HiveOS rejects a combined line — confirmed live on an AP230).
 */
function ProfileForm({ qosPolicies, onApply, busy }) {
  const empty = { name: '', attribute: '', vlanType: 'id', vlanId: '', vlanGroup: '', qosPolicy: '', schedule: '' }
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = userProfileCommands(form.name, {
      attribute: form.attribute,
      vlanId: form.vlanType === 'id' ? form.vlanId : '',
      vlanGroup: form.vlanType === 'group' ? form.vlanGroup : '',
      qosPolicy: form.qosPolicy,
      schedule: form.schedule,
    })
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  const ready = form.name.trim() && /^\d+$/.test(form.attribute.trim())

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="text-xs font-medium text-muted-foreground">Add / overwrite user profile</span>
      <p className="text-xs text-muted-foreground">
        A user profile is the policy a client lands in: a default VLAN, an optional QoS policy and schedule. The
        numeric attribute (0-4095) is what an SSID binds to and what RADIUS returns to pick a profile.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <Field label="Name" value={form.name} onChange={set('name')} placeholder="staff" />
        <Field label="Attribute (0-4095)" value={form.attribute} onChange={set('attribute')} placeholder="e.g. 7" />
        <Select
          id="up-vlan-type"
          label="VLAN type"
          value={form.vlanType}
          onChange={set('vlanType')}
          options={[
            { value: 'id', label: 'Single VLAN id' },
            { value: 'group', label: 'VLAN group' },
          ]}
        />
        {form.vlanType === 'id' ? (
          <Field label="VLAN id (1-4094)" value={form.vlanId} onChange={set('vlanId')} placeholder="e.g. 10" />
        ) : (
          <Field label="VLAN group" value={form.vlanGroup} onChange={set('vlanGroup')} placeholder="guest-vlans" />
        )}
        {qosPolicies.length > 0 ? (
          <Select id="up-qos" label="QoS policy (optional)" value={form.qosPolicy} onChange={set('qosPolicy')} options={named(qosPolicies)} />
        ) : (
          <Field label="QoS policy (optional)" value={form.qosPolicy} onChange={set('qosPolicy')} placeholder="def-user-qos" />
        )}
        <Field label="Schedule (optional)" value={form.schedule} onChange={set('schedule')} placeholder="work-hours" />
      </div>
      <MriButton size="sm" disabled={busy || !ready} onClick={apply}>
        Apply profile
      </MriButton>
    </div>
  )
}

/**
 * Bind a user profile to an SSID's security object as the default profile applied to its traffic
 * (`security-object <so> default-user-profile-attr <attr>`). The profile picker carries the attribute so the
 * operator binds by name, not by remembering the number.
 */
function BindBlock({ profiles, ssids, onApply, busy }) {
  const [ssid, setSsid] = useState(ssids[0]?.name || '')
  const [attr, setAttr] = useState(profiles[0]?.attribute != null ? String(profiles[0].attribute) : '')

  const apply = () => {
    const commands = bindUserProfileCommands(ssid, attr)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Link2 className="h-3.5 w-3.5" /> Bind a profile to an SSID
      </span>
      <p className="text-xs text-muted-foreground">
        Sets the default user profile applied to an SSID's traffic. The client lands in this profile's VLAN unless
        RADIUS or a mobile-device policy overrides it.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <Select id="bind-ssid" label="SSID" value={ssid} onChange={setSsid} options={ssids.map((s) => ({ value: s.name, label: s.name }))} />
        <Select
          id="bind-profile"
          label="User profile"
          value={attr}
          onChange={setAttr}
          options={profiles.map((p) => ({ value: String(p.attribute), label: `${p.name} (attr ${p.attribute})` }))}
        />
      </div>
      <MriButton size="sm" disabled={busy || !ssid || !attr} onClick={apply}>
        Bind profile
      </MriButton>
    </div>
  )
}

/**
 * Advanced policy on an existing user profile: a guaranteed-bandwidth SLA (performance-sentinel), L2/L3 firewall
 * bindings + default actions, and a QoS marker-map. Targets a profile by name (like the SSID hardening block) and
 * references firewall policies created in the Firewall block. Only the fields you change are applied.
 */
function ProfilePolicyBlock({ profiles, firewall, onApply, busy }) {
  const empty = {
    perfSentinel: '',
    guaranteedBandwidth: '',
    perfAction: '',
    ipDefaultAction: '',
    ipPolicyFrom: '',
    ipPolicyTo: '',
    macDefaultAction: '',
    macPolicyFrom: '',
    macPolicyTo: '',
    qosMarkerMapType: '8021p',
    qosMarkerMap: '',
  }
  const [name, setName] = useState(profiles[0]?.name || '')
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = userProfilePolicyCommands(name, form)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="text-xs font-medium text-muted-foreground">Advanced policy on a profile</span>
      <p className="text-xs text-muted-foreground">
        Bandwidth SLA, L2/L3 firewall policies (create them below first) and a QoS marker-map for the selected
        profile. Only changed fields are applied.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <Select id="pp-profile" label="Profile" value={name} onChange={setName} options={profiles.map((p) => ({ value: p.name, label: p.name }))} />
        <Select id="pp-ps" label="Performance sentinel" value={form.perfSentinel} onChange={set('perfSentinel')} options={TOGGLE} />
        <Field
          label="Guaranteed bandwidth (kbps)"
          value={form.guaranteedBandwidth}
          onChange={set('guaranteedBandwidth')}
          placeholder="100-500000"
        />
        <Select
          id="pp-ps-action"
          label="Sentinel action"
          value={form.perfAction}
          onChange={set('perfAction')}
          options={[KEEP, { value: 'log', label: 'Log' }, { value: 'boost', label: 'Boost' }]}
        />
        <Select
          id="pp-ip-default"
          label="IP default action"
          value={form.ipDefaultAction}
          onChange={set('ipDefaultAction')}
          options={[
            KEEP,
            { value: 'permit', label: 'Permit' },
            { value: 'deny', label: 'Deny' },
            { value: 'inter-station-traffic-drop', label: 'Drop inter-station' },
          ]}
        />
        <Select
          id="pp-mac-default"
          label="MAC default action"
          value={form.macDefaultAction}
          onChange={set('macDefaultAction')}
          options={[KEEP, { value: 'permit', label: 'Permit' }, { value: 'deny', label: 'Deny' }]}
        />
        <Select id="pp-ip-from" label="IP policy (from client)" value={form.ipPolicyFrom} onChange={set('ipPolicyFrom')} options={named(firewall.ip)} />
        <Select id="pp-ip-to" label="IP policy (to client)" value={form.ipPolicyTo} onChange={set('ipPolicyTo')} options={named(firewall.ip)} />
        <Select id="pp-mac-from" label="MAC policy (from client)" value={form.macPolicyFrom} onChange={set('macPolicyFrom')} options={named(firewall.mac)} />
        <Select id="pp-mac-to" label="MAC policy (to client)" value={form.macPolicyTo} onChange={set('macPolicyTo')} options={named(firewall.mac)} />
        <Select
          id="pp-marker-type"
          label="QoS marker-map type"
          value={form.qosMarkerMapType}
          onChange={set('qosMarkerMapType')}
          options={[
            { value: '8021p', label: '802.1p' },
            { value: 'diffserv', label: 'DiffServ' },
          ]}
        />
        <Field label="QoS marker-map name" value={form.qosMarkerMap} onChange={set('qosMarkerMap')} placeholder="voip-map" />
      </div>
      <MriButton size="sm" disabled={busy || !name} onClick={apply}>
        Apply policy
      </MriButton>
    </div>
  )
}

/**
 * IP / MAC firewall policy objects. An IP policy is created then given a rule
 * (`id / from / to / service / action`); a MAC policy is created here (its rules are entered via Advanced raw-CLI,
 * the combined line being rejected by HiveOS). Existing policies are listed with a remove action. Bind a policy to
 * a user profile in the Advanced-policy block above.
 */
function FirewallBlock({ firewall, onApply, busy }) {
  const emptyIp = { name: '', id: '', from: '', to: '', service: '', action: 'deny' }
  const [ip, setIp] = useState(emptyIp)
  const setIpf = (k) => (v) => setIp((f) => ({ ...f, [k]: v }))
  const [macName, setMacName] = useState('')

  const applyIp = () => {
    const commands = ipPolicyCommands(ip.name, ip)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setIp(emptyIp)
  }
  const applyMac = () => {
    const commands = macPolicyCommands(macName)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setMacName('')
  }
  const removePolicy = (kind, name) => {
    if (!window.confirm(`Remove ${kind === 'ip' ? 'IP' : 'MAC'} policy "${name}"? Profiles binding it lose that firewall.`)) return
    const commands = kind === 'ip' ? ipPolicyCommands(name, { remove: true }) : macPolicyCommands(name, { remove: true })
    onApply({ commands, save: true })
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Flame className="h-3.5 w-3.5" /> Firewall policies
      </span>
      {(firewall.ip.length > 0 || firewall.mac.length > 0) && (
        <ul className="space-y-1" data-testid="firewall-list">
          {firewall.ip.map((n) => (
            <li key={`ip-${n}`} className="flex items-center gap-2 text-xs">
              <MriStatusBadge label="IP" variant="outline" size="xs" />
              <span className="font-mono">{n}</span>
              <MriButton size="sm" variant="ghost" className="ml-auto" disabled={busy} onClick={() => removePolicy('ip', n)}>
                Remove
              </MriButton>
            </li>
          ))}
          {firewall.mac.map((n) => (
            <li key={`mac-${n}`} className="flex items-center gap-2 text-xs">
              <MriStatusBadge label="MAC" variant="outline" size="xs" />
              <span className="font-mono">{n}</span>
              <MriButton size="sm" variant="ghost" className="ml-auto" disabled={busy} onClick={() => removePolicy('mac', n)}>
                Remove
              </MriButton>
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">
        Create an IP policy and add a rule (a permit/deny on a service between source and destination — blanks
        default to <code className="font-mono">any</code>). MAC policies are created here; their rules go through
        Advanced raw-CLI.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <Field label="IP policy name" value={ip.name} onChange={setIpf('name')} placeholder="block-smb" />
        <Field label="Rule id (optional)" value={ip.id} onChange={setIpf('id')} placeholder="1" />
        <Select
          id="fw-action"
          label="Action"
          value={ip.action}
          onChange={setIpf('action')}
          options={[
            { value: 'deny', label: 'Deny' },
            { value: 'permit', label: 'Permit' },
            { value: 'nat', label: 'NAT' },
            { value: 'redirect', label: 'Redirect' },
            { value: 'inter-station-traffic-drop', label: 'Drop inter-station' },
          ]}
        />
        <Field label="From (source)" value={ip.from} onChange={setIpf('from')} placeholder="any" />
        <Field label="To (destination)" value={ip.to} onChange={setIpf('to')} placeholder="any" />
        <Field label="Service" value={ip.service} onChange={setIpf('service')} placeholder="any" />
      </div>
      <MriButton size="sm" disabled={busy || !ip.name.trim()} onClick={applyIp}>
        Create IP policy / rule
      </MriButton>
      <div className="grid gap-2 sm:grid-cols-3 pt-1">
        <Field label="MAC policy name" value={macName} onChange={setMacName} placeholder="mac-allow" />
      </div>
      <MriButton size="sm" variant="outline" disabled={busy || !macName.trim()} onClick={applyMac}>
        Create MAC policy
      </MriButton>
    </div>
  )
}

/**
 * QoS policy objects (per-user-profile rate limit). Enabling QoS globally, creating a policy and setting its
 * user-profile rate limit (kbps) + scheduling weight. A user profile then references the policy by name (the QoS
 * field in the create-profile form above). Existing policies are listed with a remove action.
 */
function QosBlock({ qos, onApply, busy }) {
  const empty = { name: '', rateKbps: '', weight: '10', enableQos: false }
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = qosPolicyCommands(form.name, {
      rateKbps: form.rateKbps,
      weight: form.weight ? Number(form.weight) : 10,
      enableQos: form.enableQos,
    })
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }
  const removePolicy = (name) => {
    if (!window.confirm(`Remove QoS policy "${name}"? Profiles referencing it lose their rate limit.`)) return
    onApply({ commands: qosPolicyCommands(name, { remove: true }), save: true })
  }

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
        <Gauge className="h-3.5 w-3.5" /> QoS policies (rate limit)
      </span>
      {qos.length > 0 && (
        <ul className="space-y-1" data-testid="qos-list">
          {qos.map((n) => (
            <li key={n} className="flex items-center gap-2 text-xs">
              <span className="font-mono">{n}</span>
              <MriButton size="sm" variant="ghost" className="ml-auto" disabled={busy} onClick={() => removePolicy(n)}>
                Remove
              </MriButton>
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-foreground">
        A QoS policy caps a user profile's throughput. Create it here, then reference it from a profile's QoS field
        above. QoS must be enabled globally for policies to take effect.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <Field label="QoS policy name" value={form.name} onChange={set('name')} placeholder="voip" />
        <Field label="Rate limit (kbps)" value={form.rateKbps} onChange={set('rateKbps')} placeholder="0-2000000" />
        <Field label="Weight (0-1000)" value={form.weight} onChange={set('weight')} placeholder="10" />
      </div>
      <label className="flex items-center gap-2 text-xs text-muted-foreground">
        <input type="checkbox" checked={form.enableQos} onChange={(e) => set('enableQos')(e.target.checked)} />
        Enable QoS globally (qos enable)
      </label>
      <MriButton size="sm" disabled={busy || !form.name.trim()} onClick={apply}>
        Apply QoS policy
      </MriButton>
    </div>
  )
}

/**
 * Network policy for a device: the HiveOS user profiles (default VLAN / QoS / schedule a client lands in), read
 * from the AP's running-config, plus forms to create/overwrite one, bind it to an SSID, attach advanced policy
 * (bandwidth SLA, firewall, marker-map), and manage the firewall + QoS policy objects those bindings reference.
 * Writes go through apply-config; the list re-reads after each change. A single running-config read feeds the
 * profile list, SSID picker, firewall names and QoS names (loadPolicy returns { profiles, ssids, firewall, qos }).
 */
export function PolicySection({ device, loadPolicy, onApply, busy }) {
  const [state, setState] = useState(null)

  const refresh = useCallback(() => {
    setState(null)
    return loadPolicy(device)
      .then((r) =>
        setState({
          profiles: r?.profiles || [],
          ssids: r?.ssids || [],
          firewall: r?.firewall || { ip: [], mac: [] },
          qos: r?.qos || [],
        }),
      )
      .catch(() => setState({ profiles: [], ssids: [], firewall: { ip: [], mac: [] }, qos: [] }))
  }, [loadPolicy, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  const dispatch = (body) => Promise.resolve(onApply(device, body)).then(refresh)
  const remove = (name) => {
    if (!window.confirm(`Remove user profile "${name}"? Clients relying on it fall back to the default profile.`)) return
    dispatch({ commands: removeUserProfileCommands(name), save: true })
  }

  const profiles = state?.profiles || []
  const ssids = state?.ssids || []
  const firewall = state?.firewall || { ip: [], mac: [] }
  const qos = state?.qos || []

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <MriSectionHeader icon={ShieldUser} title="Network policy (user profiles)" />
        <MriButton size="sm" variant="ghost" disabled={busy} onClick={refresh}>
          Refresh
        </MriButton>
      </div>

      {state === null ? (
        <p className="text-sm text-muted-foreground">Loading policy from the AP…</p>
      ) : profiles.length === 0 ? (
        <p className="text-sm text-muted-foreground">No user profiles configured.</p>
      ) : (
        <ul className="space-y-2" data-testid="profile-list">
          {profiles.map((p) => (
            <li key={p.name} className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2">
              <span className="font-medium">{p.name}</span>
              <MriStatusBadge label={`attr ${p.attribute}`} variant="outline" size="xs" />
              {p.vlanId != null && <MriStatusBadge label={`VLAN ${p.vlanId}`} variant="outline" size="xs" />}
              {p.vlanGroup && <MriStatusBadge label={`VLAN grp ${p.vlanGroup}`} variant="outline" size="xs" />}
              {p.qosPolicy && <span className="text-xs text-muted-foreground">QoS {p.qosPolicy}</span>}
              {p.schedule && <span className="text-xs text-muted-foreground">sched {p.schedule}</span>}
              {p.boundTo?.length > 0 && (
                <span className="text-xs text-muted-foreground">↔ {p.boundTo.join(', ')}</span>
              )}
              <MriButton
                size="sm"
                variant="destructive"
                className="ml-auto"
                disabled={busy}
                onClick={() => remove(p.name)}
              >
                Remove
              </MriButton>
            </li>
          ))}
        </ul>
      )}

      {state !== null && (
        <>
          <ProfileForm qosPolicies={qos} busy={busy} onApply={dispatch} />
          {ssids.length > 0 && profiles.length > 0 && (
            <BindBlock profiles={profiles} ssids={ssids} busy={busy} onApply={dispatch} />
          )}
          {profiles.length > 0 && (
            <ProfilePolicyBlock profiles={profiles} firewall={firewall} busy={busy} onApply={dispatch} />
          )}
          <FirewallBlock firewall={firewall} busy={busy} onApply={dispatch} />
          <QosBlock qos={qos} busy={busy} onApply={dispatch} />
        </>
      )}
    </div>
  )
}
