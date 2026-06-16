import {
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriSelect,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { ConfirmButton } from '../molecules/ConfirmButton'
import { ROLE_OPTIONS } from '../../lib/members'

const STATUS_VARIANT = { active: 'success', invited: 'warning', suspended: 'destructive' }

/**
 * The organization roster: each member's identity, status, and org role. The role is a live select — changing
 * it re-grants on the gateway, which owner-gates owner changes and refuses demoting the last owner — and remove
 * is a two-click confirm. Listing members needs an org admin, so a non-admin gets a forbidden note. `me` marks
 * the signed-in person as "(you)". `members` is an array, or null (gateway down), or the string 'forbidden'.
 */
export function MembersList({ members, me, onChangeRole, onRemove, busy }) {
  if (members === 'forbidden') {
    return (
      <p className="text-sm text-muted-foreground">
        Managing members needs an organization admin or owner role.
      </p>
    )
  }
  if (members == null) {
    return <p className="text-sm text-muted-foreground">Gateway unreachable.</p>
  }
  if (members.length === 0) {
    return <p className="text-sm text-muted-foreground">No members yet.</p>
  }
  return (
    <MriTable>
      <MriTableHeader>
        <MriTableRow>
          <MriTableHead>Member</MriTableHead>
          <MriTableHead>Status</MriTableHead>
          <MriTableHead>Role</MriTableHead>
          <MriTableHead className="text-right">Actions</MriTableHead>
        </MriTableRow>
      </MriTableHeader>
      <MriTableBody>
        {members.map((m) => {
          const self = me?.userId && m.userId === me.userId
          return (
            <MriTableRow key={m.userId}>
              <MriTableCell>
                <div className="font-medium">
                  {m.name || m.email || m.userId}
                  {self && <span className="ml-2 text-xs text-muted-foreground">(you)</span>}
                </div>
                {m.email && <div className="text-xs text-muted-foreground">{m.email}</div>}
              </MriTableCell>
              <MriTableCell>
                <MriStatusBadge label={m.status} variant={STATUS_VARIANT[m.status] || 'outline'} size="xs" />
              </MriTableCell>
              <MriTableCell>
                <div className="w-32">
                  <MriSelect
                    options={ROLE_OPTIONS}
                    value={m.role || ''}
                    onChange={(v) => onChangeRole?.(m, v)}
                    size="sm"
                    aria-label={`Role for ${m.name || m.email || m.userId}`}
                  />
                </div>
              </MriTableCell>
              <MriTableCell className="text-right">
                <ConfirmButton confirmLabel="Remove?" disabled={busy} onConfirm={() => onRemove?.(m)}>
                  Remove
                </ConfirmButton>
              </MriTableCell>
            </MriTableRow>
          )
        })}
      </MriTableBody>
    </MriTable>
  )
}
