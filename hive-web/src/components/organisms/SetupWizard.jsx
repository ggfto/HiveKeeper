import { useState } from 'react'
import {
  MriButton,
  MriCard,
  MriCardHeader,
  MriCardTitle,
  MriCardDescription,
  MriCardContent,
  MriInput,
} from '@mriqbox/ui-kit'

function Field({ label, type = 'text', value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput type={type} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

function tokenFromUrl() {
  try {
    return new URLSearchParams(window.location.search).get('setup') || ''
  } catch {
    return ''
  }
}

/**
 * First-run wizard, shown when the gateway reports it is uninitialized. It creates the first organization and
 * the first admin (in Keycloak, via the gateway's setup API) using the one-time setup token printed on the
 * server console. After setup the admin signs in normally via OIDC.
 */
export function SetupWizard({ gateway, onSignIn, defaultToken }) {
  const [step, setStep] = useState('org')
  const [setupToken, setSetupToken] = useState(defaultToken ?? tokenFromUrl())
  const [orgName, setOrgName] = useState('')
  const [name, setName] = useState('')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const submit = async () => {
    if (password !== confirm) {
      setError('The passwords do not match.')
      return
    }
    setBusy(true)
    setError('')
    try {
      await gateway.setup({
        setupToken: setupToken.trim(),
        orgName: orgName.trim(),
        username: username.trim(),
        password,
        email: email.trim(),
        name: name.trim(),
      })
      setStep('done')
    } catch (e) {
      setError(e.message || 'Setup failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
      <MriCard className="w-full max-w-md">
        <MriCardHeader>
          <MriCardTitle className="flex items-center gap-2">
            <span aria-hidden>🐝</span> Welcome to HiveKeeper
          </MriCardTitle>
          <MriCardDescription>
            {step === 'done'
              ? 'Your organization is ready.'
              : 'First-run setup — create your organization and its first administrator.'}
          </MriCardDescription>
        </MriCardHeader>
        <MriCardContent className="space-y-3">
          {step === 'org' && (
            <>
              <Field
                label="Setup token (printed on the server console)"
                value={setupToken}
                onChange={setSetupToken}
                placeholder="paste the printed token"
              />
              <Field label="Organization name" value={orgName} onChange={setOrgName} placeholder="Acme Corp" />
              <MriButton
                className="w-full"
                disabled={!setupToken.trim() || !orgName.trim()}
                onClick={() => setStep('admin')}
              >
                Next
              </MriButton>
            </>
          )}

          {step === 'admin' && (
            <>
              <Field label="Full name" value={name} onChange={setName} placeholder="Olivia Owner" />
              <Field label="Username" value={username} onChange={setUsername} placeholder="admin" />
              <Field label="Email" type="email" value={email} onChange={setEmail} placeholder="admin@acme.test" />
              <Field label="Password" type="password" value={password} onChange={setPassword} placeholder="Password" />
              <Field
                label="Confirm password"
                type="password"
                value={confirm}
                onChange={setConfirm}
                placeholder="Confirm password"
              />
              <div className="flex gap-2">
                <MriButton variant="outline" disabled={busy} onClick={() => setStep('org')}>
                  Back
                </MriButton>
                <MriButton
                  className="flex-1"
                  disabled={busy || !username.trim() || !password || !confirm}
                  onClick={submit}
                >
                  {busy ? 'Creating…' : 'Create organization'}
                </MriButton>
              </div>
            </>
          )}

          {step === 'done' && (
            <>
              <p className="text-sm text-muted-foreground">
                All set. Sign in with your new administrator account{username ? ` (${username})` : ''}.
              </p>
              <MriButton className="w-full" onClick={onSignIn}>
                Sign in
              </MriButton>
            </>
          )}

          {error && <p className="text-sm text-destructive">{error}</p>}
        </MriCardContent>
      </MriCard>
    </div>
  )
}
