import { describe, it, expect } from 'vitest'
import { parseTemplateCommands, loadTemplates, saveTemplate, deleteTemplate } from './configTemplate'

// A tiny in-memory Storage stand-in (getItem/setItem), so the persistence helpers are testable without a browser.
function fakeStorage(initial = {}) {
  const map = { ...initial }
  return {
    getItem: (k) => (k in map ? map[k] : null),
    setItem: (k, v) => {
      map[k] = String(v)
    },
    _map: map,
  }
}

describe('parseTemplateCommands', () => {
  it('splits lines, trims, and drops blanks and # comments', () => {
    const text = `# set the hostname\nhostname AP-LOBBY\n\n   ssid HK schedule work-hours   \n# trailing comment`
    expect(parseTemplateCommands(text)).toEqual(['hostname AP-LOBBY', 'ssid HK schedule work-hours'])
  })
  it('is empty for blank/missing input', () => {
    expect(parseTemplateCommands('')).toEqual([])
    expect(parseTemplateCommands('   \n  \n')).toEqual([])
    expect(parseTemplateCommands(null)).toEqual([])
  })
})

describe('template persistence', () => {
  it('returns an empty list when nothing is stored or the value is garbage', () => {
    expect(loadTemplates(fakeStorage())).toEqual([])
    expect(loadTemplates(fakeStorage({ 'hivekeeper.configTemplates': 'not json' }))).toEqual([])
  })
  it('saves a new template and reads it back', () => {
    const s = fakeStorage()
    const list = saveTemplate(s, 'Guest baseline', 'hostname X\nssid GUEST hide-ssid')
    expect(list).toEqual([{ name: 'Guest baseline', text: 'hostname X\nssid GUEST hide-ssid' }])
    expect(loadTemplates(s)).toEqual(list)
  })
  it('overwrites a template with the same name (case/space-insensitive)', () => {
    const s = fakeStorage()
    saveTemplate(s, 'Base', 'a')
    const list = saveTemplate(s, '  base ', 'b')
    expect(list).toEqual([{ name: 'Base', text: 'b' }])
  })
  it('ignores a save with a blank name or no commands', () => {
    const s = fakeStorage()
    expect(saveTemplate(s, '  ', 'hostname X')).toEqual([])
    expect(saveTemplate(s, 'Empty', '   ')).toEqual([])
  })
  it('deletes a template by name', () => {
    const s = fakeStorage()
    saveTemplate(s, 'A', 'x')
    saveTemplate(s, 'B', 'y')
    expect(deleteTemplate(s, 'A')).toEqual([{ name: 'B', text: 'y' }])
    expect(loadTemplates(s)).toEqual([{ name: 'B', text: 'y' }])
  })
})
