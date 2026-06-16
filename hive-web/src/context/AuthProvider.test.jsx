import { describe, it, expect, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'

// Capture the handlers AuthProvider registers on the (mocked) userManager so the test can fire them.
const h = vi.hoisted(() => ({}))
vi.mock('../auth', () => ({
  userManager: {
    events: {
      addUserLoaded: (fn) => {
        h.loaded = fn
      },
      addAccessTokenExpired: (fn) => {
        h.expired = fn
      },
      removeUserLoaded: () => {},
      removeAccessTokenExpired: () => {},
    },
  },
  login: vi.fn(),
  logout: vi.fn(),
  resolveUser: vi.fn().mockResolvedValue(null), // no session at start
}))

import { AuthProvider, useAuth } from './AuthProvider'

function Probe() {
  const { authenticated } = useAuth()
  return <div data-testid="authed">{String(authenticated)}</div>
}

describe('AuthProvider OIDC token renewal', () => {
  it('adopts a silently-renewed user so the gateway uses the fresh token', async () => {
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    expect(screen.getByTestId('authed').textContent).toBe('false') // anonymous at start
    await act(async () => h.loaded({ access_token: 'fresh-token', expired: false }))
    expect(screen.getByTestId('authed').textContent).toBe('true') // userLoaded -> authenticated
  })

  it('drops to the sign-in gate when the access token finally expires', async () => {
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    await act(async () => h.loaded({ access_token: 'x' }))
    expect(screen.getByTestId('authed').textContent).toBe('true')
    await act(async () => h.expired())
    expect(screen.getByTestId('authed').textContent).toBe('false') // not a silent 401 storm
  })
})
