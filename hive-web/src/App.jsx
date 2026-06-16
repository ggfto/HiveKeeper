import { useEffect, useState } from 'react'
import { HashRouter, Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthProvider'
import { ToastProvider } from './context/ToastProvider'
import { Toaster } from './components/molecules/Toaster'
import { ConsoleLayout } from './components/templates/ConsoleLayout'
import { SignInGate } from './components/organisms/SignInGate'
import { SetupWizard } from './components/organisms/SetupWizard'
import { OverviewPage } from './pages/OverviewPage'
import { AgentsPage } from './pages/AgentsPage'
import { DevicesPage } from './pages/DevicesPage'
import { DeviceDetailPage } from './pages/DeviceDetailPage'
import { SitesGroupsPage } from './pages/SitesGroupsPage'
import { MembersPage } from './pages/MembersPage'
import { BulkPage } from './pages/BulkPage'
import { AuditPage } from './pages/AuditPage'

function Console() {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <ConsoleLayout activeRoute={location.pathname} onNavigate={(route) => navigate(route)} auth={auth}>
      <Routes>
        <Route path="/overview" element={<OverviewPage />} />
        <Route path="/agents" element={<AgentsPage />} />
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/devices/:deviceId" element={<DeviceDetailPage />} />
        <Route path="/sites-groups" element={<SitesGroupsPage />} />
        <Route path="/members" element={<MembersPage />} />
        <Route path="/bulk" element={<BulkPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
    </ConsoleLayout>
  )
}

/** Gate: a fresh gateway (no organizations) shows the first-run wizard; otherwise the console only mounts (and
 *  only makes authorized requests) once there is a real identity or the dev owner key was explicitly chosen.
 *  The setup check shows the wizard ONLY on an explicit { initialized: false } — any error or older gateway
 *  falls through to the normal sign-in. */
function Root() {
  const auth = useAuth()
  const [setup, setSetup] = useState({ loading: true, initialized: true })

  useEffect(() => {
    let active = true
    auth.gateway
      .setupStatus()
      .then((s) => active && setSetup({ loading: false, initialized: s?.initialized !== false }))
      .catch(() => active && setSetup({ loading: false, initialized: true }))
    return () => {
      active = false
    }
  }, [auth.gateway])

  if (setup.loading) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">Loading…</div>
  }
  if (!setup.initialized) {
    return <SetupWizard gateway={auth.gateway} onSignIn={auth.signIn} />
  }
  if (!auth.authenticated) {
    return <SignInGate onSignIn={auth.signIn} onDevMode={auth.enableDevMode} />
  }
  return <Console />
}

/** The HiveKeeper multi-org control panel. Gateway-only: every page talks to hive-gateway through /gw. */
export default function App() {
  return (
    <HashRouter>
      <AuthProvider>
        <ToastProvider>
          <Root />
          <Toaster />
        </ToastProvider>
      </AuthProvider>
    </HashRouter>
  )
}
