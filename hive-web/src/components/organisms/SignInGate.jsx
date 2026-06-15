import {
  MriButton,
  MriCard,
  MriCardHeader,
  MriCardTitle,
  MriCardDescription,
  MriCardContent,
} from '@mriqbox/ui-kit'

/**
 * The unauthenticated gate. The console makes no authorized requests until you sign in (so your scoped role
 * actually governs what you see), with one clearly-labelled dev escape hatch: the X-Tenant-Key owner key.
 */
export function SignInGate({ onSignIn, onDevMode }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
      <MriCard className="w-full max-w-sm">
        <MriCardHeader>
          <MriCardTitle className="flex items-center gap-2">
            <span aria-hidden>🐝</span> HiveKeeper
          </MriCardTitle>
          <MriCardDescription>
            Sign in to manage your fleet. Your role in the active organization governs every action.
          </MriCardDescription>
        </MriCardHeader>
        <MriCardContent className="space-y-3">
          <MriButton className="w-full" onClick={onSignIn}>
            Sign in
          </MriButton>
          <MriButton variant="ghost" className="w-full text-xs text-muted-foreground" onClick={onDevMode}>
            Continue with the dev owner key
          </MriButton>
        </MriCardContent>
      </MriCard>
    </div>
  )
}
