import { describe, it, expect } from 'vitest'
import { evaluateAlerts, worstSeverity, severityRank, loadThresholds, saveThresholds, DEFAULT_THRESHOLDS } from './alerts'

function fakeStorage(initial = {}) {
  const map = { ...initial }
  return { getItem: (k) => (k in map ? map[k] : null), setItem: (k, v) => (map[k] = String(v)), _map: map }
}

describe('evaluateAlerts', () => {
  it('reports only an offline alert when the agent is offline', () => {
    expect(evaluateAlerts({ online: false, snapshot: { stations: [1, 2, 3] } })).toEqual([
      { id: 'agent-offline', severity: 'critical', message: 'Agent offline — the AP is unreachable.' },
    ])
  })

  it('warns when the AP is still cloud-managed (CAPWAP up)', () => {
    const alerts = evaluateAlerts({ online: true, snapshot: { cloud: { managed: true } } })
    expect(alerts).toContainEqual(
      expect.objectContaining({ id: 'cloud-managed', severity: 'warning' }),
    )
  })

  it('warns when station count exceeds the threshold', () => {
    const stations = Array.from({ length: 31 }, (_, i) => ({ mac: String(i) }))
    const alerts = evaluateAlerts({ online: true, snapshot: { stations } })
    expect(alerts).toContainEqual(expect.objectContaining({ id: 'high-clients', severity: 'warning' }))
    // and respects a custom threshold (5 here -> 31 > 5)
    expect(evaluateAlerts({ online: true, snapshot: { stations } }, { maxStations: 100 })).toEqual([])
  })

  it('raises a radio info alert when a radio trips a best-practice warning (high power / wide channel)', () => {
    const alerts = evaluateAlerts({
      online: true,
      snapshot: { radios: [{ name: 'Wifi1', channel: 36, width: 160, txPower: 20 }] },
    })
    expect(alerts).toContainEqual(expect.objectContaining({ id: 'radio-wifi1', severity: 'info' }))
  })

  it('is quiet for a healthy standalone AP', () => {
    expect(
      evaluateAlerts({ online: true, snapshot: { cloud: { managed: false }, stations: [{ mac: 'a' }], radios: [{ name: 'Wifi0', channel: 6, width: 20, txPower: 10 }] } }),
    ).toEqual([])
  })
})

describe('severity helpers', () => {
  it('ranks critical worse than warning worse than info', () => {
    expect(severityRank('critical')).toBeLessThan(severityRank('warning'))
    expect(severityRank('warning')).toBeLessThan(severityRank('info'))
  })
  it('picks the worst severity in a list (null when empty)', () => {
    expect(worstSeverity([{ severity: 'info' }, { severity: 'critical' }, { severity: 'warning' }])).toBe('critical')
    expect(worstSeverity([])).toBeNull()
  })
})

describe('threshold persistence', () => {
  it('returns defaults when nothing is stored or the value is garbage', () => {
    expect(loadThresholds(fakeStorage())).toEqual(DEFAULT_THRESHOLDS)
    expect(loadThresholds(fakeStorage({ 'hivekeeper.alertThresholds': 'nonsense' }))).toEqual(DEFAULT_THRESHOLDS)
  })
  it('saves and reads back thresholds merged over the defaults', () => {
    const s = fakeStorage()
    expect(saveThresholds(s, { maxStations: 50 })).toEqual({ maxStations: 50 })
    expect(loadThresholds(s)).toEqual({ maxStations: 50 })
  })
})
