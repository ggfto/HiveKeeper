import { useCallback, useEffect, useState } from 'react'
import {
  MriButton,
  MriInput,
  MriSectionHeader,
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { KeyRound } from 'lucide-react'
import { ConfirmButton } from '../molecules/ConfirmButton'

function Field({ label, value, onChange, placeholder, type }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} type={type} />
    </label>
  )
}

const EMPTY = { securityObject: '', userGroup: '', username: '', vlanId: '', userProfileAttr: '' }
const numOrNull = (v) => (v == null || String(v).trim() === '' ? null : Number(v))

/**
 * PPSK users (Caminho B): mint, rotate, and revoke per-user Private PSKs that HiveKeeper owns on-prem (the
 * agent's RADIUS store). The cloud never stores the usable key, so a freshly generated or rotated PSK is shown
 * exactly ONCE, here, for the operator to hand to the user. Listing needs viewer; create/rotate/revoke need
 * operator — a non-authorized caller gets a forbidden note (the gateway enforces the role).
 *
 * Self-contained: it loads its own list on mount and after every mutation. The handlers are injected so the
 * whole organism is unit-testable: {@code loadPpskUsers(device) -> users[]}, {@code onCreate(device, body)},
 * {@code onRotate(device, id)} and {@code onRotate} return {@code { user, psk }} (psk one-time);
 * {@code onRevoke(device, id)}.
 */
export function PpskUsersSection({ device, loadPpskUsers, onCreate, onRotate, onRevoke, busy }) {
  const [users, setUsers] = useState(undefined) // undefined=loading, null=unreachable, 'forbidden', or array
  const [form, setForm] = useState(EMPTY)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [revealed, setRevealed] = useState(null) // { username, psk } — the one-time key
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const reload = useCallback(() => {
    if (!loadPpskUsers) return
    loadPpskUsers(device)
      .then((list) => setUsers(Array.isArray(list) ? list : (list?.users ?? [])))
      .catch((e) => setUsers(e?.status === 403 ? 'forbidden' : null))
  }, [device, loadPpskUsers])

  useEffect(() => {
    reload()
  }, [reload])

  const act = async (fn) => {
    setWorking(true)
    setError('')
    try {
      const r = await fn()
      reload()
      return r
    } catch (e) {
      setError(e?.body?.detail || e?.message || 'Request failed')
      return null
    } finally {
      setWorking(false)
    }
  }

  const create = async () => {
    if (!form.securityObject.trim() || !form.username.trim() || !onCreate) return
    const body = {
      securityObject: form.securityObject.trim(),
      userGroup: form.userGroup.trim() || null,
      username: form.username.trim(),
      vlanId: numOrNull(form.vlanId),
      userProfileAttr: numOrNull(form.userProfileAttr),
    }
    const r = await act(() => onCreate(device, body))
    if (r?.psk) {
      setRevealed({ username: body.username, psk: r.psk })
      setForm(EMPTY)
    }
  }

  const rotate = async (u) => {
    const r = await act(() => onRotate(device, u.id))
    if (r?.psk) setRevealed({ username: u.username, psk: r.psk })
  }

  const revoke = (u) => act(() => onRevoke(device, u.id))

  const disabled = busy || working

  return (
    <div className="space-y-4">
      <MriSectionHeader
        icon={KeyRound}
        title="PPSK users"
        description="Per-user Private PSKs HiveKeeper mints and owns on-prem (the agent's RADIUS store). The key is shown once."
      />

      {revealed && (
        <div className="space-y-1 rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3" role="status">
          <div className="text-xs font-medium text-foreground">
            New PSK for <span className="font-mono">{revealed.username}</span> — copy it now, it is shown only once:
          </div>
          <div className="flex items-center gap-2">
            <code className="select-all rounded bg-background px-2 py-1 text-sm">{revealed.psk}</code>
            <MriButton size="sm" variant="secondary" onClick={() => setRevealed(null)}>
              Dismiss
            </MriButton>
          </div>
        </div>
      )}

      <div className="space-y-2 rounded-md border border-border p-3">
        <span className="text-xs font-medium text-muted-foreground">Mint a PPSK user</span>
        <div className="grid gap-2 sm:grid-cols-3">
          <Field label="Security object (SSID)" value={form.securityObject} onChange={set('securityObject')} placeholder="Corp" />
          <Field label="Username" value={form.username} onChange={set('username')} placeholder="alice" />
          <Field label="User-group" value={form.userGroup} onChange={set('userGroup')} placeholder="staff" />
          <Field label="VLAN id" value={form.vlanId} onChange={set('vlanId')} placeholder="optional" type="number" />
          <Field label="User-profile attr" value={form.userProfileAttr} onChange={set('userProfileAttr')} placeholder="optional" type="number" />
        </div>
        <MriButton size="sm" disabled={disabled || !form.securityObject.trim() || !form.username.trim()} onClick={create}>
          Generate PSK
        </MriButton>
      </div>

      {error && <p className="text-xs text-destructive">{error}</p>}

      {users === 'forbidden' && (
        <p className="text-sm text-muted-foreground">Managing PPSK users needs an operator role on this site.</p>
      )}
      {users === null && <p className="text-sm text-muted-foreground">Gateway or agent unreachable.</p>}
      {Array.isArray(users) && users.length === 0 && (
        <p className="text-sm text-muted-foreground">No PPSK users yet.</p>
      )}
      {Array.isArray(users) && users.length > 0 && (
        <MriTable>
          <MriTableHeader>
            <MriTableRow>
              <MriTableHead>User</MriTableHead>
              <MriTableHead>Security object</MriTableHead>
              <MriTableHead>Group</MriTableHead>
              <MriTableHead>VLAN</MriTableHead>
              <MriTableHead>Status</MriTableHead>
              <MriTableHead> </MriTableHead>
            </MriTableRow>
          </MriTableHeader>
          <MriTableBody>
            {users.map((u) => (
              <MriTableRow key={u.id}>
                <MriTableCell className="font-mono">{u.username}</MriTableCell>
                <MriTableCell>{u.securityObject}</MriTableCell>
                <MriTableCell>{u.userGroup || '—'}</MriTableCell>
                <MriTableCell>{u.vlanId ?? '—'}</MriTableCell>
                <MriTableCell>
                  <MriStatusBadge variant={u.status === 'active' ? 'success' : 'muted'}>{u.status}</MriStatusBadge>
                </MriTableCell>
                <MriTableCell>
                  {u.status === 'active' && (
                    <div className="flex justify-end gap-2">
                      <MriButton size="sm" variant="secondary" disabled={disabled} onClick={() => rotate(u)}>
                        Rotate
                      </MriButton>
                      <ConfirmButton size="sm" disabled={disabled} onConfirm={() => revoke(u)}>
                        Revoke
                      </ConfirmButton>
                    </div>
                  )}
                </MriTableCell>
              </MriTableRow>
            ))}
          </MriTableBody>
        </MriTable>
      )}
    </div>
  )
}
