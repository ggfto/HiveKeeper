import { MriButton } from '@mriqbox/ui-kit'
import { ShieldQuestion } from 'lucide-react'

/**
 * Shown to somebody who has signed in successfully and belongs to no organization.
 *
 * This is not an error, and it is not a dead end that needs explaining away — it is the expected first step of
 * federated sign-in. Anyone who signs in with GitHub has no account here until that first sign-in creates one,
 * so an admin cannot add them in advance: they sign in, land here, and the admin admits them. Without this
 * screen they would reach a console with an empty organization switcher where every request fails, and would
 * reasonably conclude that HiveKeeper is broken.
 *
 * The one thing they need is the identity to give the admin, so it is the thing this screen shows.
 */
export function NoOrganizationGate({ me, onSignOut }) {
  const identity = me?.email || me?.name

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-md space-y-4 rounded-lg border border-border p-6 text-center">
        <ShieldQuestion className="mx-auto h-8 w-8 text-muted-foreground" aria-hidden="true" />

        <h1 className="text-lg font-semibold">You are signed in, but not a member of any organization</h1>

        <p className="text-sm text-muted-foreground">
          Ask an administrator to add you. They will need the account you signed in with:
        </p>

        {identity && (
          <p className="rounded-md bg-muted px-3 py-2 font-mono text-sm" data-testid="identity">
            {identity}
          </p>
        )}

        <p className="text-xs text-muted-foreground">
          In the console they add you under <strong>Members → Add member → Existing account</strong>. Once they
          have, sign in again.
        </p>

        <MriButton size="sm" variant="outline" onClick={onSignOut}>
          Sign out
        </MriButton>
      </div>
    </div>
  )
}
