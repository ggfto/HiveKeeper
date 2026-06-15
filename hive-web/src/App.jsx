import { HashRouter, Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthProvider'
import { ConsoleLayout } from './components/templates/ConsoleLayout'
import { SignInGate } from './components/organisms/SignInGate'
import { OverviewPage } from './pages/OverviewPage'
import { AgentsPage } from './pages/AgentsPage'
import { DevicesPage } from './pages/DevicesPage'
import { DeviceDetailPage } from './pages/DeviceDetailPage'
import { SitesGroupsPage } from './pages/SitesGroupsPage'
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
        <Route path="/bulk" element={<BulkPage />} />
        <Route path="/audit" element={<AuditPage />} />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
    </ConsoleLayout>
  )
}

/** Gate: the console only mounts (and only makes authorized requests) once there is a real identity or the
 *  dev owner key was explicitly chosen. */
function Root() {
  const auth = useAuth()
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
        <Root />
      </AuthProvider>
    </HashRouter>
  )
}
