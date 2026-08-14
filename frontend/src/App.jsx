import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Portal from './pages/Portal.jsx'

// The staff apps are far bigger than the captive portal and are never
// needed by a customer buying WiFi, so they load on demand — the portal
// itself has to be fast over a hotspot link.
const Admin = lazy(() => import('./pages/Admin.jsx'))
const Tech = lazy(() => import('./pages/Tech.jsx'))
const PayPortal = lazy(() => import('./pages/PayPortal.jsx'))
const MyAccount = lazy(() => import('./pages/MyAccount.jsx'))
const Status = lazy(() => import('./pages/Status.jsx'))
const NotFound = lazy(() => import('./pages/NotFound.jsx'))

function Loading() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <div className="flex flex-col items-center gap-3">
        <div className="w-10 h-10 rounded-full border-4 border-surface-variant border-t-primary animate-spin"></div>
        <p className="text-sm text-on-surface-variant">Loading…</p>
      </div>
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/" element={<Portal />} />
          <Route path="/admin/*" element={<Admin />} />
          <Route path="/tech/*" element={<Tech />} />
          <Route path="/pay" element={<PayPortal />} />
          <Route path="/my-account" element={<MyAccount />} />
          <Route path="/status" element={<Status />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  )
}
