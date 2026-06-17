import { describe, it, expect } from 'vitest'
import { parseFrontmatter, allDocs, getDoc } from './docs'

describe('docs', () => {
  it('parses frontmatter title/description and strips the block', () => {
    const { data, body } = parseFrontmatter('---\ntitle: Hello\ndescription: A doc\n---\n# Body\n')
    expect(data.title).toBe('Hello')
    expect(data.description).toBe('A doc')
    expect(body.startsWith('# Body')).toBe(true)
  })

  it('treats plain markdown as having no frontmatter', () => {
    const { data, body } = parseFrontmatter('# Just markdown')
    expect(data).toEqual({})
    expect(body).toBe('# Just markdown')
  })

  it('loads the bundled docs in sidebar order with titles', () => {
    const docs = allDocs()
    expect(docs.length).toBeGreaterThan(0)
    expect(docs[0].id).toBe('index')
    expect(getDoc('getting-started')?.title).toBe('Getting started')
  })
})
