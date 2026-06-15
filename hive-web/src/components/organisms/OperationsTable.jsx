import {
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'

function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString()
}

/** The per-tenant audit of operations dispatched to agents (GET /api/operations). RLS-scoped on the gateway. */
export function OperationsTable({ operations }) {
  if (!operations || operations.length === 0) {
    return <p className="text-sm text-muted-foreground">No operations recorded yet.</p>
  }
  return (
    <MriTable>
      <MriTableHeader>
        <MriTableRow>
          <MriTableHead>Time</MriTableHead>
          <MriTableHead>Agent</MriTableHead>
          <MriTableHead>Operation</MriTableHead>
          <MriTableHead>Host</MriTableHead>
          <MriTableHead>Result</MriTableHead>
        </MriTableRow>
      </MriTableHeader>
      <MriTableBody>
        {operations.map((op) => (
          <MriTableRow key={op.id}>
            <MriTableCell className="whitespace-nowrap text-xs text-muted-foreground">
              {formatTime(op.createdAt)}
            </MriTableCell>
            <MriTableCell className="font-mono text-xs">{op.agentId || '—'}</MriTableCell>
            <MriTableCell>
              <MriStatusBadge label={op.opType} variant="outline" size="xs" />
            </MriTableCell>
            <MriTableCell className="font-mono text-xs">{op.host || '—'}</MriTableCell>
            <MriTableCell className="text-muted-foreground">{op.summary || ''}</MriTableCell>
          </MriTableRow>
        ))}
      </MriTableBody>
    </MriTable>
  )
}
