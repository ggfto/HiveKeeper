import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
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
    expect(screen.getByText('HiveKeeper')).toBeInTheDocument()
    expect(screen.getByText('Devices')).toBeInTheDocument()
    expect(screen.getByText('Hello content')).toBeInTheDocument()
  })

  it('marks dev-owner mode in the topbar when there is no signed-in user', () => {
    render(
      <ConsoleLayout activeRoute="/overview" onNavigate={() => {}} auth={auth}>
        <p>x</p>
      </ConsoleLayout>,
    )
    expect(screen.getByText('DEV OWNER')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /exit/i })).toBeInTheDocument()
  })
})
