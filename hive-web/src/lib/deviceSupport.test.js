import { describe, it, expect } from 'vitest'
import { supportLevel, supportBadge } from './deviceSupport'

describe('supportLevel', () => {
  it('marks a known model as tested (case-insensitive)', () => {
    expect(supportLevel('AP230')).toBe('tested')
    expect(supportLevel('ap250')).toBe('tested')
  })

  it('marks an unknown HiveOS model as untested rather than blocking it', () => {
    expect(supportLevel('AP1130')).toBe('untested')
    expect(supportLevel(null)).toBe('untested')
  })

  it('marks a non-HiveOS host as unsupported', () => {
    expect(supportLevel('AP230', { hiveOs: false })).toBe('unsupported')
  })
})

describe('supportBadge', () => {
  it('maps each level to a label and variant', () => {
    expect(supportBadge('tested')).toEqual({ label: 'tested', variant: 'success' })
    expect(supportBadge('unsupported')).toEqual({ label: 'unsupported', variant: 'destructive' })
    expect(supportBadge('untested')).toEqual({ label: 'HiveOS · untested', variant: 'outline' })
  })
})

describe('hardware revision suffixes', () => {
  it('treats AP410C-1 as the AP410C family', () => {
    // HiveOS reports this model as `AP410C-1`. It is the same AP as far as CLI grammar goes, and it was
    // driven live on 2026-07-20 — badging it "untested" over a revision suffix would be wrong.
    expect(supportLevel('AP410C-1')).toBe('tested')
    expect(supportLevel('AP410C')).toBe('tested')
  })

  it('still rejects a genuinely unknown model that merely ends in a digit', () => {
    expect(supportLevel('AP999-1')).toBe('untested')
  })
})
