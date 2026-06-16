import { useState } from 'react'
import { MriButton } from '@mriqbox/ui-kit'

/**
 * A destructive action guarded by a second click: the first click arms it (revealing a confirm + cancel), the
 * second runs onConfirm. A modal-free guard against accidental deletes — self-contained and easy to test.
 */
export function ConfirmButton({ children = 'Delete', confirmLabel = 'Confirm', onConfirm, disabled, size = 'sm' }) {
  const [armed, setArmed] = useState(false)

  if (armed) {
    return (
      <span className="inline-flex items-center gap-1">
        <MriButton
          size={size}
          variant="destructive"
          disabled={disabled}
          onClick={() => {
            setArmed(false)
            onConfirm?.()
          }}
        >
          {confirmLabel}
        </MriButton>
        <MriButton size={size} variant="ghost" disabled={disabled} onClick={() => setArmed(false)}>
          Cancel
        </MriButton>
      </span>
    )
  }
  return (
    <MriButton
      size={size}
      variant="ghost"
      className="text-destructive"
      disabled={disabled}
      onClick={() => setArmed(true)}
    >
      {children}
    </MriButton>
  )
}
