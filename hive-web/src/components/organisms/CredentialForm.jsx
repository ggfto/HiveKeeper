import { useState } from 'react'
import { MriInput, MriButton, MriSectionHeader } from '@mriqbox/ui-kit'
import { KeyRound, TriangleAlert } from 'lucide-react'

function Field({ label, value, onChange, placeholder, type }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput type={type} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

/**
 * Sets (or rotates) the SSH credential HiveKeeper uses to reach this AP. The secret is sealed to the agent's
 * public key at the gateway and stored in the agent's local vault — it never persists in the cloud. The
 * optional "also change on the AP" path additionally rotates the admin password on the device itself; it is
 * gated behind {@code allowOnDevice} until the HiveOS grammar is confirmed live, and warns that a wrong value
 * can lock the AP out (recoverable by a reset).
 */
export function CredentialForm({ device, onSetCredential, busy, allowOnDevice = false }) {
  const [v, setV] = useState({ username: 'admin', password: '', alsoSetOnDevice: false })
  const set = (k) => (val) => setV((prev) => ({ ...prev, [k]: val }))

  const submit = () => {
    if (!v.username.trim() || !v.password) return
    if (v.alsoSetOnDevice) {
      const ok = window.confirm(
        `Also change the admin password ON ${device.mgmtIp || 'this AP'}? If the new password is wrong, or the ` +
          `change fails midway, you can be locked out of the AP — recoverable only by resetting it.`,
      )
      if (!ok) return
    }
    onSetCredential(device, {
      username: v.username.trim(),
      password: v.password,
      alsoSetOnDevice: v.alsoSetOnDevice,
    })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={KeyRound} title="Credentials" />
      <p className="text-xs text-muted-foreground">
        The SSH username and password HiveKeeper uses to reach this AP. The secret is sealed to the agent and
        stored in its local vault — it is never persisted in the cloud.
      </p>
      <div className="grid gap-2 sm:grid-cols-2">
        <Field label="SSH username" value={v.username} onChange={set('username')} placeholder="admin" />
        <Field
          label="SSH password"
          type="password"
          value={v.password}
          onChange={set('password')}
          placeholder="••••••••"
        />
      </div>
      <label className="flex items-start gap-2 text-xs">
        <input
          type="checkbox"
          className="mt-0.5"
          checked={v.alsoSetOnDevice}
          disabled={!allowOnDevice}
          onChange={(e) => set('alsoSetOnDevice')(e.target.checked)}
        />
        <span className={allowOnDevice ? '' : 'text-muted-foreground'}>
          Also change the admin password on the AP itself
          {!allowOnDevice && ' — pending live CLI confirmation; not available yet'}
        </span>
      </label>
      {v.alsoSetOnDevice && (
        <div className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-2 text-xs text-destructive">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            A wrong value can lock you out of the AP — recoverable only by resetting it. HiveOS requires 8–32
            characters with at least one number and one uppercase letter, and not equal to the username.
          </span>
        </div>
      )}
      <MriButton size="sm" disabled={busy} onClick={submit}>
        Save credential
      </MriButton>
    </div>
  )
}
