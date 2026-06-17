import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ConsoleLayout } from './ConsoleLayout'

const auth = {
  user: null,
  me: null,
  activeOrg: '',
  setActiveOrg: () => {},
  signIn: () => {},
  signOut: () => {},
}

describe('ConsoleLayout', () => {
  it('renders the brand, the navigation and the page content', () => {
    render(
      <ConsoleLayout activeRoute="/overview" onNavigate={() => {}} auth={auth}>
        <p>Hello content</p>
      </ConsoleLayout>,
    )
    // The brand renders both in the persistent desktop sidebar and in the mobile top bar.
    expect(screen.getAllByText('HiveKeeper').length).toBeGreaterThan(0)
    expect(screen.getByText('Devices')).toBeInTheDocument()
    expect(screen.getByText('Hello content')).toBeInTheDocument()
  })

  it('opens the navigation drawer from the mobile hamburger and closes it on the backdrop', () => {
    render(
      <ConsoleLayout activeRoute="/overview" onNavigate={() => {}} auth={auth}>
        <p>x</p>
      </ConsoleLayout>,
    )
    // Closed initially: no close affordance present.
    expect(screen.queryByLabelText('Close navigation')).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Open navigation'))
    const close = screen.getByLabelText('Close navigation')
    expect(close).toBeInTheDocument()
    fireEvent.click(close)
    expect(screen.queryByLabelText('Close navigation')).not.toBeInTheDocument()
  })

  it('marks dev-owner mode in the sidebar footer when there is no signed-in user', () => {
    render(
      <ConsoleLayout activeRoute="/overview" onNavigate={() => {}} auth={auth}>
        <p>x</p>
      </ConsoleLayout>,
    )
    expect(screen.getByText('DEV OWNER')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /exit/i })).toBeInTheDocument()
  })

  it('shows a LOCAL marker and trims the nav in solo mode', () => {
    render(
      <ConsoleLayout activeRoute="/devices" onNavigate={() => {}} auth={{ ...auth, solo: true }}>
        <p>x</p>
      </ConsoleLayout>,
    )
    expect(screen.getByText('LOCAL')).toBeInTheDocument()
    expect(screen.queryByText('DEV OWNER')).not.toBeInTheDocument()
    expect(screen.queryByText('Members')).not.toBeInTheDocument() // multi-org nav hidden
    expect(screen.getByText('Devices')).toBeInTheDocument()
  })
})
