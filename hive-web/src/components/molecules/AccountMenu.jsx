import { MriButton } from '@mriqbox/ui-kit'
import { accountLabel } from '../../lib/authState'

/** The sidebar-footer account control: sign in when signed out, or the account label + sign out when signed in. */
export function AccountMenu({ user, me, onSignIn, onSignOut }) {
  if (!user) {
    return (
      <MriButton size="sm" className="w-full" onClick={onSignIn}>
        Sign in
      </MriButton>
    )
  }
  const email = me?.email || user.profile?.email
  return (
    <div className="flex items-center justify-between gap-2">
      <div className="min-w-0 leading-tight">
        <div className="truncate text-sm font-medium">{accountLabel(user, me)}</div>
        {email && <div className="truncate text-xs text-muted-foreground">{email}</div>}
      </div>
      <MriButton size="sm" variant="ghost" onClick={onSignOut}>
        Sign out
      </MriButton>
    </div>
  )
}
