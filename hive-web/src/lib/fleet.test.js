import { describe, it, expect } from 'vitest'
import {
  groupNamesFor,
  siteName,
  bulkTargetOptions,
  parseBulkTarget,
  summarizeBulk,
  outcomeVariant,
  lastSeenLabel,
} from './fleet'

describe('groupNamesFor', () => {
  const groups = [{ groupId: 'g1', name: 'Floor 3' }, { groupId: 'g2', name: 'Roof' }]
  it('maps a device group ids to names', () => {
    expect(groupNamesFor({ groups: ['g1', 'g2'] }, groups)).toEqual(['Floor 3', 'Roof'])
  })
  it('falls back to the id for an unknown group', () => {
    expect(groupNamesFor({ groups: ['g1', 'gX'] }, groups)).toEqual(['Floor 3', 'gX'])
  })
  it('returns an empty list for an untagged device', () => {
    expect(groupNamesFor({ groups: [] }, groups)).toEqual([])
    expect(groupNamesFor({}, groups)).toEqual([])
  })
})

describe('siteName', () => {
  const sites = [{ siteId: 's1', name: 'HQ' }]
  it('resolves a site name', () => expect(siteName('s1', sites)).toBe('HQ'))
  it('falls back to the id', () => expect(siteName('s9', sites)).toBe('s9'))
  it('returns null for no site', () => expect(siteName(null, sites)).toBeNull())
})

describe('bulkTargetOptions', () => {
  it('always offers the whole organization first', () => {
    expect(bulkTargetOptions()[0]).toEqual({ label: 'Whole organization', value: 'org' })
  })
  it('lists sites then groups', () => {
    const opts = bulkTargetOptions([{ siteId: 's1', name: 'HQ' }], [{ groupId: 'g1', name: 'Floor 3' }])
    expect(opts).toEqual([
      { label: 'Whole organization', value: 'org' },
      { label: 'Site: HQ', value: 'site:s1' },
      { label: 'Group: Floor 3', value: 'group:g1' },
    ])
  })
})

describe('parseBulkTarget', () => {
  it('parses an org target', () => expect(parseBulkTarget('org')).toEqual({ kind: 'org' }))
  it('parses a site target', () => expect(parseBulkTarget('site:s1')).toEqual({ kind: 'site', id: 's1' }))
  it('parses a group target', () => expect(parseBulkTarget('group:g1')).toEqual({ kind: 'group', id: 'g1' }))
  it('defaults unknown values to org', () => expect(parseBulkTarget('weird')).toEqual({ kind: 'org' }))
})

describe('summarizeBulk', () => {
  it('summarizes a bulk result', () => {
    expect(summarizeBulk({ op: 'inventory', ok: 3, total: 5, failed: 1 })).toBe('inventory: 3/5 ok, 1 failed')
  })
  it('returns empty for no result', () => expect(summarizeBulk(null)).toBe(''))
})

describe('outcomeVariant', () => {
  it('maps ok to success', () => expect(outcomeVariant('ok')).toBe('success'))
  it('maps failed and forbidden to destructive', () => {
    expect(outcomeVariant('failed')).toBe('destructive')
    expect(outcomeVariant('forbidden')).toBe('destructive')
  })
  it('maps skipped/agent_offline/timeout to warning', () => {
    expect(outcomeVariant('agent_offline')).toBe('warning')
    expect(outcomeVariant('skipped')).toBe('warning')
    expect(outcomeVariant('timeout')).toBe('warning')
  })
  it('defaults unknown statuses to outline', () => expect(outcomeVariant('???')).toBe('outline'))
})

describe('lastSeenLabel', () => {
  const now = new Date('2026-08-06T12:00:00Z').getTime()
  const ago = (ms) => new Date(now - ms).toISOString()

  it('says so outright when the agent has never dialed in', () => {
    // Distinct from "a long time ago": this is usually an enrollment whose install never finished.
    expect(lastSeenLabel(null, now)).toBe('never connected')
    expect(lastSeenLabel(undefined, now)).toBe('never connected')
    expect(lastSeenLabel('not-a-date', now)).toBe('never connected')
  })

  it('scales the unit to the gap', () => {
    expect(lastSeenLabel(ago(5 * 1000), now)).toBe('just now')
    expect(lastSeenLabel(ago(18 * 60 * 1000), now)).toBe('18 min ago')
    expect(lastSeenLabel(ago(3 * 3600 * 1000), now)).toBe('3 h ago')
    expect(lastSeenLabel(ago(2 * 86400 * 1000), now)).toBe('2 d ago')
  })

  it('falls back to a date once the relative form stops meaning anything', () => {
    expect(lastSeenLabel(ago(30 * 86400 * 1000), now)).toBe('30 d ago')
    const old = ago(60 * 86400 * 1000)
    expect(lastSeenLabel(old, now)).toBe(new Date(old).toLocaleDateString())
  })
})
