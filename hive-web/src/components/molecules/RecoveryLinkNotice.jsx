import { useState } from 'react'
import { MriButton } from '@mriqbox/ui-kit'
import { KeyRound, Copy, Check } from 'lucide-react'

/**
 * The one-time link a newly added teammate must follow to choose their own password.
 *
 * Only some identity providers work this way. Keycloak forces the change at first sign-in, so there is nothing
 * to show and the gateway sends no link; Authentik has no such flag, so the gateway creates the account with no
 * usable password and mints a recovery link instead — which is the only way in. The gateway cannot re-issue it,
 * hence the persistent panel rather than a toast: an admin who misses it has to delete the account and re-add.
 */
export function RecoveryLinkNotice({ link, username, onDismiss }) {
  const [copied, setCopied] = useState(false)

  if (!link) return null

  const copy = async () => {
    try {
      await navigator.clipboard?.writeText(link)
      setCopied(true)
    } catch {
      // Clipboard access can be refused (insecure context, permission); the link is on screen to copy by hand.
      setCopied(false)
    }
  }

  return (
    <div className="rounded-md border border-primary/40 bg-primary/5 p-4 space-y-2">
      <div className="flex items-center gap-2 font-medium">
        <KeyRound className="size-4" aria-hidden="true" />
        <span>Send this link to {username}</span>
      </div>
      <p className="text-sm text-muted-foreground">
        They set their own password with it — you never see it. It is only shown once; if you lose it, remove
        the member and add them again.
      </p>
      {/* Full, unwrapped and selectable: the copy button is a convenience, not the only way to get the link. */}
      <code className="block break-all rounded bg-background p-2 text-xs">{link}</code>
      <div className="flex gap-2">
        <MriButton size="sm" onClick={copy}>
          {copied ? <Check className="size-4" aria-hidden="true" /> : <Copy className="size-4" aria-hidden="true" />}
          {copied ? 'Copied' : 'Copy link'}
        </MriButton>
        <MriButton size="sm" variant="ghost" onClick={onDismiss}>
          Dismiss
        </MriButton>
      </div>
    </div>
  )
}
