import { describe, it, expect } from 'vitest'
import {
  groupNamesFor,
  siteName,
  bulkTargetOptions,
  parseBulkTarget,
  summarizeBulk,
  outcomeVariant,
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
