import { useState } from 'react'
import {
  MriSelect,
  MriButton,
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { bulkTargetOptions, parseBulkTarget, summarizeBulk, outcomeVariant } from '../../lib/fleet'

/**
 * Run a read op (backup|inventory) across every registered device in a scope (org / site / group). Each device
 * is reached through its own agent and credential; the gateway re-authorizes each one, so the per-device
 * outcomes can include ok / failed / agent_offline / skipped / forbidden / timeout.
 */
export function BulkOpsPanel({ sites = [], groups = [], onRun, result, busy }) {
  const [target, setTarget] = useState('org')
  const run = (op) => onRun?.(op, parseBulkTarget(target))

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex w-full flex-col gap-1 sm:w-64">
          <span className="text-xs text-muted-foreground">Target</span>
          <MriSelect options={bulkTargetOptions(sites, groups)} value={target} onChange={setTarget} />
        </label>
        <MriButton variant="outline" disabled={busy} onClick={() => run('backup')}>
          Backup all
        </MriButton>
        <MriButton disabled={busy} onClick={() => run('inventory')}>
          Inventory all
        </MriButton>
      </div>

      {result && (
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">{summarizeBulk(result)}</p>
          <MriTable>
            <MriTableHeader>
              <MriTableRow>
                <MriTableHead>Host</MriTableHead>
                <MriTableHead>Serial</MriTableHead>
                <MriTableHead>Status</MriTableHead>
                <MriTableHead>Detail</MriTableHead>
              </MriTableRow>
            </MriTableHeader>
            <MriTableBody>
              {result.results?.map((r, i) => (
                <MriTableRow key={`${r.deviceId || r.host || i}`}>
                  <MriTableCell className="font-mono text-xs">{r.host || '—'}</MriTableCell>
                  <MriTableCell className="font-mono text-xs">{r.serial || '—'}</MriTableCell>
                  <MriTableCell>
                    <MriStatusBadge label={r.status} variant={outcomeVariant(r.status)} size="xs" />
                  </MriTableCell>
                  <MriTableCell className="text-muted-foreground">{r.detail || ''}</MriTableCell>
                </MriTableRow>
              ))}
            </MriTableBody>
          </MriTable>
        </div>
      )}
    </div>
  )
}
