import { useCallback, useEffect, useState } from 'react'
import { MriInput, MriButton, MriStatusBadge, MriSectionHeader } from '@mriqbox/ui-kit'
import { CalendarClock } from 'lucide-react'
import { scheduleCommands, removeScheduleCommands } from '../../lib/hiveosCli'

const SELECT_CLASS = 'h-9 rounded-md border border-border bg-background px-2 text-sm text-foreground'
const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

function Field({ label, value, onChange, placeholder }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <MriInput value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />
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

const ANY = { value: '', label: '(any)' }
const weekdayOptions = [ANY, ...WEEKDAYS.map((d) => ({ value: d, label: d }))]

/**
 * Create a named schedule object. A recurrent schedule gates by weekday and/or time of day (with an optional
 * active date window); a one-time schedule runs once between a start and an end date+time. The object is then
 * referenced by an SSID's `schedule` field (Wi-Fi → Hardening) or a user-profile's schedule (Policy) to limit
 * when each applies. Each setting maps straight to the live-confirmed grammar via scheduleCommands.
 */
function ScheduleForm({ onApply, busy }) {
  const empty = {
    type: 'recurrent',
    name: '',
    weekdayStart: '',
    weekdayEnd: '',
    timeStart: '',
    timeEnd: '',
    dateStart: '',
    dateEnd: '',
  }
  const [form, setForm] = useState(empty)
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }))

  const apply = () => {
    const commands = scheduleCommands(form.name, form)
    if (commands.length === 0 || !onApply) return
    onApply({ commands, save: true })
    setForm(empty)
  }

  const ready =
    form.name.trim() &&
    (form.type === 'once'
      ? form.dateStart && form.timeStart && form.dateEnd && form.timeEnd
      : form.weekdayStart || (form.timeStart && form.timeEnd) || form.dateStart)

  return (
    <div className="space-y-2 rounded-md border border-border p-3">
      <span className="text-xs font-medium text-muted-foreground">Add schedule</span>
      <p className="text-xs text-muted-foreground">
        A schedule limits <em>when</em> an SSID or user profile is active. A <strong>recurrent</strong> schedule
        repeats by weekday and/or time of day; a <strong>one-time</strong> schedule runs once between a start and
        end. Reference it from the Wi-Fi (Hardening) or Policy sections.
      </p>
      <div className="grid gap-2 sm:grid-cols-3">
        <Select
          id="sched-type"
          label="Type"
          value={form.type}
          onChange={set('type')}
          options={[
            { value: 'recurrent', label: 'Recurrent' },
            { value: 'once', label: 'One-time' },
          ]}
        />
        <Field label="Name" value={form.name} onChange={set('name')} placeholder="work-hours" />
        {form.type === 'recurrent' ? (
          <>
            <Select id="sched-wd-from" label="From weekday" value={form.weekdayStart} onChange={set('weekdayStart')} options={weekdayOptions} />
            <Select id="sched-wd-to" label="To weekday" value={form.weekdayEnd} onChange={set('weekdayEnd')} options={weekdayOptions} />
            <Field label="Start time" value={form.timeStart} onChange={set('timeStart')} placeholder="08:00" />
            <Field label="End time" value={form.timeEnd} onChange={set('timeEnd')} placeholder="17:00" />
            <Field label="Active from (optional)" value={form.dateStart} onChange={set('dateStart')} placeholder="2026-01-01" />
            <Field label="Active until (optional)" value={form.dateEnd} onChange={set('dateEnd')} placeholder="2026-12-31" />
          </>
        ) : (
          <>
            <Field label="Start date" value={form.dateStart} onChange={set('dateStart')} placeholder="2026-12-25" />
            <Field label="Start time" value={form.timeStart} onChange={set('timeStart')} placeholder="08:00" />
            <Field label="End date" value={form.dateEnd} onChange={set('dateEnd')} placeholder="2026-12-26" />
            <Field label="End time" value={form.timeEnd} onChange={set('timeEnd')} placeholder="17:00" />
          </>
        )}
      </div>
      <MriButton size="sm" disabled={busy || !ready} onClick={apply}>
        Create schedule
      </MriButton>
    </div>
  )
}

/**
 * Named schedule objects on a device, read from the AP's running-config, plus a form to create one. A schedule
 * is the time window an SSID or user profile is active in — created here once, then referenced by name elsewhere.
 * Writes go through apply-config; the list re-reads after each change.
 */
export function ScheduleSection({ device, loadSchedules, onApply, busy }) {
  const [state, setState] = useState(null)

  const refresh = useCallback(() => {
    setState(null)
    return loadSchedules(device)
      .then((r) => setState(r || []))
      .catch(() => setState([]))
  }, [loadSchedules, device])

  useEffect(() => {
    refresh()
  }, [refresh])

  const dispatch = (body) => Promise.resolve(onApply(device, body)).then(refresh)
  const remove = (name) => {
    if (!window.confirm(`Remove schedule "${name}"? SSIDs or profiles referencing it lose their time window.`)) return
    dispatch({ commands: removeScheduleCommands(name), save: true })
  }

  const schedules = state || []

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <MriSectionHeader icon={CalendarClock} title="Schedules" />
        <MriButton size="sm" variant="ghost" disabled={busy} onClick={refresh}>
          Refresh
        </MriButton>
      </div>

      {state === null ? (
        <p className="text-sm text-muted-foreground">Loading schedules from the AP…</p>
      ) : schedules.length === 0 ? (
        <p className="text-sm text-muted-foreground">No schedules configured.</p>
      ) : (
        <ul className="space-y-2" data-testid="schedule-list">
          {schedules.map((s) => (
            <li key={s.name} className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2">
              <span className="font-medium">{s.name}</span>
              <MriStatusBadge label={s.type} variant="outline" size="xs" />
              {s.detail && <span className="font-mono text-xs text-muted-foreground">{s.detail}</span>}
              <MriButton size="sm" variant="destructive" className="ml-auto" disabled={busy} onClick={() => remove(s.name)}>
                Remove
              </MriButton>
            </li>
          ))}
        </ul>
      )}

      {state !== null && <ScheduleForm busy={busy} onApply={dispatch} />}
    </div>
  )
}
