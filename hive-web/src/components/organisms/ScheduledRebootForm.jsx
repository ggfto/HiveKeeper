import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriStatusBadge, MriSectionHeader } from '@mriqbox/ui-kit'
import { CalendarClock } from 'lucide-react'
import { rebootScheduleCommands, cancelRebootScheduleCommands } from '../../lib/hiveosCli'

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'
const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

function Field({ id, label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1" htmlFor={id}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput id={id} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
    </label>
  )
}

function Select({ id, label, value, onChange, options }) {
  return (
    <label className="flex flex-col gap-1" htmlFor={id}>
      <span className="text-xs text-muted-foreground">{label}</span>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

/**
 * Schedule (or cancel) a recurring AP reboot. Only the recurring `reboot schedule daily|weekly` forms are
 * offered — confirmed live to be non-interactive, so the apply-config channel can drive them (the one-shot
 * `reboot date` / `reboot offset` forms prompt Y/N and would hang it). The schedule is not part of the
 * running-config, so it is applied with `save: false`. The current schedule is read from `show reboot schedule`.
 * A reboot drops the AP's clients for ~1-2 minutes at the chosen time, so both scheduling and cancelling confirm.
 */
export function ScheduledRebootForm({ device, loadRebootSchedule, onApply, busy }) {
  const [state, setState] = useState(undefined) // undefined = loading, null = none, object = scheduled
  const [form, setForm] = useState({ type: 'daily', interval: '1', weekday: 'Monday', time: '' })
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const refresh = useCallback(() => {
    setState(undefined)
    return loadRebootSchedule(device)
      .then((r) => setState(r || null))
      .catch(() => setState(null))
  }, [loadRebootSchedule, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  const dispatch = (body) => Promise.resolve(onApply(device, body)).then(refresh)

  const schedule = () => {
    const commands = rebootScheduleCommands({
      type: form.type,
      interval: Number(form.interval),
      weekday: form.weekday,
      time: form.time,
    })
    if (commands.length === 0) return
    const when = form.type === 'weekly' ? `every ${form.interval} week(s) on ${form.weekday}` : `every ${form.interval} day(s)`
    if (!window.confirm(`Schedule a reboot ${when} at ${form.time}? The AP will drop its clients for ~1-2 minutes each time.`)) return
    dispatch({ commands, save: false })
  }

  const cancel = () => {
    if (!window.confirm('Cancel the scheduled reboot?')) return
    dispatch({ commands: cancelRebootScheduleCommands(), save: false })
  }

  return (
    <div className="space-y-3">
      <MriSectionHeader icon={CalendarClock} title="Scheduled reboot" />
      <p className="text-xs text-muted-foreground">
        A recurring reboot can clear slow memory leaks on long-running APs. Only daily/weekly schedules are
        supported here (one-shot reboots need an interactive confirmation the agent can&apos;t answer).
      </p>

      {state === undefined ? (
        <p className="text-sm text-muted-foreground">Loading the reboot schedule…</p>
      ) : state ? (
        <div className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2">
          <span className="text-sm">Next reboot</span>
          <MriStatusBadge label={state.scheduledAt} variant="outline" size="xs" />
          {state.weekday && <MriStatusBadge label={state.weekday} variant="outline" size="xs" />}
          <MriButton size="sm" variant="destructive" className="ml-auto" disabled={busy} onClick={cancel}>
            Cancel scheduled reboot
          </MriButton>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">No reboot scheduled.</p>
      )}

      <div className="space-y-2 rounded-md border border-border p-3">
        <span className="text-xs font-medium text-muted-foreground">Set a recurring reboot</span>
        <div className="grid gap-2 sm:grid-cols-3">
          <Select
            id="reboot-freq"
            label="Frequency"
            value={form.type}
            onChange={set('type')}
            options={[
              { value: 'daily', label: 'Daily' },
              { value: 'weekly', label: 'Weekly' },
            ]}
          />
          <Field
            id="reboot-interval"
            label={form.type === 'weekly' ? 'Every N weeks' : 'Every N days'}
            value={form.interval}
            onChange={set('interval')}
            placeholder="1"
          />
          {form.type === 'weekly' && (
            <Select
              id="reboot-weekday"
              label="Weekday"
              value={form.weekday}
              onChange={set('weekday')}
              options={WEEKDAYS.map((d) => ({ value: d, label: d }))}
            />
          )}
          <Field id="reboot-time" label="Time" value={form.time} onChange={set('time')} placeholder="04:30" />
        </div>
        <MriButton size="sm" variant="outline" disabled={busy || !form.interval || !form.time} onClick={schedule}>
          Schedule reboot
        </MriButton>
      </div>
    </div>
  )
}
