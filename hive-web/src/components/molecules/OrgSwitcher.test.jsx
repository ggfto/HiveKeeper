import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { OrgSwitcher } from './OrgSwitcher'

describe('OrgSwitcher', () => {
  it('renders nothing when the user belongs to no organizations', () => {
    const { container } = render(<OrgSwitcher me={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the single organization name as plain text', () => {
    render(<OrgSwitcher me={{ organizations: [{ tenantId: 'acme', tenantName: 'Acme' }] }} activeOrg="acme" />)
    expect(screen.getByText('Acme')).toBeInTheDocument()
  })

  it('shows a switcher with the active organization selected when there are several', () => {
    render(
      <OrgSwitcher
        me={{ organizations: [{ tenantId: 'acme', tenantName: 'Acme' }, { tenantId: 'gx', tenantName: 'Globex' }] }}
        activeOrg="gx"
      />,
    )
    expect(screen.getByText('Globex')).toBeInTheDocument()
  })
})
