import { useState } from 'react'
import { MriButton, MriInput, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { UserPlus } from 'lucide-react'
import { ROLE_OPTIONS, suggestPassword } from '../../lib/members'

/**
 * Add a teammate to the active organization. A Keycloak login is created with the temporary password set here
 * (they must change it at first sign-in) and they join the org with the chosen role. `onAdd({ username, email,
 * name, password, role })` resolves to true on success (the form then clears) or false on failure (fields
 * kept so the admin can retry). Creating an owner requires you to be an owner — the gateway enforces it.
 */
export function AddMemberForm({ onAdd, busy }) {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('viewer')
  const [working, setWorking] = useState(false)

  const canSubmit = username.trim() && password.trim() && !busy && !working

  const submit = async () => {
    if (!canSubmit) return
    setWorking(true)
    try {
      const ok = await onAdd?.({
        username: username.trim(),
        email: email.trim() || null,
        name: name.trim() || null,
        password,
        role,
      })
      if (ok) {
        setUsername('')
        setEmail('')
        setName('')
        setPassword('')
        setRole('viewer')
      }
    } finally {
      setWorking(false)
    }
  }

  return (
    <section className="space-y-3 rounded-md border border-border p-3">
      <MriSectionHeader icon={UserPlus} title="Add member" />
      <p className="text-xs text-muted-foreground">
        Creates a sign-in for the person and adds them to this organization. The password is temporary — they
        choose their own the first time they sign in.
      </p>
      <div className="grid items-end gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Username</span>
          <MriInput value={username} onChange={(e) => setUsername(e.target.value)} placeholder="bob" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Email (optional)</span>
          <MriInput value={email} onChange={(e) => setEmail(e.target.value)} placeholder="bob@acme.com" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Display name (optional)</span>
          <MriInput value={name} onChange={(e) => setName(e.target.value)} placeholder="Bob Builder" />
        </label>
        <label className="flex flex-col gap-1 sm:col-span-2">
          <span className="text-xs text-muted-foreground">Temporary password</span>
          <div className="flex gap-1">
            <MriInput value={password} onChange={(e) => setPassword(e.target.value)} placeholder="temp pass" />
            <MriButton size="sm" variant="outline" type="button" onClick={() => setPassword(suggestPassword())}>
              Generate
            </MriButton>
          </div>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">Role</span>
          <MriSelect options={ROLE_OPTIONS} value={role} onChange={setRole} />
        </label>
        <MriButton size="sm" disabled={!canSubmit} onClick={submit}>
          {working ? 'Adding…' : 'Add member'}
        </MriButton>
      </div>
    </section>
  )
}
