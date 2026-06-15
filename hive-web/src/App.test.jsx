import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import App from './App'

// Smoke test of the whole composition: with no OIDC session in jsdom, App lands on the sign-in gate and only
// enters the console (HashRouter -> AuthProvider -> ConsoleLayout -> default page) once dev mode is chosen.
describe('App', () => {
  it('gates behind sign-in, then enters the console via the explicit dev owner key', async () => {
    render(<App />)
    // anonymous: the gate, not the console
    expect(screen.getByText('HiveKeeper')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()

    // opt into dev owner mode -> the console shell mounts on the default Overview page
    fireEvent.click(screen.getByRole('button', { name: /dev owner key/i }))
    expect(await screen.findByText('Bulk ops')).toBeInTheDocument() // sidebar nav
    expect(screen.getByText('DEV OWNER')).toBeInTheDocument() // topbar badge
  })
})
