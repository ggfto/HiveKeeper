import { MriButton } from '@mriqbox/ui-kit'
import { accountLabel } from '../../lib/authState'

/** The topbar account control: sign in when signed out, or the account label + sign out when signed in. */
export function AccountMenu({ user, me, onSignIn, onSignOut }) {
  if (!user) {
    return (
      <MriButton size="sm" onClick={onSignIn}>
        Sign in
      </MriButton>
    )
  }
  const email = me?.email || user.profile?.email
  return (
    <div className="flex items-center gap-2">
      <div className="text-right leading-tight">
        <div className="text-sm font-medium">{accountLabel(user, me)}</div>
        {email && <div className="text-xs text-muted-foreground">{email}</div>}
      </div>
      <MriButton size="sm" variant="ghost" onClick={onSignOut}>
        Sign out
      </MriButton>
    </div>
  )
}
