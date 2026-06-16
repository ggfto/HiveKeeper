import { render } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { AuthContext } from '../context/AuthProvider'
import { ToastProvider } from '../context/ToastProvider'
import { Toaster } from '../components/molecules/Toaster'

/** A gateway double whose every method resolves to [] unless overridden — so a page under test never hits an
 *  unstubbed method. Override the calls a given page makes with concrete resolved values. */
export function fakeGateway(overrides = {}) {
  const fallback = () => Promise.resolve([])
  return new Proxy(overrides, { get: (target, prop) => (prop in target ? target[prop] : fallback) })
}

/**
 * Render a page wrapped in a MemoryRouter + an AuthContext with a fake gateway. Pass `route` + `path` to drive
 * a routed page that reads useParams (e.g. the device detail page); omit them for a plain page.
 */
export function renderWithAuth(ui, { gateway = fakeGateway(), auth = {}, route = '/', path } = {}) {
  const value = {
    user: null,
    me: null,
    activeOrg: 'acme',
    setActiveOrg: () => {},
    signIn: () => {},
    signOut: () => {},
    gateway,
    ...auth,
  }
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthContext.Provider value={value}>
        <ToastProvider>
          {path ? (
            <Routes>
              <Route path={path} element={ui} />
            </Routes>
          ) : (
            ui
          )}
          <Toaster />
        </ToastProvider>
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}
