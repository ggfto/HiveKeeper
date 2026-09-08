import { useCallback, useEffect, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { Users } from 'lucide-react'
import { useAuth } from '../context/AuthProvider'
import { useToast } from '../context/ToastProvider'
import { MembersList } from '../components/organisms/MembersList'
import { AddMemberForm } from '../components/organisms/AddMemberForm'
import { RecoveryLinkNotice } from '../components/molecules/RecoveryLinkNotice'

/**
 * The active organization's people: who belongs, their role, and adding / re-roling / removing them. Managing
 * members needs an org admin (a non-admin sees a forbidden note); the gateway owner-gates owner changes and
 * refuses removing or demoting the last owner. Re-scopes whenever the active org switches.
 */
export function MembersPage() {
  const { gateway, activeOrg, me } = useAuth()
  const { toast } = useToast()
  const [members, setMembers] = useState(null)
  const [busy, setBusy] = useState(false)
  // Set only under an IdP that hands back a one-time link instead of forcing a password change (Authentik).
  const [recovery, setRecovery] = useState(null)

  const load = useCallback(async () => {
    const list = await gateway.members().catch((e) => (e.status === 403 ? 'forbidden' : null))
    setMembers(list)
  }, [gateway])

  useEffect(() => {
    load()
  }, [load, activeOrg])

  const label = (m) => m.name || m.email || m.userId

  const onAdd = async (body) => {
    setBusy(true)
    try {
      const added = await gateway.addMember(body)
      toast(`Added ${body.username}.`, 'success')
      // A toast would be gone before it could be copied, and the gateway cannot mint the link again.
      setRecovery(added?.recoveryLink ? { link: added.recoveryLink, username: body.username } : null)
      await load()
      return true
    } catch (e) {
      // the gateway's friendly text lives in detail (message is the short error code)
      toast(`Add member: ${e.body?.detail || e.message}`, 'error')
      return false
    } finally {
      setBusy(false)
    }
  }

  const onChangeRole = async (member, role) => {
    if (role === member.role) return
    setBusy(true)
    try {
      await gateway.setMemberRole(member.userId, role)
      toast(`${label(member)} is now ${role}.`, 'success')
      await load()
    } catch (e) {
      toast(`Change role: ${e.body?.detail || e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  const onRemove = async (member) => {
    setBusy(true)
    try {
      await gateway.removeMember(member.userId)
      toast(`Removed ${label(member)}.`, 'success')
      await load()
    } catch (e) {
      toast(`Remove member: ${e.body?.detail || e.message}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  const count = Array.isArray(members) ? members.length : undefined

  return (
    <div className="space-y-4">
      <MriPageHeader title="Members" icon={Users} count={count} countLabel="people" />
      <MembersList members={members} me={me} onChangeRole={onChangeRole} onRemove={onRemove} busy={busy} />
      <RecoveryLinkNotice
        link={recovery?.link}
        username={recovery?.username}
        onDismiss={() => setRecovery(null)}
      />
      <AddMemberForm onAdd={onAdd} busy={busy} />
    </div>
  )
}
