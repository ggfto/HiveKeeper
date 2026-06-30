import { useState } from 'react'
import {
  MriSelect,
  MriButton,
  MriInput,
  MriTable,
  MriTableHeader,
  MriTableBody,
  MriTableRow,
  MriTableHead,
  MriTableCell,
  MriStatusBadge,
} from '@mriqbox/ui-kit'
import { bulkTargetOptions, parseBulkTarget, summarizeBulk, outcomeVariant } from '../../lib/fleet'
import { parseTemplateCommands, loadTemplates, saveTemplate, deleteTemplate } from '../../lib/configTemplate'

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'

/**
 * Apply a config template — a set of HiveOS CLI lines — across a scope (org / site / group) via the gateway's
 * bulk apply-config. Each device is reached through its own agent + credential and re-authorized server-side, so
 * the per-device outcomes can include ok / failed / agent_offline / skipped / forbidden / timeout. Because this
 * writes to many APs at once it is confirm-gated. Named templates are saved locally (localStorage) for reuse.
 */
export function ConfigTemplatePanel({ sites = [], groups = [], onApply, result, busy }) {
  const [target, setTarget] = useState('org')
  const [text, setText] = useState('')
  const [save, setSave] = useState(true)
  const [name, setName] = useState('')
  const [templates, setTemplates] = useState(() => loadTemplates(window.localStorage))
  const [selected, setSelected] = useState('')

  const commands = parseTemplateCommands(text)

  const apply = () => {
    if (commands.length === 0) return
    const scope = parseBulkTarget(target)
    const where = target === 'org' ? 'every device in the organization' : 'every device in the selected scope'
    if (!window.confirm(`Apply ${commands.length} CLI line(s) to ${where}? This writes to every reachable AP at once${save ? ' and saves config' : ''}.`)) return
    onApply?.(scope, commands, save)
  }

  const onSave = () => {
    const list = saveTemplate(window.localStorage, name, text)
    setTemplates(list)
    setName('')
  }
  const onLoad = (value) => {
    setSelected(value)
    const t = templates.find((x) => x.name === value)
    if (t) setText(t.text)
  }
  const onDelete = () => {
    if (!selected) return
    setTemplates(deleteTemplate(window.localStorage, selected))
    setSelected('')
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex w-full flex-col gap-1 sm:w-64">
          <span className="text-xs text-muted-foreground">Target</span>
          <MriSelect options={bulkTargetOptions(sites, groups)} value={target} onChange={setTarget} />
        </label>
        {templates.length > 0 && (
          <label className="flex w-full flex-col gap-1 sm:w-64" htmlFor="tpl-load">
            <span className="text-xs text-muted-foreground">Saved templates</span>
            <select id="tpl-load" className={SELECT_CLASS} value={selected} onChange={(e) => onLoad(e.target.value)}>
              <option value="">(load a saved template…)</option>
              {templates.map((t) => (
                <option key={t.name} value={t.name}>
                  {t.name}
                </option>
              ))}
            </select>
          </label>
        )}
        {selected && (
          <MriButton size="sm" variant="ghost" disabled={busy} onClick={onDelete}>
            Delete template
          </MriButton>
        )}
      </div>

      <label className="flex flex-col gap-1" htmlFor="tpl-cli">
        <span className="text-xs text-muted-foreground">CLI lines (one per line; blank lines and # comments are ignored)</span>
        <textarea
          id="tpl-cli"
          className="min-h-[8rem] rounded-md border border-border bg-background p-2 font-mono text-sm text-foreground"
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={'hostname AP-LOBBY\nled off\nssid HK schedule work-hours'}
        />
      </label>

      <div className="flex flex-wrap items-end gap-2">
        <label className="flex items-center gap-2 text-xs text-muted-foreground">
          <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
          Save config (persist to flash with <code className="font-mono">save config</code>)
        </label>
        <label className="flex flex-col gap-1" htmlFor="tpl-name">
          <span className="text-xs text-muted-foreground">Template name</span>
          <MriInput id="tpl-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Guest baseline" />
        </label>
        <MriButton size="sm" variant="outline" disabled={busy || !name.trim() || commands.length === 0} onClick={onSave}>
          Save template
        </MriButton>
        <MriButton variant="destructive" disabled={busy || commands.length === 0} onClick={apply}>
          Apply to devices ({commands.length} line{commands.length === 1 ? '' : 's'})
        </MriButton>
      </div>

      {result && (
        <div className="space-y-2" data-testid="bulk-config-results">
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
