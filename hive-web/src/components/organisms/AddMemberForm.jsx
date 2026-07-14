import { useState } from 'react'
import { MriButton, MriInput, MriSelect, MriSectionHeader } from '@mriqbox/ui-kit'
import { UserPlus } from 'lucide-react'
import { ROLE_OPTIONS, suggestPassword } from '../../lib/members'

/**
 * Add a teammate to the active organization, in one of two ways.
 *
 * **Create a login** — a Keycloak user is created with the temporary password set here (they change it at
 * first sign-in).
 *
 * **Admit an existing account** — for anyone who signs in through an identity provider. A GitHub user has no
 * password, and no account at all until their first sign-in creates one, so they cannot be created in advance:
 * they sign in once (and are told they belong to no organization), and an admin then admits them by username
 * or e-mail. Sending no password is what tells the gateway to admit rather than create.
 *
 * `onAdd({ username, email, name, password, role })` resolves to true on success (the form then clears) or
 * false on failure (fields kept so the admin can retry). Creating an owner requires you to be an owner — the
 * gateway enforces it.
 */
export function AddMemberForm({ onAdd, busy }) {
  const [mode, setMode] = useState('create') // 'create' | 'existing'
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('viewer')
  const [working, setWorking] = useState(false)

  const creating = mode === 'create'
  const canSubmit = username.trim() && (!creating || password.trim()) && !busy && !working

  const submit = async () => {
    if (!canSubmit) return
    setWorking(true)
    try {
      const ok = await onAdd?.({
        username: username.trim(),
        email: creating ? email.trim() || null : null,
        name: creating ? name.trim() || null : null,
        // No password is the signal to admit an existing account instead of minting a new login.
        password: creating ? password : null,
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

      <fieldset className="flex flex-wrap gap-4 text-xs">
        <label className="flex items-center gap-1.5">
          <input
            type="radio"
            name="add-member-mode"
            checked={creating}
            onChange={() => setMode('create')}
            aria-label="Create a login"
          />
          <span>Create a login</span>
        </label>
        <label className="flex items-center gap-1.5">
          <input
            type="radio"
            name="add-member-mode"
            checked={!creating}
            onChange={() => setMode('existing')}
            aria-label="Existing account (GitHub or other sign-in)"
          />
          <span>Existing account (GitHub or other sign-in)</span>
        </label>
      </fieldset>

      <p className="text-xs text-muted-foreground">
        {creating
          ? 'Creates a sign-in for the person and adds them to this organization. The password is temporary — they choose their own the first time they sign in.'
          : 'Adds someone who already has an account — anyone signing in with GitHub, for example. They must sign in once before you can add them: their account does not exist until they do.'}
      </p>

      <div className="grid items-end gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-muted-foreground">{creating ? 'Username' : 'Username or email'}</span>
          <MriInput
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            placeholder={creating ? 'bob' : 'octocat or bob@acme.com'}
          />
        </label>

        {creating && (
          <>
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
                <MriInput
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="temp pass"
                />
                <MriButton size="sm" variant="outline" type="button" onClick={() => setPassword(suggestPassword())}>
                  Generate
                </MriButton>
              </div>
            </label>
          </>
        )}

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
