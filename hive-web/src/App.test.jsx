import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import App from './App'

afterEach(() => vi.unstubAllGlobals())

// Smoke test of the whole composition: with no OIDC session in jsdom, App lands on the sign-in gate and only
// enters the console (HashRouter -> AuthProvider -> ConsoleLayout -> default page) once dev mode is chosen.
describe('App', () => {
  it('gates behind sign-in, then enters the console via the explicit dev owner key', async () => {
    render(<App />)
    // anonymous (setup status errors out -> assume initialized -> the gate, not the console or wizard)
    expect(await screen.findByText('HiveKeeper')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()

    // opt into dev owner mode -> the console shell mounts on the default Overview page
    fireEvent.click(screen.getByRole('button', { name: /dev owner key/i }))
    expect(await screen.findByText('Bulk ops')).toBeInTheDocument() // sidebar nav
    expect(screen.getByText('DEV OWNER')).toBeInTheDocument() // topbar badge
  })

  it('shows the first-run wizard when the gateway reports it is uninitialized', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        String(url).includes('/api/setup/status')
          ? Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ initialized: false })) })
          : Promise.reject(new Error('not found')),
      ),
    )
    render(<App />)
    expect(await screen.findByText(/welcome to hivekeeper/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/paste the printed token/i)).toBeInTheDocument()
  })
})
