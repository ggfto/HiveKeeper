import { useState } from 'react'
import { MriTextarea, MriButton, MriSwitch, MriSectionHeader } from '@mriqbox/ui-kit'
import { Terminal } from 'lucide-react'

const CLI_ERROR = /invalid input|unknown keyword|error|incomplete|ambiguous/i

/**
 * The universal config escape hatch: raw HiveOS CLI lines applied to the device (one per line), optionally
 * persisted with `save config`. Covers anything a guided form does not — hostname, management IP, captive
 * portal, `no capwap client enable`, etc. The applied lines + their (secret-masked) output are shown back.
 */
export function AdvancedConfigForm({ device, onApply, result, busy }) {
  const [text, setText] = useState('')
  const [save, setSave] = useState(true)

  const apply = () => {
    const commands = text
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
    if (commands.length === 0) return
    onApply(device, { commands, save })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={Terminal} title="Advanced (HiveOS CLI)" />
      <p className="text-xs text-muted-foreground">
        One command per line — anything the AP&apos;s CLI accepts (hostname, radio, management IP, captive
        portal, <code className="font-mono">no capwap client enable</code>, …). Output is shown below.
      </p>
      <MriTextarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        rows={6}
        placeholder={'hostname lab-ap-01\ncountry-code US'}
        className="font-mono text-xs"
      />
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-2 text-sm">
          <MriSwitch checked={save} onCheckedChange={setSave} aria-label="Save config after applying" />
          Save config after applying
        </label>
        <MriButton size="sm" disabled={busy || !text.trim()} onClick={apply}>
          Apply
        </MriButton>
      </div>
      {result?.commands && (
        <div className="space-y-1">
          <h4 className="text-xs font-semibold text-muted-foreground">Applied {result.saved ? '(saved)' : ''}</h4>
          <ul className="space-y-0.5 font-mono text-xs">
            {result.commands.map((command, i) => {
              const out = (result.outputs?.[i] || '').replace(/\s+/g, ' ').trim()
              return (
                <li key={i} className={CLI_ERROR.test(out) ? 'text-destructive' : ''}>
                  {command}
                  {out ? ` -> ${out.slice(0, 140)}` : ''}
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}
