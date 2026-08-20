import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import { INPUT_CLS, LABEL_CLS, PrimaryButton, PageHeader, StatCard, AreaSparkline } from '../components/ui.jsx'
import { enrollPasskey, passkeyLogin, passkeySupported } from '../passkey.js'
import { triggerInstall } from '../pwa.js'
import TaskNotes from '../components/TaskNotes.jsx'
import ChatThread from '../components/ChatThread.jsx'
import zidiLogo from '../assets/zidi-logo.png'
import zidiLogoDark from '../assets/zidi-logo-dark.png'
import RoutersPage from './admin/Routers.jsx'
import DevicesPage from './admin/Devices.jsx'
import RadiusPage from './admin/Radius.jsx'
import RetentionPage from './admin/Retention.jsx'
import ThemeSwitcher from '../components/ThemeSwitcher.jsx'
import FinancePage from './admin/Finance.jsx'
import BranchesPage from './admin/Branches.jsx'
import PayBillPage from './admin/PayBill.jsx'
import AuditLogPage from './admin/AuditLog.jsx'
import ActiveUsersPage from './admin/ActiveUsers.jsx'
import BrandingPage from './admin/Branding.jsx'
import LeadsPage from './admin/Leads.jsx'
import AgentsPage from './admin/Agents.jsx'
import EquipmentPage from './admin/Equipment.jsx'
import AnalyticsPage from './admin/Analytics.jsx'
import VouchersPage from './admin/Vouchers.jsx'
import CommunicationsPage from './admin/Communications.jsx'
import FiberPage from './admin/Fiber.jsx'
import CpePage from './admin/Cpe.jsx'
import UsagePage from './admin/Usage.jsx'
import RouterBackupsPage from './admin/RouterBackups.jsx'
import StaffPage from './admin/Staff.jsx'
import LedgerPage from './admin/Ledger.jsx'
import RevenueAuditPage from './admin/RevenueAudit.jsx'
import SystemHealthPage from './admin/SystemHealth.jsx'
import TaxSettingsPage from './admin/TaxSettings.jsx'
import PaymentGatewaysPage from './admin/PaymentGateways.jsx'
import SettingsHub from './admin/SettingsHub.jsx'
import loginFiber from '../assets/login-fiber.jpg'

/* ------------------------------------------------------------------ */
/* Shared helpers                                                      */
/* ------------------------------------------------------------------ */

import { Icon } from '../components/icons.jsx'
import { payBrand, payPinPhrase } from '../payBrand.js'
import { phoneExample } from '../phone.js'
import { money } from '../money.js'

function formatDuration(minutes) {
  if (minutes < 60) return `${minutes} min`
  if (minutes < 1440) {
    const h = Math.floor(minutes / 60)
    const m = minutes % 60
    return m ? `${h} hr ${m} min` : `${h} hr`
  }
  const d = Math.floor(minutes / 1440)
  const h = Math.floor((minutes % 1440) / 60)
  return h ? `${d} day${d > 1 ? 's' : ''} ${h} hr` : `${d} day${d > 1 ? 's' : ''}`
}

const PLAN_GROUPS = ['Hourly', 'Daily', 'Weekly', 'Monthly']

function planGroup(minutes) {
  if (minutes < 1440) return 'Hourly'
  if (minutes < 7 * 1440) return 'Daily'
  if (minutes < 28 * 1440) return 'Weekly'
  return 'Monthly'
}

/** <option> list grouped Hourly/Daily/Weekly/Monthly for plan selectors. */
function speedLabel(bandwidth) {
  if (!bandwidth) return null
  const down = bandwidth.split('/')[0].trim().replace(/M$/i, '')
  return `${down} Mbps`
}

function fmtKES(n) {
  return `${money(n || 0)}`
}

function fmtNum(n) {
  return Number(n || 0).toLocaleString()
}

function fmtDate(d) {
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function fmtTime(d) {
  return new Date(d).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function relativeTime(d) {
  const diff = Date.now() - new Date(d).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min${mins > 1 ? 's' : ''} ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs} hour${hrs > 1 ? 's' : ''} ago`
  const days = Math.floor(hrs / 24)
  return `${days} day${days > 1 ? 's' : ''} ago`
}

const PILL_STYLES = {
  SUCCESS: { bg: 'bg-secondary-container', text: 'text-on-secondary-container', dot: 'bg-secondary', label: 'Success' },
  PENDING: { bg: 'bg-surface-container-highest', text: 'text-on-surface', dot: 'bg-outline', label: 'Pending' },
  FAILED: { bg: 'bg-error-container', text: 'text-on-error-container', dot: 'bg-error', label: 'Failed' },
  UNUSED: { bg: 'bg-surface-container-high', text: 'text-on-surface-variant', dot: 'bg-outline', label: 'Unused' },
  ACTIVE: { bg: 'bg-primary-fixed/40', text: 'text-primary', dot: 'bg-primary animate-pulse', label: 'Active' },
  USED: { bg: 'bg-secondary-container', text: 'text-on-secondary-container', dot: 'bg-secondary', label: 'Used' },
  EXPIRED: { bg: 'bg-error-container', text: 'text-on-error-container', dot: 'bg-error', label: 'Expired' },
  INACTIVE: { bg: 'bg-surface-variant', text: 'text-on-surface-variant', dot: 'bg-outline', label: 'Inactive' },
}

function StatusPill({ status }) {
  const s = PILL_STYLES[status] || { bg: 'bg-surface-container-high', text: 'text-on-surface-variant', dot: 'bg-outline', label: status }
  return (
    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full ${s.bg} ${s.text} text-xs font-semibold tracking-wider`}>
      <span className={`w-1.5 h-1.5 rounded-full ${s.dot}`}></span>
      {s.label}
    </span>
  )
}

function CardLabel({ children }) {
  return <h3 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{children}</h3>
}

/* Animated count-up for KPI numbers (skips animation under prefers-reduced-motion) */
function useCountUp(target, duration = 700) {
  const [value, setValue] = useState(0)
  useEffect(() => {
    const n = Number(target) || 0
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setValue(n)
      return
    }
    let raf
    let start
    const step = (t) => {
      if (start === undefined) start = t
      const p = Math.min((t - start) / duration, 1)
      setValue(Math.round(n * (1 - Math.pow(1 - p, 3))))
      if (p < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [target, duration])
  return value
}

function Skeleton({ className = '' }) {
  return <div className={`animate-pulse bg-surface-container-high rounded-xl ${className}`}></div>
}

/* ------------------------------------------------------------------ */
/* Root: login gate                                                    */
/* ------------------------------------------------------------------ */

export default function Admin({ demo = false }) {
  const [auth, setAuth] = useState(sessionStorage.getItem('adminAuth'))
  const [demoLoading, setDemoLoading] = useState(false)
  const [demoError, setDemoError] = useState(null)

  // Sign into the read-only demo automatically so a prospect never meets a
  // login wall. Reached by the /demo route, or by the older /admin?demo=1 that
  // links in the wild still point at.
  const wantsDemo = demo
    || new URLSearchParams(window.location.search).get('demo') === '1'

  useEffect(() => {
    if (auth || !wantsDemo) return
    setDemoLoading(true)
    api('/auth/demo', { method: 'POST' })
      .then((res) => {
        const a = 'Bearer ' + res.token
        sessionStorage.setItem('adminAuth', a)
        setAuth(a)
      })
      .catch(() => {
        // Deliberately not silent. This fails on every real ISP deployment,
        // where demo mode is off by design — and swallowing it dropped a
        // prospect on a staff login form with no explanation, which reads as a
        // broken product rather than a link pointed at the wrong host.
        setDemoError('This deployment is not the demo. '
          + 'The live demo runs on its own address — try the link on zidi.co.ke.')
      })
      .finally(() => setDemoLoading(false))
  }, [wantsDemo]) // eslint-disable-line react-hooks/exhaustive-deps

  function logout() {
    // Best-effort server-side revocation; the session ends locally regardless.
    api('/auth/logout', { method: 'POST', auth }).catch(() => {})
    sessionStorage.removeItem('adminAuth')
    setAuth(null)
  }

  if (demoLoading) {
    return (
      <div className="admin-theme bg-inverse-surface text-on-background min-h-screen flex flex-col items-center justify-center gap-3">
        <Icon name="progress_activity" className="animate-spin text-primary text-[32px]!" />
        <p className="text-sm text-on-surface-variant">Opening the live demo…</p>
      </div>
    )
  }

  if (demoError) {
    return (
      <div className="admin-theme bg-inverse-surface text-on-background min-h-screen flex flex-col items-center justify-center gap-4 px-6 text-center">
        <Icon name="info" className="text-primary text-[32px]!" />
        <p className="text-sm text-on-surface-variant max-w-md">{demoError}</p>
        <a href="/admin" className="text-sm font-semibold text-primary underline">
          Staff sign-in instead
        </a>
      </div>
    )
  }

  return auth
    ? <Shell auth={auth} onLogout={logout} />
    : <Login onLogin={(a) => { sessionStorage.setItem('adminAuth', a); setAuth(a) }} />
}

/* ------------------------------------------------------------------ */
/* Login                                                               */
/* ------------------------------------------------------------------ */

function Login({ onLogin }) {
  // Pre-fill the email when arriving from the central email-first sign-in
  // (…/admin?email=you@isp.co.ke), so the owner only types their password.
  const [username, setUsername] = useState(
    () => new URLSearchParams(window.location.search).get('email') || '')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [needCode, setNeedCode] = useState(false)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  // Set when a password sign-in succeeds but policy makes the account enrol a
  // passkey before it reaches the dashboard.
  const [enroll, setEnroll] = useState(null) // { token }
  const canPasskey = passkeySupported()

  async function usePasskey() {
    setBusy(true)
    setError(null)
    try {
      const res = await passkeyLogin(username)
      onLogin('Bearer ' + res.token)
    } catch (err) {
      // A cancelled browser prompt throws NotAllowedError — not worth alarming.
      if (err.name === 'NotAllowedError' || err.name === 'AbortError') {
        setError('Passkey sign-in was cancelled.')
      } else {
        setError(err.status === 400 ? 'No passkey is set up for this account. Use your password.' : (err.message || 'Passkey sign-in failed.'))
      }
    } finally {
      setBusy(false)
    }
  }

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      // Primary path: exchange the password (and a one-time code where the
      // account has one) for a session token.
      const res = await api('/auth/login', {
        method: 'POST',
        body: { username, password, code: code || undefined },
      })
      const token = 'Bearer ' + res.token
      if (res.passkeyEnrollmentRequired && canPasskey) {
        setEnroll({ token })
        return
      }
      onLogin(token)
    } catch (err) {
      if (err.status === 428) {
        // Password was right; the account has two-factor on. Ask for the code
        // rather than reporting a failure.
        setNeedCode(true)
        setError(needCode ? 'That code is not right. Try the current one.' : null)
        return
      }
      // The break-glass account from application.properties is not a database
      // row, so /auth/login cannot mint it a token. Fall back to Basic so a
      // broken staff table can never lock an owner out entirely.
      if (err.status === 401 && !needCode) {
        const basic = 'Basic ' + btoa(`${username}:${password}`)
        try {
          await api('/admin/stats', { auth: basic })
          onLogin(basic)
          return
        } catch { /* fall through to the normal error */ }
      }
      setError(err.status === 401 ? 'Wrong username or password' : err.message)
    } finally {
      setBusy(false)
    }
  }

  if (enroll) {
    return <EnrollPasskey token={enroll.token} onDone={() => onLogin(enroll.token)} canPasskey={canPasskey} />
  }

  return (
    <div className="admin-theme relative bg-inverse-surface text-on-background min-h-screen flex flex-col items-center justify-center px-5 overflow-hidden">
      <img src={loginFiber} alt="" className="absolute inset-0 w-full h-full object-cover" />
      <div className="absolute inset-0 bg-black/70"></div>
      <div className="relative z-10 w-full max-w-sm">
        <div className="flex flex-col items-center mb-8">
          <img src={zidiLogo} alt="Zidi" className="h-16 w-auto object-contain mb-3 drop-shadow-[0_8px_16px_rgba(0,0,0,0.4)]" />
          <p className="text-sm text-white/70">Network Manager</p>
        </div>

        <form
          onSubmit={submit}
          className="bg-surface-container-lowest rounded-lg border border-outline-variant border-t-2 border-t-primary p-6 flex flex-col gap-4"
        >
          <h2 className="text-lg font-semibold text-on-surface">Sign in to Zidi</h2>
          <div>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="admin-user">
              Email or username
            </label>
            <input
              id="admin-user"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              autoComplete="username"
              placeholder="you@example.com"
              className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base text-on-surface placeholder:text-on-surface-variant/50 focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
          </div>
          <div>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="admin-pass">
              Password
            </label>
            <input
              id="admin-pass"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
          </div>
          {needCode && (
            <div>
              <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="admin-code">
                Authenticator code
              </label>
              <input
                id="admin-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="\d{6}"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                placeholder="6-digit code"
                autoFocus
                className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base font-mono tracking-[0.3em] text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
              />
              <p className="text-xs text-on-surface-variant mt-1.5">From your authenticator app.</p>
            </div>
          )}
          {error && <p className="text-sm text-error">{error}</p>}
          <button
            type="submit"
            disabled={busy}
            className="w-full h-12 bg-primary text-on-primary rounded-lg text-lg font-semibold  hover:bg-surface-tint active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer"
          >
            {busy ? 'Signing in…' : needCode ? 'Verify & Sign In' : 'Sign In'}
          </button>
          {canPasskey && !needCode && (
            <>
              <div className="flex items-center gap-3 my-1">
                <span className="h-px flex-1 bg-outline-variant" />
                <span className="text-[11px] uppercase tracking-wider text-on-surface-variant">or</span>
                <span className="h-px flex-1 bg-outline-variant" />
              </div>
              <button
                type="button"
                onClick={usePasskey}
                disabled={busy}
                className="w-full h-12 bg-surface border border-outline-variant text-on-surface rounded-lg text-sm font-semibold flex items-center justify-center gap-2 hover:bg-surface-container-high active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer"
              >
                <Icon name="fingerprint" className="text-[20px]! text-primary" />
                Sign in with a passkey
              </button>
            </>
          )}
          <p className="text-xs text-on-surface-variant flex items-center gap-1.5 justify-center">
            <Icon name="lock" className="text-[14px]!" /> Restricted area — authorized staff only.
          </p>
        </form>
      </div>
    </div>
  )
}

/**
 * Shown once, straight after a first password sign-in, when policy requires a
 * passkey. The password got them this far (and stays their recovery path); a
 * passkey is what they use from now on.
 */
function EnrollPasskey({ token, onDone, canPasskey }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function enable() {
    setBusy(true)
    setError(null)
    try {
      await enrollPasskey(token, `${navigator.platform || 'This device'}`)
      onDone()
    } catch (err) {
      if (err.name === 'NotAllowedError' || err.name === 'AbortError') {
        setError('That was cancelled. Try again — a passkey is required for this account.')
      } else {
        setError(err.message || 'Could not set up the passkey. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="admin-theme relative bg-inverse-surface text-on-background min-h-screen flex flex-col items-center justify-center px-5 overflow-hidden">
      <img src={loginFiber} alt="" className="absolute inset-0 w-full h-full object-cover" />
      <div className="absolute inset-0 bg-black/70"></div>
      <div className="relative z-10 w-full max-w-sm">
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant border-t-2 border-t-primary p-6 flex flex-col gap-4 text-center">
          <div className="w-16 h-16 rounded-full bg-primary/15 flex items-center justify-center mx-auto">
            <Icon name="fingerprint" className="text-primary text-[36px]!" />
          </div>
          <h2 className="text-lg font-semibold text-on-surface">Set up your passkey</h2>
          <p className="text-sm text-on-surface-variant">
            Use this device's fingerprint, face or PIN to sign in from now on — faster than a
            password, and it can't be phished. Your password stays as backup.
          </p>
          {error && <p className="text-sm text-error">{error}</p>}
          {canPasskey ? (
            <button
              onClick={enable}
              disabled={busy}
              className="w-full h-12 bg-primary text-on-primary rounded-lg text-base font-semibold flex items-center justify-center gap-2 hover:bg-surface-tint active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer"
            >
              <Icon name="fingerprint" className="text-[20px]!" />
              {busy ? 'Waiting for your device…' : 'Set up passkey'}
            </button>
          ) : (
            <>
              <p className="text-xs text-error">
                This browser can't create a passkey (it needs a secure HTTPS connection).
              </p>
              <button onClick={onDone} className="w-full h-11 border border-outline-variant text-on-surface rounded-lg text-sm font-semibold hover:bg-surface-container-high cursor-pointer">
                Continue for now
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Dashboard shell: sidebar + topbar                                   */
/* ------------------------------------------------------------------ */

/**
 * Grouped so the sidebar stays scannable — a flat list of fifteen items
 * is the "overloaded nav" anti-pattern.
 */
const NAV_GROUPS = [
  {
    label: null,
    items: [
      { key: 'overview', label: 'Overview', icon: 'dashboard' },
      { key: 'analytics', label: 'Analytics', icon: 'insights', need: 'FINANCE' },
    ],
  },
  {
    label: 'Customers',
    items: [
      { key: 'subscribers', label: 'Subscribers', icon: 'lan', need: 'CUSTOMERS' },
      { key: 'leads', label: 'Leads', icon: 'person_search', need: 'CUSTOMERS' },
      { key: 'retention', label: 'Keeping customers', icon: 'monitor_heart', need: 'CUSTOMERS' },
      { key: 'usage', label: 'Data usage', icon: 'data_usage', need: 'CUSTOMERS' },
      { key: 'support', label: 'Tickets', icon: 'support_agent', need: 'CUSTOMERS' },
    ],
  },
  {
    label: 'Network',
    items: [
      { key: 'active', label: 'Live Sessions', icon: 'wifi_tethering', need: 'NETWORK' },
      { key: 'plans', label: 'Plans', icon: 'inventory_2', need: 'PRICING' },
      { key: 'routers', label: 'Routers', icon: 'router', need: 'NETWORK' },
      { key: 'devices', label: 'Devices', icon: 'lan', need: 'NETWORK' },
      { key: 'radius', label: 'RADIUS', icon: 'key', need: 'NETWORK' },
      { key: 'equipment', label: 'Equipment', icon: 'inventory_2', need: 'NETWORK' },
      { key: 'fiber', label: 'Fiber', icon: 'polyline', need: 'NETWORK' },
      { key: 'cpe', label: 'Customer routers', icon: 'router', need: 'NETWORK' },
      { key: 'backups', label: 'Router backups', icon: 'backup', need: 'NETWORK' },
      { key: 'maintenance', label: 'Maintenance', icon: 'calendar_month', need: 'NETWORK' },
    ],
  },
  {
    label: 'Finance',
    items: [
      { key: 'finance', label: 'Billing', icon: 'receipt_long', need: 'FINANCE' },
      { key: 'ledger', label: 'Ledger', icon: 'account_balance_wallet', need: 'FINANCE' },
      { key: 'payments', label: 'Payments', icon: 'payments', need: 'FINANCE' },
      { key: 'paybill', label: 'PayBill', icon: 'account_balance', need: 'FINANCE' },
      { key: 'assurance', label: 'Revenue Guard', icon: 'policy', need: 'FINANCE' },
      { key: 'vouchers', label: 'Vouchers', icon: 'confirmation_number', need: 'SELL' },
      { key: 'agents', label: 'Agents', icon: 'storefront', need: 'SELL' },
    ],
  },
  {
    label: 'Outreach',
    items: [
      { key: 'outbox', label: 'Outbox', icon: 'outbox', need: 'OUTREACH' },
      { key: 'messages', label: 'Team Chat', icon: 'chat', need: 'CUSTOMERS' },
      { key: 'promos', label: 'Promotions', icon: 'campaign', need: 'OUTREACH' },
    ],
  },
  {
    label: 'Organisation',
    items: [
      { key: 'team', label: 'Team', icon: 'group', need: 'STAFF' },
      { key: 'staff', label: 'Staff Logins', icon: 'admin_panel_settings', need: 'STAFF' },
      { key: 'branches', label: 'Branches', icon: 'add_business', need: 'FINANCE' },
      { key: 'audit', label: 'Audit Log', icon: 'history', need: 'STAFF' },
      { key: 'health', label: 'System Health', icon: 'monitor_heart', need: 'SETTINGS' },
      { key: 'settings', label: 'Settings', icon: 'settings', need: 'SETTINGS' },
    ],
  },
]

const NAV = NAV_GROUPS.flatMap((g) => g.items)

/** Unread/open counts for the destinations inside a collapsed group. */
/** Destinations this role can actually use; untagged ones are open to all. */
function allowedGroups(permissions) {
  if (!permissions) return NAV_GROUPS
  return NAV_GROUPS
    .map((g) => ({ ...g, items: g.items.filter((i) => !i.need || permissions.includes(i.need)) }))
    .filter((g) => g.items.length > 0)
}

function SidebarContent({ tab, onNav, onLogout, badges = {}, permissions, me, collapsible = false }) {
  // The rail is taller than a laptop screen. A fade on the bottom edge shows
  // there is more below, but it must clear once you reach the end, otherwise
  // the last item looks disabled.
  const [moreBelow, setMoreBelow] = useState(false)
  const railRef = useRef(null)

  const measure = () => {
    const el = railRef.current
    if (el) setMoreBelow(el.scrollTop + el.clientHeight < el.scrollHeight - 4)
  }

  useEffect(() => {
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [])

  // When collapsible, the rail sits at icon width and only the icons show;
  // hovering the whole nav (the `group`) reveals every label. Labels must be
  // *display:none* on the rail (not just transparent) — a hidden-but-present
  // label still takes width, and `justify-center` would then centre the whole
  // row and push the icon off the left edge. The mobile drawer passes
  // collapsible=false and always shows labels.
  const railText = collapsible ? 'md:hidden md:group-hover:inline' : ''
  const railBlock = collapsible ? 'md:hidden md:group-hover:block' : ''
  const railFlex = collapsible ? 'md:hidden md:group-hover:flex' : ''

  return (
    <div className="flex flex-col h-full py-5 px-3">
      <div className={`mb-5 px-4 shrink-0 flex flex-col ${collapsible ? 'md:px-2 md:items-center md:group-hover:items-start md:group-hover:px-4' : ''}`}>
        <img
          src={zidiLogo}
          alt="Zidi"
          className={`w-auto object-contain object-left shrink-0 ${collapsible ? 'h-7 md:group-hover:h-9' : 'h-9'}`}
        />
        <p className={`mt-1.5 text-[10px] font-semibold tracking-wider text-surface-variant/70 whitespace-nowrap ${railBlock}`}>NETWORK MANAGER</p>
      </div>
      <div className="relative flex-1 min-h-0 flex">
      <nav
        ref={railRef}
        onScroll={measure}
        className="flex flex-col gap-3 flex-1 overflow-y-auto overflow-x-hidden pr-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {allowedGroups(permissions).map((group, gi) => (
          <div key={group.label || `g${gi}`}>
            {group.label && (
              <p className={`px-4 mb-1 text-[10px] font-bold tracking-[0.12em] uppercase text-surface-variant/50 whitespace-nowrap ${railBlock}`}>
                {group.label}
              </p>
            )}
            <ul className="flex flex-col gap-0.5">
              {group.items.map((item) => (
                <li key={item.key}>
                  <button
                    onClick={() => onNav(item.key)}
                    aria-current={tab === item.key ? 'page' : undefined}
                    title={collapsible ? item.label : undefined}
                    className={`w-full flex items-center gap-3 px-4 py-2 rounded-lg cursor-pointer transition-colors ${
                      collapsible ? 'md:px-0 md:justify-center md:group-hover:px-4 md:group-hover:justify-start' : ''
                    } ${
                      tab === item.key
                        ? 'bg-primary-container text-on-primary-container font-semibold'
                        : 'text-surface-variant hover:text-surface-bright hover:bg-surface-container-highest/10'
                    }`}
                  >
                    <span className="relative shrink-0">
                      <Icon name={item.icon} filled={tab === item.key} className="text-[20px]!" />
                      {/* On the collapsed rail a label-less badge would vanish,
                          so show a small dot on the icon instead. */}
                      {collapsible && badges[item.key] > 0 && (
                        <span className="md:group-hover:hidden absolute -top-1 -right-1 w-2 h-2 bg-error rounded-full" />
                      )}
                    </span>
                    <span className={`text-[15px] whitespace-nowrap ${railText}`}>{item.label}</span>
                    {badges[item.key] > 0 && (
                      <span className={`ml-auto min-w-[20px] h-5 px-1.5 bg-error text-on-error text-xs font-bold rounded-full flex items-center justify-center ${railFlex}`}>
                        {badges[item.key]}
                      </span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
        <div
          aria-hidden="true"
          className={`pointer-events-none absolute bottom-0 left-0 right-0 h-8 bg-gradient-to-t from-inverse-surface to-transparent transition-opacity ${
            moreBelow ? 'opacity-100' : 'opacity-0'
          }`}
        />
      </div>
      <div className="mt-4 pt-3 border-t border-outline-variant/20 shrink-0">
        {me && (
          <div className={`px-4 pb-2 overflow-hidden ${railBlock}`}>
            <p className="text-sm text-surface-bright font-medium truncate">{me.fullName || me.username}</p>
            <p className="text-[10px] font-semibold tracking-wider uppercase text-surface-variant/60 whitespace-nowrap">
              {me.role === 'OWNER' ? 'Owner' : me.role === 'MANAGER' ? 'Manager'
                : me.role === 'ACCOUNTANT' ? 'Accountant' : me.role === 'SUPPORT' ? 'Support' : me.role}
              {me.breakGlass ? ' · fallback login' : ''}
            </p>
          </div>
        )}
        <button
          onClick={onLogout}
          title={collapsible ? 'Logout' : undefined}
          className={`w-full flex items-center gap-3 px-4 py-2.5 text-surface-variant hover:text-surface-bright hover:bg-surface-container-highest/10 rounded-lg cursor-pointer transition-colors ${
            collapsible ? 'md:px-0 md:justify-center md:group-hover:px-4 md:group-hover:justify-start' : ''
          }`}
        >
          <Icon name="logout" className="text-[20px]! shrink-0" />
          <span className={`text-base whitespace-nowrap ${railText}`}>Logout</span>
        </button>
      </div>
    </div>
  )
}

/* Notification bell: technician payout requests needing admin action */
function PayoutBell({ auth }) {
  const [open, setOpen] = useState(false)
  const [payouts, setPayouts] = useState([])

  const load = () => api('/admin/payouts', { auth }).then(setPayouts).catch(() => {})
  useEffect(() => {
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const pending = payouts.filter((p) => p.status === 'REQUESTED')

  async function act(id, status) {
    await api(`/admin/payouts/${id}/status`, { method: 'PATCH', auth, body: { status } }).catch(() => {})
    load()
  }

  return (
    <div className="relative">
      <button
        onClick={() => setOpen(!open)}
        aria-label={`Notifications: ${pending.length} pending payout requests`}
        className="relative p-2 text-on-surface-variant hover:text-primary hover:bg-surface-container transition-colors rounded-full cursor-pointer"
      >
        <Icon name="notifications" filled={pending.length > 0} />
        {pending.length > 0 && (
          <span className="absolute top-0.5 right-0.5 min-w-[18px] h-[18px] px-1 bg-error text-on-error text-[11px] font-bold rounded-full flex items-center justify-center">
            {pending.length}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-2 w-80 bg-surface-container-lowest border border-outline-variant rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)] z-50 overflow-hidden">
          <div className="p-3 border-b border-outline-variant/30 bg-surface-container-low flex justify-between items-center">
            <span className="text-xs font-bold uppercase tracking-wider text-on-surface-variant">Payout Requests</span>
            <span className="text-xs text-on-surface-variant">{pending.length} pending</span>
          </div>
          <div className="max-h-80 overflow-y-auto">
            {pending.map((p) => (
              <div key={p.id} className="p-3 border-b border-outline-variant/20">
                <div className="flex justify-between items-start mb-1">
                  <span className="text-sm font-semibold text-on-surface capitalize">{p.technician}</span>
                  <span className="text-sm font-bold text-primary tabular-nums">{fmtKES(p.amount)}</span>
                </div>
                <p className="text-xs text-on-surface-variant mb-2">
                  {relativeTime(p.createdAt)}{p.note ? ` · ${p.note}` : ''}
                </p>
                <div className="flex gap-2">
                  <button onClick={() => act(p.id, 'PAID')} className="flex-1 px-3 py-1.5 rounded-md border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
                    Mark Paid
                  </button>
                  <button onClick={() => act(p.id, 'REJECTED')} className="flex-1 px-3 py-1.5 rounded-lg border border-error text-error text-xs font-semibold hover:bg-error/5 transition-colors cursor-pointer">
                    Reject
                  </button>
                </div>
              </div>
            ))}
            {pending.length === 0 && (
              <p className="p-4 text-sm text-on-surface-variant text-center">No pending payout requests.</p>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

const TAB_TITLES = {
  overview: 'Overview',
  active: 'Live Sessions',
  leads: 'Leads',
  agents: 'Agents & Batches',
  equipment: 'Equipment',
  analytics: 'Analytics',
  outbox: 'Outbox',
  fiber: 'Fiber Map',
  staff: 'Staff Logins',
  ledger: 'Customer Ledger',
  plans: 'Plans',
  vouchers: 'Vouchers',
  subscribers: 'Subscribers',
  payments: 'Payments',
  paybill: 'PayBill',
  assurance: 'Revenue Guard',
  finance: 'Billing',
  routers: 'Routers',
  devices: 'Devices',
  radius: 'RADIUS',
  retention: 'Keeping customers',
  branches: 'Branches',
  support: 'Tickets',
  maintenance: 'Maintenance',
  messages: 'Team Chat',
  team: 'Team',
  promos: 'Promotions & Branding',
  audit: 'Audit Log',
  health: 'System Health',
  settings: 'Settings',
  subscription: 'Plan & billing',
  refer: 'Refer & Earn',
}

/* Plan & billing — what this ISP owes Zidi this month. Usage-based: a cut of
   hotspot takings + a flat fee per fixed-line customer, free on a quiet month. */
function SubscriptionPage({ auth }) {
  const [b, setB] = useState(null)
  const [failed, setFailed] = useState(false)

  const [pay, setPay] = useState(null) // this month's platform invoice, from the control plane
  const [phone, setPhone] = useState('')
  const [paying, setPaying] = useState(false)
  const [payErr, setPayErr] = useState(null)
  const payPoll = useRef(null)

  useEffect(() => {
    api('/admin/platform-billing', { auth }).then(setB).catch(() => setFailed(true))
    api('/admin/platform-billing/payment-status', { auth }).then(setPay).catch(() => {})
    return () => clearInterval(payPoll.current)
  }, [auth])

  async function startPay() {
    setPayErr(null)
    const p = phone.replace(/\D/g, '')
    if (!/^254\d{9}$/.test(p)) { setPayErr('Enter your M-Pesa number as 2547XXXXXXXX.'); return }
    setPaying(true)
    try {
      const r = await api('/admin/platform-billing/pay', { method: 'POST', auth, body: { phone: p } })
      setPay(r)
      clearInterval(payPoll.current)
      payPoll.current = setInterval(async () => {
        const s = await api('/admin/platform-billing/payment-status', { auth }).catch(() => null)
        if (s) setPay(s)
        if (s && (s.status === 'PAID' || s.status === 'FAILED')) clearInterval(payPoll.current)
      }, 3000)
    } catch (e) {
      setPayErr(e.message)
    } finally {
      setPaying(false)
    }
  }

  if (failed) return <PageHeader title="Plan & billing" subtitle="Couldn't load your bill right now." />
  if (!b) {
    return (
      <div>
        <PageHeader title="Plan & billing" subtitle="Your Zidi platform bill." />
        <div className="animate-pulse bg-surface-container-high rounded-xl h-40" />
      </div>
    )
  }

  const Card = ({ label, value, sub, tone }) => (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5">
      <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{label}</p>
      <p className={`text-2xl font-bold tabular-nums mt-1 ${tone || 'text-on-surface'}`}>{value}</p>
      {sub && <p className="text-xs text-on-surface-variant mt-1">{sub}</p>}
    </div>
  )

  return (
    <div>
      <PageHeader
        title="Plan & billing"
        subtitle="Your Zidi platform bill — a small cut of hotspot takings plus a flat fee per fixed-line customer. Free on a quiet month."
      />

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        <Card label="This month's earnings" value={fmtKES(b.totalEarnings)} sub={`Hotspot ${fmtKES(b.hotspotRevenue)} · PPPoE ${fmtKES(b.pppoeRevenue)}`} />
        <Card label="Fixed-line customers" value={fmtNum(b.activePppoeUsers)} sub={`Billed ${money(b.pppoePerUser)} each`} />
        <Card
          label="Amount due"
          value={b.free ? 'Free' : fmtKES(b.amountDue)}
          tone={b.free ? 'text-secondary' : 'text-primary'}
          sub={
            b.free
              ? (b.inTrial
                  ? `${b.trialDaysLeft} day${b.trialDaysLeft === 1 ? '' : 's'} left in your free trial`
                  : `Under ${money(b.freeThreshold)} this month`)
              : `${b.daysLeftInMonth} days left in the month`
          }
        />
      </div>

      <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5 max-w-2xl">
        <CardLabelLine>How this month is billed</CardLabelLine>
        {b.free ? (
          b.inTrial ? (
            <p className="text-sm text-on-surface-variant mt-2">
              You're in your first <strong className="text-on-surface">{b.trialDays} days</strong> — the platform is{' '}
              <strong className="text-secondary">free for {b.trialDaysLeft} more day{b.trialDaysLeft === 1 ? '' : 's'}</strong>,
              whatever you earn. After that, only months where you earn {money(b.freeThreshold)} or more are charged.
            </p>
          ) : (
            <p className="text-sm text-on-surface-variant mt-2">
              You earned less than <strong className="text-on-surface">{money(b.freeThreshold)}</strong> this
              month, so the platform is <strong className="text-secondary">free</strong>. Zidi only charges once you're earning.
            </p>
          )
        ) : (
          <dl className="mt-3 space-y-2.5">
            <div className="flex justify-between items-baseline gap-3">
              <dt className="text-sm text-on-surface-variant">Hotspot — {b.hotspotRatePercent}% of {fmtKES(b.hotspotRevenue)}</dt>
              <dd className="text-sm font-semibold tabular-nums">{fmtKES(b.hotspotFee)}</dd>
            </div>
            <div className="flex justify-between items-baseline gap-3">
              <dt className="text-sm text-on-surface-variant">Fixed-line — {money(b.pppoePerUser)} × {fmtNum(b.activePppoeUsers)} customers</dt>
              <dd className="text-sm font-semibold tabular-nums">{fmtKES(b.pppoeFee)}</dd>
            </div>
            <div className="flex justify-between items-baseline gap-3 pt-2.5 border-t border-outline-variant">
              <dt className="text-sm font-semibold">Total due for {b.month}</dt>
              <dd className="text-lg font-bold tabular-nums text-primary">{fmtKES(b.amountDue)}</dd>
            </div>
          </dl>
        )}
        <p className="text-xs text-on-surface-variant mt-4">
          Your first {b.trialDays} days are free. After that, billing runs monthly: hotspot is 2.5% of what you collect,
          {/* Deliberately still KES: this is what Zidi charges the ISP, not what
              the ISP charges its own customers. Converting it to the operator's
              currency would misquote the platform's own price. */}
          fixed-line is a flat KES 25 per active customer, and any month you earn under {money(b.freeThreshold)} is free.
        </p>
      </div>

      {!b.free && Number(b.amountDue) > 0 && (
        <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5 max-w-2xl mt-4">
          <CardLabelLine>Pay this month</CardLabelLine>
          {pay?.status === 'PAID' ? (
            <p className="text-sm text-secondary mt-3 flex items-center gap-2">
              <Icon name="check_circle" filled className="text-[18px]!" />
              Paid for {b.month}{pay.mpesaReceipt ? ` — receipt ${pay.mpesaReceipt}` : ''}.
            </p>
          ) : pay?.status === 'UNCONFIGURED' ? (
            <p className="text-sm text-on-surface-variant mt-3">
              Online payment isn't set up on this server yet — pay <strong className="text-on-surface">{fmtKES(b.amountDue)}</strong> to Zidi directly, or contact support.
            </p>
          ) : (
            <>
              <p className="text-sm text-on-surface-variant mt-3">
                Pay <strong className="text-on-surface">{fmtKES(b.amountDue)}</strong> for {b.month} by {payBrand()} — we&rsquo;ll send a prompt to your phone.
              </p>
              <div className="flex flex-col sm:flex-row gap-2 mt-3 max-w-md">
                <input
                  className={INPUT_CLS + ' flex-1'}
                  placeholder={`${payBrand()} number (${phoneExample()})`}
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  disabled={pay?.status === 'PENDING'}
                />
                <button
                  onClick={startPay}
                  disabled={paying || pay?.status === 'PENDING'}
                  className="bg-primary text-on-primary text-sm font-semibold px-4 h-11 rounded-md whitespace-nowrap disabled:opacity-60 cursor-pointer hover:brightness-105 transition"
                >
                  {pay?.status === 'PENDING' ? 'Awaiting your PIN…' : paying ? 'Sending…' : `Pay ${fmtKES(b.amountDue)}`}
                </button>
              </div>
              {pay?.status === 'PENDING' && <p className="text-xs text-on-surface-variant mt-2">Check your phone and enter {payPinPhrase()} to complete the payment…</p>}
              {pay?.status === 'FAILED' && <p className="text-xs text-error mt-2">{pay.detail || 'Payment not completed.'} Please try again.</p>}
              {payErr && <p className="text-xs text-error mt-2">{payErr}</p>}
            </>
          )}
        </div>
      )}
    </div>
  )
}

/* Refer & Earn — invite another ISP; when they sign up and pay, you get a
   discount. Referral tracking is a follow-up, so the counters read zero. */
function ReferEarnPage({ auth }) { // eslint-disable-line no-unused-vars
  const [copied, setCopied] = useState(false)
  const signupUrl = 'https://zidi.co.ke'

  function invite() {
    const subject = encodeURIComponent('Try Zidi for your ISP')
    const body = encodeURIComponent(`I'm using Zidi to run my ISP billing — hotspot vouchers, M-Pesa, MikroTik and PPPoE from one dashboard. Start yours here: ${signupUrl}`)
    window.location.href = `mailto:?subject=${subject}&body=${body}`
  }
  function copyLink() {
    navigator.clipboard.writeText(signupUrl).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  const Card = ({ label, value, sub }) => (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5">
      <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{label}</p>
      <p className="text-2xl font-bold tabular-nums mt-1">{value}</p>
      <p className="text-xs text-on-surface-variant mt-1">{sub}</p>
    </div>
  )

  return (
    <div>
      <div className="flex items-start justify-between gap-4 flex-wrap mb-6">
        <PageHeader
          title="Refer an ISP, earn a discount"
          subtitle="Invite another ISP to Zidi. When they sign up and pay their first month, you get 10% off your next bill — up to 100% off."
        />
        <div className="flex gap-2">
          <button onClick={copyLink} className="h-11 px-4 rounded-lg border border-outline-variant text-on-surface text-sm font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
            {copied ? 'Link copied' : 'Copy link'}
          </button>
          <button onClick={invite} className="h-11 px-5 rounded-lg bg-primary text-on-primary text-sm font-semibold flex items-center gap-2 hover:brightness-110 transition cursor-pointer">
            <Icon name="person_add" className="text-[18px]!" /> Invite an ISP
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <Card label="Invites sent" value="0" sub="all time" />
        <Card label="Pending" value="0" sub="not yet signed up" />
        <Card label="Converted" value="0" sub="referred ISP paid" />
        <Card label="Pending discount" value="0%" sub="on your next bill" />
      </div>

      <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-8 text-center">
        <Icon name="redeem" className="text-[40px]! text-on-surface-variant/40" />
        <p className="mt-2 text-on-surface-variant">No referrals yet — invite an ISP to get started.</p>
      </div>
    </div>
  )
}

/* Small caps label used on the billing card. */
function CardLabelLine({ children }) {
  return <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{children}</p>
}

/* "Install app" — the admin is a normal web app, so installing it is the
   browser's add-to-home-screen. A short how-to per platform is the honest,
   dependency-free version until we ship a full PWA manifest. */
function InstallAppModal({ onClose }) {
  const Step = ({ icon, children }) => (
    <div className="flex items-start gap-3">
      <Icon name={icon} className="text-primary text-[20px]! mt-0.5" />
      <p className="text-sm text-on-surface-variant">{children}</p>
    </div>
  )
  return (
    <div className="fixed inset-0 z-[60] bg-black/60 flex items-center justify-center p-5" onClick={onClose}>
      <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-6 w-full max-w-sm" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center gap-2 mb-4">
          <Icon name="install_mobile" className="text-primary" />
          <h2 className="text-lg font-semibold text-on-surface">Install the app</h2>
        </div>
        <p className="text-sm text-on-surface-variant mb-4">
          Add Zidi to your home screen or desktop for a full-screen, app-like experience.
        </p>
        <div className="space-y-3">
          <Step icon="phone_iphone">On iPhone/iPad: tap <strong className="text-on-surface">Share</strong>, then <strong className="text-on-surface">Add to Home Screen</strong>.</Step>
          <Step icon="android">On Android/Chrome: menu <strong className="text-on-surface">⋮</strong>, then <strong className="text-on-surface">Install app</strong>.</Step>
          <Step icon="computer">On desktop: the <strong className="text-on-surface">install icon</strong> in the address bar.</Step>
        </div>
        <button onClick={onClose} className="mt-6 w-full h-11 rounded-lg bg-primary text-on-primary font-semibold cursor-pointer hover:brightness-110 transition">
          Got it
        </button>
      </div>
    </div>
  )
}

/* Resolves the theme choice (light/dark/system) to what actually renders,
   following the OS when the choice is "system". */
function useResolvedTheme(choice) {
  const [sys, setSys] = useState(() =>
    window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const on = (e) => setSys(e.matches ? 'dark' : 'light')
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return choice === 'system' ? sys : choice
}

/* The signed-in owner's account menu, hung off the top-bar avatar: who they
   are, the theme, the account and workspace shortcuts, and sign out.
   "Password & security" is where they manage 2FA and passkeys. */
function ProfileMenu({ me, onNav, onLogout, onInstall, theme, resolvedTheme, onTheme }) {
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return
    const onDoc = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const name = me?.fullName || me?.username || 'Account'
  const email = me?.email || (me?.username?.includes('@') ? me.username : '')
  const initial = (name || 'A').trim().charAt(0).toUpperCase()
  const perms = me?.permissions || []
  const go = (key) => { setOpen(false); onNav(key) }

  const Item = ({ icon, label, onClick }) => (
    <button onClick={onClick} className="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm text-on-surface hover:bg-surface-container-high transition-colors cursor-pointer text-left">
      <Icon name={icon} className="text-[18px]! text-on-surface-variant" /> {label}
    </button>
  )

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        aria-label="Account menu"
        className="w-9 h-9 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center font-semibold border border-outline-variant cursor-pointer hover:brightness-110 transition"
      >
        {initial}
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-72 bg-surface-container-lowest border border-outline-variant rounded-xl shadow-xl z-50 overflow-hidden">
          <div className="flex items-center gap-3 p-4 border-b border-outline-variant">
            <div className="w-10 h-10 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center font-semibold shrink-0">{initial}</div>
            <div className="min-w-0">
              <p className="text-sm font-semibold text-on-surface truncate">{name}</p>
              {email && <p className="text-xs text-on-surface-variant truncate">{email}</p>}
            </div>
          </div>

          {/* Theme */}
          <div className="p-3 border-b border-outline-variant flex flex-col items-center gap-2">
            <ThemeSwitcher isDark={resolvedTheme === 'dark'} onToggle={onTheme} />
            {/* The switch is two-state and the setting is three. "System" is
                kept beside it rather than dropped, because a laptop that dims
                itself at dusk is a real preference and a nicer toggle is not a
                reason to take it away. */}
            <button
              type="button"
              onClick={() => onTheme('system')}
              className={`text-xs font-medium px-3 py-1 rounded-full cursor-pointer transition-colors ${
                theme === 'system'
                  ? 'bg-primary text-on-primary'
                  : 'text-on-surface-variant hover:bg-surface-container-high'
              }`}
            >
              {theme === 'system' ? 'Following your system' : 'Follow my system'}
            </button>
          </div>

          <div className="px-1.5 py-1.5">
            <p className="px-3 pt-1 pb-1 text-[11px] font-semibold tracking-wider uppercase text-on-surface-variant">Account</p>
            <Item icon="account_circle" label="Profile" onClick={() => go('settings/profile')} />
            <Item icon="lock" label="Password & security" onClick={() => go('settings/security')} />
            <Item icon="install_mobile" label="Install app" onClick={() => { setOpen(false); onInstall() }} />
          </div>

          <div className="border-t border-outline-variant px-1.5 py-1.5">
            <p className="px-3 pt-1 pb-1 text-[11px] font-semibold tracking-wider uppercase text-on-surface-variant">Workspace</p>
            {perms.includes('SETTINGS') && <Item icon="settings" label="Settings" onClick={() => go('settings')} />}
            {perms.includes('STAFF') && <Item icon="group" label="Staff" onClick={() => go('staff')} />}
            {perms.includes('SETTINGS') && <Item icon="credit_card" label="Plan & billing" onClick={() => go('subscription')} />}
            <Item icon="volunteer_activism" label="Refer & Earn" onClick={() => go('refer')} />
            {perms.includes('SETTINGS') && <Item icon="history" label="Audit log" onClick={() => go('audit')} />}
            <Item icon="feedback" label="Feedback" onClick={() => { setOpen(false); window.location.href = 'mailto:feedback@zidi.co.ke?subject=' + encodeURIComponent('Zidi feedback') }} />
          </div>

          <div className="border-t border-outline-variant px-1.5 py-1.5">
            <Item icon="logout" label="Sign out" onClick={() => { setOpen(false); onLogout() }} />
          </div>
        </div>
      )}
    </div>
  )
}

function Shell({ auth, onLogout }) {
  // The open section lives in the URL rather than in state, so a page can
  // be bookmarked or shared, the browser's back button works, and a
  // refresh returns you where you were instead of to the overview.
  const navigate = useNavigate()
  const location = useLocation()
  // The shell is mounted under /admin for staff and /demo for prospects, so the
  // base cannot be hardcoded: derived from the URL, a nav click inside the demo
  // used to navigate to /admin and drop the visitor straight out of it.
  const base = location.pathname.startsWith('/demo') ? '/demo' : '/admin'
  const tab = location.pathname.slice(base.length).replace(/^\//, '').split('/')[0] || 'overview'
  const setTab = (key) => navigate(key === 'overview' ? base : `${base}/${key}`)
  const [drawer, setDrawer] = useState(false)
  const [unreadMessages, setUnreadMessages] = useState(0)
  const [installOpen, setInstallOpen] = useState(false)
  const [theme, setTheme] = useState(() => localStorage.getItem('adminTheme') || 'system')
  const resolvedTheme = useResolvedTheme(theme)
  useEffect(() => { localStorage.setItem('adminTheme', theme) }, [theme])
  // Null until known, so nothing is hidden or shown on a guess.
  const [me, setMe] = useState(null)

  useEffect(() => {
    api('/admin/staff/me', { auth })
      .then(setMe)
      .catch((err) => {
        // A stale or expired session (or the credential format changing under
        // an old tab) must drop back to the login rather than leave a broken
        // half-loaded shell. Anything else — e.g. an older backend missing the
        // endpoint — falls back to full permissions so the rail still renders.
        if (err.status === 401) {
          onLogout()
          return
        }
        setMe({
          username: 'admin',
          role: 'OWNER',
          permissions: ['STAFF', 'SETTINGS', 'FINANCE', 'CUSTOMERS', 'NETWORK', 'OUTREACH', 'SELL'],
          breakGlass: false,
        })
      })
  }, [auth])

  // If the current tab is not open to this role, fall back to the overview.
  useEffect(() => {
    if (!me) return
    const item = NAV.find((i) => i.key === tab)
    if (item?.need && !me.permissions.includes(item.need)) {
      navigate(base, { replace: true })
    }
  }, [me, tab, base])

  useEffect(() => {
    const load = () =>
      api('/admin/messages/channels', { auth })
        .then((cs) => setUnreadMessages(cs.reduce((a, c) => a + (c.unread || 0), 0)))
        .catch(() => {})
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [auth, tab])

  function nav(key) {
    setTab(key)
    setDrawer(false)
    window.scrollTo(0, 0)
  }

  // An unknown section in the URL should not render a blank page.
  const known = NAV.some((i) => i.key === tab)

  const badges = { messages: unreadMessages }
  const permissions = me?.permissions

  const demo = !!me?.demo

  return (
    <div className="admin-theme bg-background text-on-background min-h-screen" data-theme={resolvedTheme}>
      {/* Read-only demo strip, above everything. */}
      {demo && (
        <div className="fixed top-0 inset-x-0 h-8 z-50 bg-primary text-on-primary flex items-center justify-center gap-2 px-4 text-xs sm:text-sm font-semibold">
          <Icon name="visibility" className="text-[16px]!" />
          <span>Demo — read only. Changes are disabled.</span>
          <a href="/start" className="underline underline-offset-2 hidden sm:inline hover:opacity-80">Create your own account →</a>
        </div>
      )}

      {/* Desktop sidebar — a slim icon rail that expands to reveal labels
          while hovered. It's fixed, so the expansion overlays the content
          rather than reflowing it; the main margin stays at the rail width. */}
      <nav className={`admin-rail group w-16 hover:w-64 fixed left-0 ${demo ? 'top-8 h-[calc(100vh-2rem)]' : 'top-0 h-screen'} bg-inverse-surface shadow-md hidden md:flex flex-col z-40 overflow-hidden transition-[width] duration-200 ease-out`}>
        <SidebarContent tab={tab} onNav={nav} onLogout={onLogout} badges={badges} permissions={permissions} me={me} collapsible />
      </nav>

      {/* Mobile drawer */}
      {drawer && (
        <div className="md:hidden fixed inset-0 z-50 flex">
          <div className="admin-rail w-64 bg-inverse-surface h-full shadow-xl">
            <SidebarContent tab={tab} onNav={nav} onLogout={onLogout} badges={badges} permissions={permissions} me={me} />
          </div>
          <div className="flex-1 bg-on-background/50" onClick={() => setDrawer(false)}></div>
        </div>
      )}

      {/* Top bar */}
      <header className={`fixed ${demo ? 'top-8' : 'top-0'} right-0 w-full md:w-[calc(100%-4rem)] h-16 bg-surface shadow-sm z-30 flex justify-between items-center px-5 md:px-6`}>
        <div className="flex items-center gap-3">
          <button className="md:hidden p-2 -ml-2 text-on-surface cursor-pointer" onClick={() => setDrawer(true)} aria-label="Open menu">
            <Icon name="menu" />
          </button>
          <img
            src={resolvedTheme === 'light' ? zidiLogoDark : zidiLogo}
            alt="Zidi"
            className="h-6 w-auto object-contain hidden sm:block"
          />
          <span className="text-on-surface-variant hidden sm:inline">/</span>
          <span className="text-lg font-bold text-primary">{TAB_TITLES[tab]}</span>
        </div>
        <div className="flex items-center gap-2">
          <PayoutBell auth={auth} />
          <ProfileMenu me={me} onNav={nav} onLogout={onLogout} theme={theme} resolvedTheme={resolvedTheme} onTheme={setTheme}
            onInstall={async () => { const native = await triggerInstall(); if (!native) setInstallOpen(true) }} />
        </div>
      </header>

      {installOpen && <InstallAppModal onClose={() => setInstallOpen(false)} />}

      {/* Content */}
      {/* Matches the fluid top bar above, which is calc(100%-16rem). A cap
          below that left the header running wider than the content under it.
          2400px only bites on an ultrawide, where full-bleed table rows
          would be a worse problem than a margin. */}
      <main className={`md:ml-16 ${demo ? 'pt-32' : 'pt-24'} px-5 md:px-8 pb-8 max-w-[2400px]`}>
        {tab === 'overview' && <Overview auth={auth} onNav={nav} />}
        {tab === 'active' && <ActiveUsersPage auth={auth} />}
        {tab === 'leads' && <LeadsPage auth={auth} />}
        {tab === 'agents' && <AgentsPage auth={auth} />}
        {tab === 'equipment' && <EquipmentPage auth={auth} />}
        {tab === 'analytics' && <AnalyticsPage auth={auth} />}
        {tab === 'outbox' && <CommunicationsPage auth={auth} />}
        {tab === 'fiber' && <FiberPage auth={auth} />}
        {tab === 'cpe' && <CpePage auth={auth} />}
        {tab === 'usage' && <UsagePage auth={auth} />}
        {tab === 'backups' && <RouterBackupsPage auth={auth} />}
        {tab === 'staff' && <StaffPage auth={auth} me={me} />}
        {tab === 'ledger' && <LedgerPage auth={auth} />}
        {tab === 'plans' && <Plans auth={auth} />}
        {tab === 'vouchers' && <VouchersPage auth={auth} />}
        {tab === 'subscribers' && <Subscribers auth={auth} />}
        {tab === 'payments' && <Payments auth={auth} />}
        {tab === 'paybill' && <PayBillPage auth={auth} />}
        {tab === 'assurance' && <RevenueAuditPage auth={auth} />}
        {tab === 'finance' && <FinancePage auth={auth} />}
        {tab === 'routers' && <RoutersPage auth={auth} />}
        {tab === 'devices' && <DevicesPage auth={auth} />}
        {tab === 'radius' && <RadiusPage auth={auth} />}
        {tab === 'retention' && <RetentionPage auth={auth} />}
        {tab === 'branches' && <BranchesPage auth={auth} />}
        {tab === 'promos' && <BrandingPage auth={auth} />}
        {tab === 'audit' && <AuditLogPage auth={auth} />}
        {tab === 'health' && <SystemHealthPage auth={auth} />}
        {tab === 'support' && <Support auth={auth} />}
        {tab === 'maintenance' && <Maintenance auth={auth} />}
        {tab === 'messages' && <Messages auth={auth} />}
        {tab === 'team' && <Team auth={auth} />}
        {tab === 'settings' && (
          <SettingsHub auth={auth} me={me} mikrotikSection={<Settings auth={auth} />} />
        )}
        {tab === 'subscription' && <SubscriptionPage auth={auth} />}
        {tab === 'refer' && <ReferEarnPage auth={auth} />}
      </main>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Overview                                                            */
/* ------------------------------------------------------------------ */

function RevenueChart({ payments, days }) {
  const series = useMemo(() => {
    const buckets = Array.from({ length: days }, () => 0)
    const now = new Date()
    for (const p of payments) {
      if (p.status !== 'SUCCESS') continue
      const age = Math.floor((now - new Date(p.createdAt)) / 86400000)
      if (age >= 0 && age < days) buckets[days - 1 - age] += Number(p.amount)
    }
    return buckets
  }, [payments, days])

  const max = Math.max(...series, 1)
  const n = series.length
  const pts = series.map((v, i) => [(i / (n - 1)) * 100, 46 - (v / max) * 40])
  const line = pts.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ')
  const area = `M0,50 L${line.replace(/ /g, ' L')} L100,50 Z`
  const total = series.reduce((a, b) => a + b, 0)

  return (
    <div className="flex-1 relative min-h-[200px] flex flex-col">
      <p className="text-sm text-on-surface-variant mb-2">
        {total > 0 ? `${fmtKES(total)} collected in the last ${days} days` : `No successful payments in the last ${days} days yet`}
      </p>
      <div className="flex-1 relative flex items-end border-b border-l border-outline-variant/30">
        <svg className="w-full h-full text-primary opacity-20 absolute inset-0" preserveAspectRatio="none" viewBox="0 0 100 50">
          <path d={area} fill="currentColor" />
        </svg>
        <svg className="w-full h-full absolute inset-0 text-primary" preserveAspectRatio="none" viewBox="0 0 100 50">
          <polyline points={line} fill="none" stroke="currentColor" strokeLinejoin="round" strokeWidth="0.8" />
        </svg>
      </div>
    </div>
  )
}

function KpiCard({ label, icon, iconClass, value, format = (v) => v, accent, wide, index = 0, trend }) {
  // Some cards show a duration or a dash rather than a number; counting up
  // to those would render 0, so they pass straight through.
  const numeric = value !== '' && value !== null && value !== undefined && !Number.isNaN(Number(value))
  const counted = useCountUp(numeric ? value : 0)
  const shown = numeric ? counted : value
  return (
    <div
      className={`bg-surface-container-lowest rounded-lg p-4  fade-up ${
        accent ? `border-l-2 ${accent}` : ''
      } ${wide ? 'xl:col-span-2' : 'xl:col-span-1'}`}
      style={{ animationDelay: `${index * 70}ms` }}
    >
      <div className="flex justify-between items-start mb-2">
        <CardLabel>{label}</CardLabel>
        <Icon name={icon} className={iconClass} />
      </div>
      <div className={`${wide ? 'text-[26px]' : 'text-[22px]'} leading-tight font-semibold text-on-surface tabular-nums`}>{format(shown)}</div>
      {trend != null && (
        <div className={`flex items-center gap-1 text-sm mt-2 ${trend >= 0 ? 'text-secondary' : 'text-error'}`}>
          <Icon name={trend >= 0 ? 'trending_up' : 'trending_down'} className="text-[16px]!" />
          <span>{trend >= 0 ? '+' : ''}{trend}% vs previous 30 days</span>
        </div>
      )}
    </div>
  )
}


const SEVERITY = {
  critical: { dot: 'bg-error', text: 'text-error' },
  warning: { dot: 'bg-[#FDBF2D]', text: 'text-[#FDBF2D]' },
  info: { dot: 'bg-on-surface-variant', text: 'text-on-surface-variant' },
}

/** Panel shell. One radius, one border, no shadows — depth comes from the
 *  panel sitting a step lighter than the canvas. */
function Panel({ title, action, children, className = '' }) {
  return (
    <section className={`bg-surface-container-lowest border border-outline-variant rounded-lg ${className}`}>
      {title && (
        <header className="flex items-center justify-between gap-3 px-4 h-11 border-b border-outline-variant">
          <h3 className="text-[13px] font-semibold text-on-surface">{title}</h3>
          {action}
        </header>
      )}
      {children}
    </section>
  )
}

/* First-run "Set up your account" checklist. Fetches step done-states from
   /admin/onboarding (computed from real data), shows a progress bar, the next
   step as a prominent CTA, and done/remaining chips. Self-hides once every
   step is done or the operator dismisses it (remembered per browser). */
function OnboardingCard({ auth, onNav }) {
  const [ob, setOb] = useState(null)
  const [hidden, setHidden] = useState(() => localStorage.getItem('onboardingDismissed') === '1')
  const [hotspotOnly, setHotspotOnly] = useState(() => localStorage.getItem('onboardingHotspotOnly') === '1')

  useEffect(() => {
    let alive = true
    api('/admin/onboarding', { auth }).then((d) => alive && setOb(d)).catch(() => {})
    return () => { alive = false }
  }, [auth])

  if (hidden || !ob) return null

  // A hotspot-only ISP has no PPPoE router, so let them mark that step done
  // rather than leaving the card stuck at 5/6 forever (per-browser, like
  // the dismiss). Recompute the counts from the adjusted steps.
  const steps = ob.steps.map((s) => (s.key === 'router' && hotspotOnly ? { ...s, done: true } : s))
  const completed = steps.filter((s) => s.done).length
  const total = steps.length
  if (completed === total) return null

  const pct = Math.round((completed / total) * 100)
  const next = steps.find((s) => !s.done)
  const done = steps.filter((s) => s.done)
  const remaining = steps.filter((s) => !s.done && s !== next)
  const routerPending = steps.some((s) => s.key === 'router' && !s.done)

  return (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-base font-semibold text-on-surface">Set up your account</h3>
          <p className="text-xs text-on-surface-variant mt-0.5">
            <span className="font-semibold text-on-surface">{completed} of {total}</span> done · {total - completed} step{total - completed === 1 ? '' : 's'} left
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="hidden sm:block w-40">
            <div className="h-2 rounded-full bg-surface-container-high overflow-hidden">
              <div className="h-full bg-primary rounded-full transition-all duration-500" style={{ width: pct + '%' }} />
            </div>
          </div>
          <span className="text-sm font-bold tabular-nums text-on-surface">{pct}%</span>
          <button onClick={() => { localStorage.setItem('onboardingDismissed', '1'); setHidden(true) }} className="text-on-surface-variant hover:text-on-surface p-1 rounded-full hover:bg-surface-container cursor-pointer" aria-label="Dismiss setup">
            <Icon name="close" className="text-[18px]!" />
          </button>
        </div>
      </div>

      {next && (
        <button onClick={() => onNav(next.tab)} className="mt-4 w-full flex items-center justify-between gap-3 bg-primary text-on-primary rounded-lg px-4 py-3 text-left hover:brightness-105 active:scale-[0.99] transition cursor-pointer">
          <span>
            <span className="block text-[10px] font-bold tracking-wider uppercase opacity-70">Next step</span>
            <span className="block text-sm font-semibold">{next.label}</span>
          </span>
          <Icon name="arrow_forward" />
        </button>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-2">
        {done.length > 0 && <span className="text-[10px] font-bold tracking-wider uppercase text-on-surface-variant mr-1">Already done</span>}
        {done.map((s) => (
          <span key={s.key} className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full bg-secondary/10 text-secondary text-xs font-medium">
            <Icon name="check" className="text-[14px]!" /> {s.label}
          </span>
        ))}
        {remaining.map((s) => (
          <button key={s.key} onClick={() => onNav(s.tab)} className="inline-flex items-center px-2.5 py-1 rounded-full border border-outline-variant text-on-surface-variant text-xs font-medium hover:bg-surface-container hover:text-on-surface transition-colors cursor-pointer">
            {s.label}
          </button>
        ))}
      </div>

      {routerPending && (
        <p className="mt-3 text-xs text-on-surface-variant">
          Running hotspot-only?{' '}
          <button
            onClick={() => { localStorage.setItem('onboardingHotspotOnly', '1'); setHotspotOnly(true) }}
            className="text-primary font-medium hover:underline cursor-pointer"
          >
            Skip the router step
          </button>
        </p>
      )}
    </div>
  )
}

/* AI ops copilot: grounded, actionable insights (customers about to lapse,
   recent drop-offs, today's take) with one-tap next steps. Deterministic — the
   numbers are real, not generated. Shows only when something's worth acting on. */
function AiCopilotCard({ auth, onNav }) {
  const [data, setData] = useState(null)
  const [busy, setBusy] = useState(false)
  const [note, setNote] = useState(null)

  const load = () => api('/admin/ai/insights', { auth }).then(setData).catch(() => {})
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data || !data.insights) return null
  const actionable = data.insights.filter((i) => i.severity !== 'info')
  if (actionable.length === 0) return null

  const dot = (sev) => sev === 'high' ? 'bg-error' : sev === 'medium' ? 'bg-primary' : 'bg-on-surface-variant'

  async function runAction(insight) {
    if (insight.action === 'remind-lapsing') {
      setBusy(true); setNote(null)
      try {
        const r = await api('/admin/ai/act/remind-lapsing', { method: 'POST', auth })
        setNote(`Reminded ${r.sent} customer${r.sent === 1 ? '' : 's'}.`)
        load()
      } catch (e) {
        setNote(e.message)
      } finally { setBusy(false) }
    } else if (insight.tab) {
      onNav(insight.tab)
    }
  }

  return (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5">
      <div className="flex items-center gap-2 mb-3">
        <Icon name="smart_toy" filled className="text-primary text-[20px]!" />
        <h3 className="text-base font-semibold text-on-surface">Copilot</h3>
        <span className="text-xs text-on-surface-variant">· {data.headline}</span>
      </div>
      <div className="space-y-2.5">
        {data.insights.map((i) => (
          <div key={i.key} className="flex items-center gap-3">
            <span className={`w-2 h-2 rounded-full shrink-0 ${dot(i.severity)}`} />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-on-surface">{i.title}</p>
              <p className="text-xs text-on-surface-variant">{i.detail}</p>
            </div>
            {i.actionLabel && (
              <button
                onClick={() => runAction(i)}
                disabled={busy && i.action === 'remind-lapsing'}
                className="text-xs font-semibold px-3 h-8 rounded-md border border-outline-variant text-on-surface hover:bg-surface-container disabled:opacity-60 cursor-pointer whitespace-nowrap transition-colors"
              >
                {busy && i.action === 'remind-lapsing' ? 'Sending…' : i.actionLabel}
              </button>
            )}
          </div>
        ))}
      </div>
      {note && <p className="text-xs text-secondary mt-3">{note}</p>}
    </div>
  )
}

function Overview({ auth, onNav }) {
  const [data, setData] = useState(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    const load = () =>
      api('/admin/overview', { auth })
        .then((d) => alive && setData(d))
        .catch(() => alive && setFailed(true))
    load()
    // A dashboard that goes stale while you watch it is worse than no
    // dashboard; 30s is often enough to catch a router dropping.
    const t = setInterval(load, 30000)
    return () => { alive = false; clearInterval(t) }
  }, [auth])

  if (failed) {
    return (
      <div className="max-w-md">
        <h2 className="text-2xl font-semibold mb-2">Cannot reach the server</h2>
        <p className="text-sm text-on-surface-variant">
          The backend is not answering. Everything else in here will be empty until it does.
        </p>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-3">
        <Skeleton className="h-40 xl:col-span-2" />
        <Skeleton className="h-40" />
        <Skeleton className="h-72 xl:col-span-2" />
        <Skeleton className="h-72" />
      </div>
    )
  }

  const money = data.money
  const sessions = data.sessions
  const attention = data.attention || []
  const change = money?.changePercent

  return (
    <div className="space-y-3">
      <OnboardingCard auth={auth} onNav={onNav} />
      <AiCopilotCard auth={auth} onNav={onNav} />
      {/* Row one: money is the largest thing on the page, faults sit beside
          it. Sized by importance rather than split into equal boxes. */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-3">
        {money && (
          <Panel className="xl:col-span-2 flex flex-col">
            <div className="flex flex-wrap items-start justify-between gap-6 p-4 pb-3">
              <div>
                <p className="text-[11px] font-semibold tracking-[0.08em] uppercase text-on-surface-variant">
                  Collected today
                </p>
                <p className="font-mono text-[30px] sm:text-[40px] leading-none font-semibold tabular-nums mt-1.5 text-on-surface">
                  {fmtKES(money.today)}
                </p>
                <p className="text-xs mt-2">
                  {change === null || change === undefined ? (
                    <span className="text-on-surface-variant">
                      Nothing yesterday to compare against
                    </span>
                  ) : (
                    <span className={Number(change) >= 0 ? 'text-secondary' : 'text-error'}>
                      {Number(change) >= 0 ? '▲' : '▼'} {Math.abs(Number(change))}% on yesterday
                      <span className="text-on-surface-variant"> · {fmtKES(money.yesterday)} then</span>
                    </span>
                  )}
                </p>
              </div>

              <dl className="flex gap-8 border-l border-outline-variant pl-6">
                <div>
                  <dt className="text-[11px] text-on-surface-variant">Sold today</dt>
                  <dd className="font-mono text-2xl font-semibold tabular-nums mt-0.5">{money.sold}</dd>
                </div>
                {sessions && (
                  <div>
                    <dt className="text-[11px] text-on-surface-variant">Online now</dt>
                    <dd className="font-mono text-2xl font-semibold tabular-nums mt-0.5">{sessions.total}</dd>
                  </div>
                )}
              </dl>
            </div>

            {/* Pinned to the foot of the panel so the card has no dead half
                when it sits beside a taller neighbour. */}
            <div className="mt-auto px-4 pb-3">
              <AreaSparkline
                data={money.series.map((s) => Number(s.amount))}
                labels={money.series.map((s) => s.date)}
                color="var(--color-primary)"
                height={48}
                format={fmtKES}
              />
              <div className="flex justify-between text-[10px] text-on-surface-variant mt-1.5">
                <span>{money.series[0]?.date}</span>
                <span>today</span>
              </div>
            </div>
          </Panel>
        )}

        <Panel title="Needs attention" className={money ? '' : 'xl:col-span-3'}>
          {attention.length === 0 ? (
            <div className="px-4 py-6 flex items-center gap-2.5">
              <span className="w-1.5 h-1.5 rounded-full bg-secondary" />
              <p className="text-sm text-on-surface-variant">Everything is running.</p>
            </div>
          ) : (
            <ul>
              {attention.map((a, i) => (
                <li key={i} className="border-b border-outline-variant last:border-0">
                  <button
                    onClick={() => onNav(a.tab)}
                    className="w-full text-left px-4 py-2.5 flex items-start gap-2.5 hover:bg-surface-container-low cursor-pointer transition-colors group"
                  >
                    <span className={`w-1.5 h-1.5 rounded-full mt-1.5 shrink-0 ${SEVERITY[a.severity]?.dot}`} />
                    <span className="min-w-0 flex-1">
                      <span className="text-[13px] font-medium block group-hover:text-primary transition-colors">
                        {a.title}
                      </span>
                      <span className="text-xs text-on-surface-variant block truncate">{a.detail}</span>
                    </span>
                    <Icon name="chevron_right" className="text-[16px]! text-on-surface-variant mt-0.5" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>

      {/* Row two: who is on the network, and what is left to sell. */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-3">
        {sessions && (
          <Panel
            title="On the network now"
            className="xl:col-span-2"
            action={
              <button onClick={() => onNav('active')}
                className="text-xs text-on-surface-variant hover:text-primary cursor-pointer transition-colors">
                All sessions
              </button>
            }
          >
            {sessions.subscribers.length === 0 ? (
              <p className="px-4 py-6 text-sm text-on-surface-variant">
                {sessions.hotspotActive > 0
                  ? `No fixed-line subscribers online. ${sessions.hotspotActive} hotspot voucher${
                      sessions.hotspotActive === 1 ? ' is' : 's are'} in use.`
                  : 'Nobody is connected. Sessions appear here within a minute of someone coming online.'}
              </p>
            ) : (
              <div className="overflow-x-auto table-scroll">
                <table className="data-table w-full">
                  <thead>
                    <tr>
                      <th>Account</th>
                      <th>Speed</th>
                      <th className="text-right">Data used</th>
                      <th className="text-right">Seen</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sessions.subscribers.slice(0, 6).map((r) => (
                      <tr key={r.user}>
                        <td>
                          <span className="font-mono text-[12px]">{r.user}</span>
                          <span className="text-on-surface-variant"> · {r.name}</span>
                        </td>
                        <td className="text-on-surface-variant">{r.plan || '—'}</td>
                        <td className="text-right tabular-nums">
                          {r.dataMb >= 1024 ? `${(r.dataMb / 1024).toFixed(1)} GB` : `${r.dataMb} MB`}
                        </td>
                        <td className="text-right text-on-surface-variant">{relativeTime(r.since)}</td>
                      </tr>
                    ))}
                    {sessions.hotspotActive > 0 && (
                      <tr>
                        <td colSpan={4} className="text-on-surface-variant">
                          plus {sessions.hotspotActive} hotspot voucher
                          {sessions.hotspotActive === 1 ? '' : 's'} in use
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        )}

        {data.stock && (
          <Panel
            title="Voucher stock"
            action={
              <button onClick={() => onNav('vouchers')}
                className="text-xs text-primary hover:underline cursor-pointer">
                Generate
              </button>
            }
          >
            <div className="p-4 flex items-end gap-6">
              <div>
                <p className="font-mono text-[26px] sm:text-[32px] leading-none font-semibold tabular-nums">{data.stock.unused}</p>
                <p className="text-xs text-on-surface-variant mt-1">unsold</p>
              </div>
              <div>
                <p className="font-mono text-[26px] sm:text-[32px] leading-none font-semibold tabular-nums text-on-surface-variant">
                  {data.stock.active}
                </p>
                <p className="text-xs text-on-surface-variant mt-1">in use</p>
              </div>
            </div>
            {data.stock.unused === 0 && (
              <p className="px-4 pb-4 text-xs text-error">
                Nothing left to sell. Print a batch before a customer asks.
              </p>
            )}
          </Panel>
        )}
      </div>
    </div>
  )
}

function FormSection({ title, hint, children }) {
  return (
    <section className="border-t border-outline-variant/60 pt-5 first:border-0 first:pt-0">
      <h4 className="text-base font-bold text-on-surface">{title}</h4>
      {hint && <p className="text-xs text-on-surface-variant mt-0.5 mb-3">{hint}</p>}
      <div className={hint ? '' : 'mt-3'}>{children}</div>
    </section>
  )
}

// These three constants were referenced by the plan modal but never defined —
// a ReferenceError that white-screened New Package / Edit plan on open.
const DURATION_UNITS = { minutes: 1, hours: 60, days: 1440 }

const AVAILABILITY = [
  { key: 'LIVE', label: 'Live', hint: 'Shown on the portal and available to buy now.' },
  { key: 'HIDDEN', label: 'Hidden', hint: 'Not listed on the portal, but still works for vouchers and direct links.' },
  { key: 'OFF', label: 'Off', hint: 'Not sold and not usable.' },
]

// Matches Plan.FupAction on the backend (THROTTLE, BLOCK, NOTIFY).
const FUP_ACTIONS = [
  { key: 'THROTTLE', label: 'Throttle to a slower speed' },
  { key: 'BLOCK', label: 'Block until reset' },
  { key: 'NOTIFY', label: 'Notify only' },
]

function PlanModal({ auth, plan, onClose, onSaved }) {
  const editing = Boolean(plan)
  const [routers, setRouters] = useState([])
  const [form, setForm] = useState(() => {
    if (!plan) {
      return {
        name: '', type: 'HOTSPOT', availability: 'LIVE', price: '',
        durationValue: '', durationUnit: 'hours',
        mbps: '', rateLimit: '', maxDevices: 1,
        burstLimit: '', burstThreshold: '', burstTime: '',
        fupEnabled: false, fupLimitMb: '', fupAction: 'THROTTLE', fupRate: '',
        scheduleEnabled: false, scheduleFrom: '23:00', scheduleTo: '06:00',
        allowedRouterIds: [],
      }
    }
    // Re-express stored minutes in the largest unit that divides cleanly.
    const mins = plan.durationMinutes || 0
    const unit = mins % 1440 === 0 && mins >= 1440 ? 'days' : mins % 60 === 0 && mins >= 60 ? 'hours' : 'minutes'
    return {
      name: plan.name || '',
      type: plan.type || 'HOTSPOT',
      availability: plan.availability || (plan.active ? 'LIVE' : 'OFF'),
      price: plan.price ?? '',
      durationValue: String(Math.round(mins / DURATION_UNITS[unit]) || ''),
      durationUnit: unit,
      mbps: '',
      rateLimit: plan.bandwidth || '',
      maxDevices: plan.maxDevices || 1,
      burstLimit: plan.burstLimit || '',
      burstThreshold: plan.burstThreshold || '',
      burstTime: plan.burstTime || '',
      fupEnabled: Boolean(plan.fupEnabled),
      fupLimitMb: plan.fupLimitMb ?? '',
      fupAction: plan.fupAction || 'THROTTLE',
      fupRate: plan.fupRate || '',
      scheduleEnabled: Boolean(plan.scheduleEnabled),
      scheduleFrom: (plan.scheduleFrom || '23:00').slice(0, 5),
      scheduleTo: (plan.scheduleTo || '06:00').slice(0, 5),
      allowedRouterIds: plan.allowedRouterIds || [],
    }
  })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api('/admin/routers', { auth }).then(setRouters).catch(() => setRouters([]))
  }, [auth])

  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const totalMinutes = Math.round(Number(form.durationValue || 0) * DURATION_UNITS[form.durationUnit])

  // A plain Mbps figure covers the common case; the raw string is there for
  // asymmetric links the simple field cannot express.
  const effectiveRate = form.rateLimit.trim() || (form.mbps ? form.mbps + 'M/' + form.mbps + 'M' : '')

  const burstFilled = ['burstLimit', 'burstThreshold', 'burstTime'].filter((k) => form[k].trim()).length
  const burstPartial = burstFilled > 0 && burstFilled < 3

  async function save(e) {
    e.preventDefault()
    if (burstPartial) {
      setError('Fill all three burst fields, or clear all three.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const body = {
        name: form.name,
        type: form.type,
        availability: form.availability,
        price: Number(form.price),
        durationMinutes: totalMinutes,
        bandwidth: effectiveRate || null,
        maxDevices: Number(form.maxDevices) || 1,
        burstLimit: form.burstLimit.trim() || null,
        burstThreshold: form.burstThreshold.trim() || null,
        burstTime: form.burstTime.trim() || null,
        fupEnabled: form.fupEnabled,
        fupLimitMb: form.fupEnabled && form.fupLimitMb ? Number(form.fupLimitMb) : null,
        fupAction: form.fupEnabled ? form.fupAction : null,
        fupRate: form.fupEnabled && form.fupAction === 'THROTTLE' ? form.fupRate.trim() || null : null,
        scheduleEnabled: form.scheduleEnabled,
        scheduleFrom: form.scheduleEnabled ? form.scheduleFrom : null,
        scheduleTo: form.scheduleEnabled ? form.scheduleTo : null,
        allowedRouterIds: form.allowedRouterIds.map(Number),
      }
      await api(editing ? '/admin/plans/' + plan.id : '/admin/plans', {
        method: editing ? 'PUT' : 'POST',
        auth,
        body,
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const inputCls =
    'w-full bg-surface border border-outline-variant rounded-lg px-3 py-2.5 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-1.5'
  const hintCls = 'text-xs text-on-surface-variant mt-1'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-start justify-center p-5 overflow-y-auto" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-2xl rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col my-8">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-start">
          <div>
            <h3 className="text-2xl font-bold text-on-background">
              {editing ? 'Edit ' + plan.name : 'Create a package'}
            </h3>
            <p className="text-sm text-on-surface-variant mt-0.5">Speed limits, scheduling and router restrictions.</p>
          </div>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>

        <form onSubmit={save}>
          <div className="p-6 space-y-5">
            <FormSection title="Identity">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className={labelCls}>Name *</label>
                  <input className={inputCls} required placeholder="e.g. Home 10 Mbps" value={form.name} onChange={(e) => set({ name: e.target.value })} />
                </div>
                <div>
                  <label className={labelCls}>Type *</label>
                  <select className={inputCls} value={form.type} onChange={(e) => set({ type: e.target.value })}>
                    <option value="HOTSPOT">Hotspot</option>
                    <option value="PPPOE">PPPoE</option>
                  </select>
                  <p className={hintCls}>
                    {form.type === 'HOTSPOT'
                      ? 'Sold as vouchers on the captive portal.'
                      : 'Assigned to PPPoE subscribers by the office.'}
                  </p>
                </div>
              </div>
              <div className="mt-4">
                <label className={labelCls}>Availability</label>
                <div className="flex flex-wrap gap-2">
                  {AVAILABILITY.map((a) => (
                    <button
                      key={a.key}
                      type="button"
                      onClick={() => set({ availability: a.key })}
                      aria-pressed={form.availability === a.key}
                      className={'px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ' + (
                        form.availability === a.key
                          ? 'bg-primary-container text-on-primary-container font-semibold'
                          : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
                      )}
                    >
                      {a.label}
                    </button>
                  ))}
                </div>
                <p className={hintCls}>{AVAILABILITY.find((a) => a.key === form.availability)?.hint}</p>
              </div>
            </FormSection>

            <FormSection title="Pricing">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className={labelCls}>Price (KES) *</label>
                  <input className={inputCls} type="number" min="1" step="0.01" required value={form.price} onChange={(e) => set({ price: e.target.value })} />
                  <p className={hintCls}>Must be at least 1 - a paid package cannot cost 0.</p>
                </div>
                <div>
                  <label className={labelCls}>Duration *</label>
                  <div className="flex gap-2">
                    <input className={inputCls + ' flex-1 min-w-[90px]'} type="number" min="1" step="1" required placeholder="e.g. 8" value={form.durationValue} onChange={(e) => set({ durationValue: e.target.value })} />
                    <select className={inputCls + ' w-[116px] shrink-0'} value={form.durationUnit} onChange={(e) => set({ durationUnit: e.target.value })}>
                      <option value="minutes">Minutes</option>
                      <option value="hours">Hours</option>
                      <option value="days">Days</option>
                    </select>
                  </div>
                  <p className={hintCls}>
                    {totalMinutes > 0
                      ? '= ' + formatDuration(totalMinutes) + ' from activation - listed under "' + planGroup(totalMinutes) + '"'
                      : 'How long access lasts from activation.'}
                  </p>
                </div>
              </div>
            </FormSection>

            <FormSection title="Speed">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div>
                  <label className={labelCls}>Speed (Mbps)</label>
                  <input className={inputCls} type="number" min="1" placeholder="e.g. 5" value={form.mbps} onChange={(e) => set({ mbps: e.target.value })} disabled={Boolean(form.rateLimit.trim())} />
                </div>
                <div>
                  <label className={labelCls}>Or rate-limit string</label>
                  <input className={inputCls} placeholder="5M/5M" value={form.rateLimit} onChange={(e) => set({ rateLimit: e.target.value })} />
                  <p className={hintCls}>MikroTik format, down/up.</p>
                </div>
                <div>
                  <label className={labelCls}>Devices per account</label>
                  <input className={inputCls} type="number" min="1" max="10" value={form.maxDevices} onChange={(e) => set({ maxDevices: e.target.value })} />
                  <p className={hintCls}>Simultaneous devices.</p>
                </div>
              </div>
              {effectiveRate && (
                <p className="mt-3 text-xs text-on-surface-variant">
                  Router will receive{' '}
                  <code className="font-mono text-on-surface">
                    {effectiveRate}{burstFilled === 3 ? ' ' + form.burstLimit + ' ' + form.burstThreshold + ' ' + form.burstTime : ''}
                  </code>
                </p>
              )}

              <div className="mt-4 p-4 rounded-lg bg-surface-container-low/60 border border-outline-variant">
                <p className="text-sm font-semibold">Burst <span className="font-normal text-on-surface-variant">optional</span></p>
                <p className="text-xs text-on-surface-variant mt-0.5 mb-3">
                  Lets a subscriber briefly exceed their rate-limit. Fill all three to enable, or leave all three blank.
                </p>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                  <div>
                    <label className={labelCls}>Burst limit</label>
                    <input className={inputCls} placeholder="10M/10M" value={form.burstLimit} onChange={(e) => set({ burstLimit: e.target.value })} />
                  </div>
                  <div>
                    <label className={labelCls}>Burst threshold</label>
                    <input className={inputCls} placeholder="5M/5M" value={form.burstThreshold} onChange={(e) => set({ burstThreshold: e.target.value })} />
                  </div>
                  <div>
                    <label className={labelCls}>Burst time</label>
                    <input className={inputCls} placeholder="30/30" value={form.burstTime} onChange={(e) => set({ burstTime: e.target.value })} />
                  </div>
                </div>
                {burstPartial && (
                  <p className="mt-2 text-xs text-error">
                    RouterOS rejects a partial burst - fill all three, or clear all three.
                  </p>
                )}
              </div>
            </FormSection>

            <FormSection title="Fair use policy">
              <label className="flex items-center gap-3 cursor-pointer">
                <Toggle checked={form.fupEnabled} onChange={(e) => set({ fupEnabled: e.target.checked })} />
                <span className="text-sm">Enforce a monthly data limit</span>
              </label>
              {form.fupEnabled && (
                <div className="mt-4 grid grid-cols-1 md:grid-cols-3 gap-4">
                  <div>
                    <label className={labelCls}>Monthly limit (MB) *</label>
                    <input className={inputCls} type="number" min="1" required value={form.fupLimitMb} onChange={(e) => set({ fupLimitMb: e.target.value })} />
                  </div>
                  <div>
                    <label className={labelCls}>Then</label>
                    <select className={inputCls} value={form.fupAction} onChange={(e) => set({ fupAction: e.target.value })}>
                      {FUP_ACTIONS.map((a) => <option key={a.key} value={a.key}>{a.label}</option>)}
                    </select>
                  </div>
                  {form.fupAction === 'THROTTLE' && (
                    <div>
                      <label className={labelCls}>Reduced speed *</label>
                      <input className={inputCls} required placeholder="1M/1M" value={form.fupRate} onChange={(e) => set({ fupRate: e.target.value })} />
                    </div>
                  )}
                </div>
              )}
            </FormSection>

            <FormSection title="Schedule">
              <label className="flex items-center gap-3 cursor-pointer">
                <Toggle checked={form.scheduleEnabled} onChange={(e) => set({ scheduleEnabled: e.target.checked })} />
                <span className="text-sm">Restrict when this plan can be used</span>
              </label>
              {form.scheduleEnabled && (
                <>
                  <div className="mt-4 flex flex-wrap items-end gap-3">
                    <div>
                      <label className={labelCls}>From</label>
                      <input className={inputCls} type="time" value={form.scheduleFrom} onChange={(e) => set({ scheduleFrom: e.target.value })} />
                    </div>
                    <div>
                      <label className={labelCls}>To</label>
                      <input className={inputCls} type="time" value={form.scheduleTo} onChange={(e) => set({ scheduleTo: e.target.value })} />
                    </div>
                  </div>
                  <p className={hintCls}>
                    {form.scheduleFrom > form.scheduleTo
                      ? 'Crosses midnight - usable ' + form.scheduleFrom + ' until ' + form.scheduleTo + ' the next morning.'
                      : 'Usable between ' + form.scheduleFrom + ' and ' + form.scheduleTo + '.'}
                    {' '}Outside that window it is hidden from the portal.
                  </p>
                </>
              )}
            </FormSection>

            <FormSection title="Router restriction" hint="Limit this plan to certain routers. Leave empty to allow it everywhere.">
              {routers.length === 0 ? (
                <p className="text-sm text-on-surface-variant">No routers configured yet.</p>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {routers.map((r) => {
                    const checked = form.allowedRouterIds.map(String).includes(String(r.id))
                    return (
                      <label key={r.id} className="flex items-center gap-2.5 p-2.5 rounded-lg border border-outline-variant cursor-pointer hover:bg-surface-container-high">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={(e) => set({
                            allowedRouterIds: e.target.checked
                              ? [...form.allowedRouterIds, r.id]
                              : form.allowedRouterIds.filter((id) => String(id) !== String(r.id)),
                          })}
                        />
                        <span className="text-sm">{r.name}<span className="text-on-surface-variant"> - {r.host}</span></span>
                      </label>
                    )
                  })}
                </div>
              )}
            </FormSection>

            {error && <p className="text-sm text-error">{error}</p>}
          </div>

          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy || burstPartial} className="px-4 h-10 rounded-md text-sm font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 disabled:opacity-60 cursor-pointer">
              {busy ? 'Saving...' : editing ? 'Save changes' : 'Create package'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function Toggle({ checked, onChange }) {
  return (
    <label className="relative inline-flex items-center cursor-pointer">
      <input type="checkbox" className="sr-only peer" checked={checked} onChange={onChange} />
      <div className="w-9 h-5 bg-surface-variant rounded-full peer peer-checked:bg-primary after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border after:border-gray-300 after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:after:translate-x-full peer-checked:after:border-white"></div>
    </label>
  )
}

const PLAN_AVAILABILITY_STYLES = {
  LIVE: 'bg-secondary-container text-on-secondary-container',
  HIDDEN: 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20',
  OFF: 'bg-surface-container-high text-on-surface-variant',
}

/** Availability as stored, falling back to the older active flag. */
const planAvailability = (p) => p.availability || (p.active ? 'LIVE' : 'OFF')

function Plans({ auth }) {
  const [plans, setPlans] = useState([])
  const [search, setSearch] = useState('')
  const [modal, setModal] = useState(false)
  const [editing, setEditing] = useState(null)

  const load = () => api('/admin/plans', { auth }).then(setPlans).catch(() => {})
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const filtered = plans.filter((p) => p.name.toLowerCase().includes(search.toLowerCase()))
  const active = plans.filter((p) => planAvailability(p) === 'LIVE')
  const avgPrice = plans.length ? Math.round(plans.reduce((a, p) => a + Number(p.price), 0) / plans.length) : 0

  return (
    <div>
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2.5 mb-4">
        <div>
          <h2 className="text-xl font-semibold tracking-tight text-on-surface">Packages</h2>
          <p className="text-xs text-on-surface-variant">Speed limits, fair use, scheduling and pricing.</p>
        </div>
        <button
          onClick={() => setModal(true)}
          className="bg-primary text-on-primary text-sm font-semibold px-4 h-10 rounded-md flex items-center gap-1.5 hover:opacity-90 transition-opacity active:scale-[0.98] whitespace-nowrap cursor-pointer"
        >
          <Icon name="add" />
          New Package
        </button>
      </div>

      {/* Stat mini-cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5 mb-4">
        <div className="bg-surface-container-lowest px-3.5 py-2.5 rounded-md border border-outline-variant border-l-2 border-l-primary">
          <CardLabel>Active Plans</CardLabel>
          <div className="text-[22px] leading-tight font-semibold mt-0.5 text-on-surface tabular-nums">{active.length}</div>
        </div>
        <div className="bg-surface-container-lowest px-3.5 py-2.5 rounded-md border border-outline-variant">
          <CardLabel>Total Plans</CardLabel>
          <div className="text-[22px] leading-tight font-semibold mt-0.5 text-on-surface tabular-nums">{plans.length}</div>
        </div>
        <div className="bg-surface-container-lowest px-3.5 py-2.5 rounded-md border border-outline-variant">
          <CardLabel>Avg. Price</CardLabel>
          <div className="text-[22px] leading-tight font-semibold mt-0.5 text-on-surface tabular-nums">{fmtKES(avgPrice)}</div>
        </div>
      </div>

      {/* Table card */}
      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        <div className="p-4 border-b border-outline-variant/50 bg-surface-container-low/30">
          <div className="relative w-full sm:w-64">
            <Icon name="search" className="absolute left-3 top-1/2 -translate-y-1/2 text-outline" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full bg-surface border border-surface-variant rounded-lg pl-10 pr-4 py-2 text-sm focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[40px]"
              placeholder="Search plans..."
              type="text"
            />
          </div>
        </div>
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[700px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="border-b border-outline-variant/50">Plan Name</th>
                <th className="border-b border-outline-variant/50">Price</th>
                <th className="border-b border-outline-variant/50">Duration</th>
                <th className="border-b border-outline-variant/50">Speed</th>
                <th className="border-b border-outline-variant/50">Rules</th>
                <th className="border-b border-outline-variant/50">Availability</th>
                <th className="border-b border-outline-variant/50 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm">
              {filtered.map((p) => (
                <tr key={p.id} className="border-b border-outline-variant/30 hover:bg-surface-container-low/20 transition-colors">
                  <td className="">
                    <div className="text-lg font-semibold text-on-background">{p.name}</div>
                    {p.bandwidth && <div className="text-xs font-semibold tracking-wider text-on-surface-variant mt-1">Rate limit: {p.bandwidth}</div>}
                  </td>
                  <td className="font-mono text-lg font-semibold tabular-nums">{fmtKES(p.price)}</td>
                  <td className="">
                    <div>{formatDuration(p.durationMinutes)}</div>
                    <span className="inline-block mt-1 px-2 py-0.5 rounded-full bg-surface-container text-on-surface-variant text-[10px] font-bold uppercase tracking-wider">
                      {planGroup(p.durationMinutes)}
                    </span>
                  </td>
                  <td className="">
                    {p.bandwidth ? (
                      <div className="flex items-center gap-2">
                        <Icon name="speed" className="text-[16px]! text-primary" />
                        {speedLabel(p.bandwidth)}
                      </div>
                    ) : '—'}
                    <div className="flex items-center gap-1 text-xs text-on-surface-variant mt-1">
                      <Icon name="devices" className="text-[14px]!" />
                      {p.effectiveMaxDevices || 1} device{(p.effectiveMaxDevices || 1) > 1 ? 's' : ''}
                    </div>
                    {p.burstLimit && p.burstThreshold && p.burstTime && (
                      <div className="text-xs text-on-surface-variant mt-1">Burst to {speedLabel(p.burstLimit)}</div>
                    )}
                  </td>
                  <td className="">
                    <div className="flex flex-wrap gap-1">
                      <span className="px-2 py-0.5 rounded-full bg-surface-container text-on-surface-variant text-[10px] font-bold uppercase tracking-wider">
                        {(p.type || 'HOTSPOT') === 'PPPOE' ? 'PPPoE' : 'Hotspot'}
                      </span>
                      {p.fupEnabled && p.fupLimitMb > 0 && (
                        <span className="px-2 py-0.5 rounded-full bg-[#f59e0b]/10 text-[#b45309] text-[10px] font-bold uppercase tracking-wider"
                          title={`Over ${p.fupLimitMb} MB a month: ${(p.fupAction || '').toLowerCase()}`}>
                          FUP {p.fupLimitMb >= 1024 ? `${(p.fupLimitMb / 1024).toFixed(0)}GB` : `${p.fupLimitMb}MB`}
                        </span>
                      )}
                      {p.scheduleEnabled && p.scheduleFrom && p.scheduleTo && (
                        <span className="px-2 py-0.5 rounded-full bg-primary-container/25 text-primary text-[10px] font-bold uppercase tracking-wider">
                          {String(p.scheduleFrom).slice(0, 5)}–{String(p.scheduleTo).slice(0, 5)}
                        </span>
                      )}
                      {p.allowedRouterIds?.length > 0 && (
                        <span className="px-2 py-0.5 rounded-full bg-surface-container text-on-surface-variant text-[10px] font-bold uppercase tracking-wider">
                          {p.allowedRouterIds.length} router{p.allowedRouterIds.length > 1 ? 's' : ''}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="">
                    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${PLAN_AVAILABILITY_STYLES[planAvailability(p)]}`}>
                      {planAvailability(p) === 'LIVE' ? 'Live' : planAvailability(p) === 'HIDDEN' ? 'Hidden' : 'Off'}
                    </span>
                  </td>
                  <td className="text-right">
                    <div className="flex gap-1.5 justify-end">
                      <button
                        onClick={() => setEditing(p)}
                        className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high"
                      >
                        Edit
                      </button>
                      <select
                        aria-label={`Availability for ${p.name}`}
                        value={planAvailability(p)}
                        onChange={(e) => api(`/admin/plans/${p.id}/availability`, { method: 'PATCH', auth, body: { availability: e.target.value } }).then(load)}
                        className="bg-surface border border-outline-variant rounded-lg px-2 py-1.5 text-xs cursor-pointer"
                      >
                        <option value="LIVE">Live</option>
                        <option value="HIDDEN">Hidden</option>
                        <option value="OFF">Off</option>
                      </select>
                    </div>
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={7}>No plans found.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {modal && <PlanModal auth={auth} onClose={() => setModal(false)} onSaved={() => { setModal(false); load() }} />}
      {editing && (
        <PlanModal
          auth={auth}
          plan={editing}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load() }}
        />
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Subscribers (monthly PPPoE customers)                               */
/* ------------------------------------------------------------------ */

function SubscriberModal({ auth, onClose, onSaved }) {
  const [form, setForm] = useState({ fullName: '', phoneNumber: '', pppoeUsername: '', pppoePassword: '', mbps: '', monthlyFee: '', initialMonths: 1, initialMethod: 'CASH' })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/admin/subscribers', {
        method: 'POST',
        auth,
        body: {
          fullName: form.fullName,
          phoneNumber: form.phoneNumber.replace(/\D/g, ''),
          pppoeUsername: form.pppoeUsername,
          pppoePassword: form.pppoePassword,
          bandwidth: form.mbps ? `${form.mbps}M/${form.mbps}M` : null,
          monthlyFee: Number(form.monthlyFee),
          initialMonths: Number(form.initialMonths),
          initialMethod: form.initialMethod,
        },
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const inputCls =
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[40px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">Add Subscriber</h3>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4 max-h-[65vh] overflow-y-auto">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={labelCls}>Full Name</label>
                <input className={inputCls} required placeholder="e.g. Mary Kamau" value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>Phone</label>
                <input className={inputCls} required placeholder="2547XXXXXXXX" value={form.phoneNumber} onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={labelCls}>PPPoE Username</label>
                <input className={inputCls} required placeholder="e.g. mkamau" value={form.pppoeUsername} onChange={(e) => setForm({ ...form, pppoeUsername: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>PPPoE Password</label>
                <input className={inputCls} required minLength={6} type="text" placeholder="Set in their router" value={form.pppoePassword} onChange={(e) => setForm({ ...form, pppoePassword: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className={labelCls}>Speed (Mbps)</label>
                <input className={inputCls} type="number" min="1" placeholder="e.g. 10" value={form.mbps} onChange={(e) => setForm({ ...form, mbps: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>Monthly Fee (KES)</label>
                <input className={inputCls} type="number" min="1" required placeholder="e.g. 2500" value={form.monthlyFee} onChange={(e) => setForm({ ...form, monthlyFee: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>Months Paid Now</label>
                <input className={inputCls} type="number" min="0" max="12" required value={form.initialMonths} onChange={(e) => setForm({ ...form, initialMonths: e.target.value })} />
              </div>
            </div>
            <div>
              <label className={labelCls}>Initial Payment Method</label>
              <select className={inputCls} value={form.initialMethod} onChange={(e) => setForm({ ...form, initialMethod: e.target.value })}>
                <option value="CASH">Cash received — credit the months now</option>
                <option value="MPESA">Send a payment request — months credited after they pay</option>
              </select>
            </div>
            <p className="text-xs font-semibold tracking-wider text-tertiary">
              The PPPoE username and password go into the customer's router (PPPoE client). The account is created on the MikroTik automatically.
            </p>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[40px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-4 h-10 rounded-md text-sm font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[40px] disabled:opacity-60 cursor-pointer">
              {busy ? 'Creating…' : 'Create Subscriber'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}


/** How a payment was taken, in words. */
function payLabel(method) {
  if (!method) return '\u2014'
  const known = {
    MPESA: 'M-Pesa', MPESA_API: 'M-Pesa', CASH: 'Cash', ONLINE: 'Online',
    PAYSTACK: 'Paystack', FLUTTERWAVE: 'Flutterwave', STRIPE: 'Card',
    MTN_MOMO: 'MTN MoMo', AIRTEL_MONEY: 'Airtel Money', ORANGE_MONEY: 'Orange Money',
    WAVE: 'Wave', CHAPA: 'Chapa', PAYNOW: 'EcoCash',
  }
  return known[method] || method
}

/**
 * Standing orders, so a renewal collects itself.
 *
 * Two mechanisms behind one panel, and the difference is worth showing rather
 * than hiding: M-Pesa Ratiba is approved on the customer's handset and Safaricom
 * sends the money, while every other rail needs the customer to pay once and
 * agree that renewals may be charged the same way. The second returns a link to
 * send them.
 */
function StandingOrder({ auth, subscriber, onChanged }) {
  const [mandate, setMandate] = useState(null)
  const [options, setOptions] = useState([])
  const [kind, setKind] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => {
    api(`/admin/subscribers/${subscriber.id}/mandate`, { auth })
      .then(setMandate).catch(() => setMandate({ exists: false }))
    api('/admin/mandates/options', { auth })
      .then((r) => {
        setOptions(r.options || [])
        setKind((k) => k || (r.options?.[0]?.kind ?? ''))
      })
      .catch(() => setOptions([]))
  }
  useEffect(load, [subscriber.id]) // eslint-disable-line react-hooks/exhaustive-deps

  async function setUp() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/subscribers/${subscriber.id}/mandate`,
        { method: 'POST', auth, body: { kind } })
      setMsg({ ok: true, text: r.message, url: r.checkoutUrl })
      load(); onChanged?.()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function cancel() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/subscribers/${subscriber.id}/mandate`, { method: 'DELETE', auth })
      setMsg({ ok: true, text: r.message })
      load(); onChanged?.()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (mandate === null) return null

  const live = mandate.exists && mandate.collecting
  return (
    <div>
      <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">
        Standing Order
      </h4>

      {live ? (
        <div className={`rounded-lg border p-3 ${mandate.suspect ? 'border-error' : 'border-outline-variant'}`}>
          <div className="flex items-center justify-between gap-3 flex-wrap">
            <div>
              <p className="text-sm font-semibold">
                {payLabel(mandate.provider)} · {mandate.model === 'PUSH'
                  ? 'the customer\u2019s bank sends it'
                  : 'charged automatically'}
              </p>
              <p className="text-xs text-on-surface-variant">
                {mandate.collections > 0
                  ? `${mandate.collections} collected, last ${fmtDate(mandate.lastCollectedAt)}`
                  : 'nothing collected yet'}
                {mandate.consecutiveFailures > 0 && ` \u00b7 ${mandate.consecutiveFailures} failed in a row`}
              </p>
            </div>
            <button disabled={busy} onClick={cancel}
              className="h-9 px-3 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer disabled:opacity-60">
              Stop relying on it
            </button>
          </div>
          {/* The dangerous state: the operator has stopped chasing this
              customer and nothing is actually being collected. */}
          {mandate.suspect && (
            <p className="text-xs text-error mt-2">
              This says it is working and has collected nothing. This customer is not being
              chased either — check it or stop relying on it.
            </p>
          )}
          {mandate.lastError && (
            <p className="text-xs text-[#b45309] mt-2">Last problem: {mandate.lastError}</p>
          )}
        </div>
      ) : mandate.exists ? (
        <div className="rounded-lg border border-outline-variant p-3">
          <p className="text-sm">
            Waiting for the customer to authorise it ({payLabel(mandate.provider)}).
          </p>
          <p className="text-xs text-on-surface-variant mt-1">
            They are still being chased for this month, which is correct until it is live.
          </p>
          <button disabled={busy} onClick={cancel}
            className="mt-2 h-9 px-3 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer disabled:opacity-60">
            Cancel the request
          </button>
        </div>
      ) : options.length === 0 ? (
        <p className="text-xs text-on-surface-variant">
          No gateway that can hold a standing order is switched on. Paystack, Flutterwave,
          Stripe or M-Pesa can; the others cannot charge a customer who is not there.
        </p>
      ) : (
        <div className="flex items-end gap-2 flex-wrap">
          <select value={kind} onChange={(e) => setKind(e.target.value)}
            className="h-10 bg-surface border border-outline-variant rounded-lg px-2 text-sm focus:outline-none focus:border-primary">
            {options.map((o) => (
              <option key={o.kind} value={o.kind}>{payLabel(o.kind)}</option>
            ))}
          </select>
          <button disabled={busy || !kind} onClick={setUp}
            className="h-10 px-3 rounded-lg bg-secondary text-on-secondary text-xs font-semibold disabled:opacity-60 cursor-pointer">
            {busy ? 'Setting up\u2026' : 'Set up'}
          </button>
          <p className="text-xs text-on-surface-variant basis-full">
            {options.find((o) => o.kind === kind)?.how}
          </p>
        </div>
      )}

      {msg && (
        <div className="mt-2">
          <p className={`text-xs ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>
          {msg.url && (
            <button type="button" onClick={() => navigator.clipboard?.writeText(msg.url)}
              className="mt-1 text-xs underline text-primary cursor-pointer break-all text-left">
              Copy the link to send them
            </button>
          )}
        </div>
      )}
    </div>
  )
}


function SubscriberDetail({ auth, subscriber, onClose, onChanged }) {
  const [history, setHistory] = useState(null)
  const [months, setMonths] = useState(1)
  const [extendAmount, setExtendAmount] = useState(1)
  const [extendUnit, setExtendUnit] = useState('DAYS')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const s = subscriber
  const st = subscriberState(s)
  const days = Math.floor((new Date(s.paidUntil) - Date.now()) / 86400000)

  useEffect(() => {
    api(`/admin/subscribers/${s.id}/payments`, { auth }).then(setHistory).catch(() => setHistory([]))
  }, [s.id, auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function run(path, body, okText) {
    setBusy(true)
    setMsg(null)
    try {
      const r = await api(path, { method: body ? 'POST' : 'PATCH', auth, body })
      setMsg({ ok: true, text: r?.message || okText })
      api(`/admin/subscribers/${s.id}/payments`, { auth }).then(setHistory).catch(() => {})
      onChanged()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  const Row = ({ label, children }) => (
    <div className="flex justify-between items-center py-2 border-b border-outline-variant border-dashed gap-4">
      <span className="text-sm text-on-surface-variant shrink-0">{label}</span>
      <span className="text-sm font-medium text-on-surface text-right">{children}</span>
    </div>
  )

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="flex-1 bg-on-background/30 backdrop-blur-[2px]" onClick={onClose}></div>
      <div className="w-full max-w-md bg-surface-container-lowest h-full shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col overflow-hidden">
        <div className="p-6 border-b border-outline-variant bg-surface-container-low flex justify-between items-start">
          <div>
            <h3 className="text-lg font-semibold text-on-surface">{s.fullName}</h3>
            <p className="text-sm text-on-surface-variant mt-1 font-mono">{s.pppoeUsername}</p>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-full hover:bg-surface-container flex items-center justify-center text-on-surface-variant transition-colors cursor-pointer" aria-label="Close details">
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1 space-y-6">
          <div className={`flex flex-col items-center justify-center py-4 bg-surface-container-low rounded-md border-l-2 ${
            st.label === 'Active' ? 'border-secondary' : st.label === 'Expiring' ? 'border-[#f59e0b]' : 'border-error'
          }`}>
            <span className={`text-xs font-semibold tracking-wider px-2.5 py-1 rounded-full mb-2 ${st.cls}`}>{st.label}</span>
            <span className="text-lg font-bold text-on-surface">{fmtDate(s.paidUntil)}</span>
            <span className="text-sm text-on-surface-variant">
              {days < 0 ? `${-days} day${days === -1 ? '' : 's'} overdue` : `${days} day${days === 1 ? '' : 's'} remaining`}
            </span>
          </div>

          <div>
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">Account</h4>
            <Row label="Phone">{s.phoneNumber}</Row>
            <Row label="PPPoE username"><span className="font-mono">{s.pppoeUsername}</span></Row>
            <Row label="Package">
              {fmtKES(s.monthlyFee)}/mo{s.bandwidth ? ` · ${speedLabel(s.bandwidth)}` : ''}
            </Row>
            <Row label="Created by">
              {s.createdBy ? <span className="capitalize">{s.createdBy}</span> : <span className="text-on-surface-variant">—</span>}
            </Row>
            <Row label="Created on">{fmtDate(s.createdAt)}, {fmtTime(s.createdAt)}</Row>
            <Row label="Last payment">
              {/* Was a two-way guess that called every rail "Cash". */}
              {s.lastPaymentMethod
                ? `${payLabel(s.lastPaymentMethod)}${s.lastPaymentAt ? ` · ${fmtDate(s.lastPaymentAt)}` : ''}`
                : <span className="text-on-surface-variant">No payment yet</span>}
            </Row>
          </div>

          {/* Take payment */}
          <div>
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">Take Payment</h4>
            <div className="flex items-end gap-2 flex-wrap">
              <div className="w-20">
                <label className="block text-xs text-on-surface-variant mb-1">Months</label>
                <input type="number" min="1" max="12" value={months} onChange={(e) => setMonths(e.target.value)}
                  className="w-full h-10 bg-surface border border-outline-variant rounded-lg px-2 text-sm text-center tabular-nums focus:outline-none focus:border-primary" />
              </div>
              <span className="text-sm font-semibold text-primary tabular-nums pb-2.5">= {fmtKES(Number(s.monthlyFee) * (Number(months) || 0))}</span>
              <button disabled={busy} onClick={() => run(`/admin/subscribers/${s.id}/payments`, { months: Number(months) }, 'Cash payment recorded.')}
                className="h-10 px-3 rounded-lg bg-secondary text-on-secondary text-xs font-semibold disabled:opacity-60 cursor-pointer">
                Record Cash
              </button>
              {/* Named for what it does, not for one country's rail. It goes
                  down whichever gateway is first in Settings, which for a
                  Ghanaian operator is not M-Pesa. */}
              <button disabled={busy} onClick={() => run(`/admin/subscribers/${s.id}/stk`, { months: Number(months) }, 'Payment request sent.')}
                className="h-10 px-3 rounded-lg bg-primary text-on-primary text-xs font-semibold disabled:opacity-60 cursor-pointer">
                Request Payment
              </button>
            </div>
          </div>

          <StandingOrder auth={auth} subscriber={s} onChanged={onChanged} />

          {/* Goodwill extend */}
          <div>
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">Extend Without Payment</h4>
            <p className="text-xs text-on-surface-variant mb-2">Goodwill time, e.g. compensation for an outage.</p>
            <div className="flex items-end gap-2 flex-wrap">
              <div className="w-20">
                <input type="number" min="1" value={extendAmount} onChange={(e) => setExtendAmount(e.target.value)}
                  className="w-full h-10 bg-surface border border-outline-variant rounded-lg px-2 text-sm text-center tabular-nums focus:outline-none focus:border-primary" />
              </div>
              <select value={extendUnit} onChange={(e) => setExtendUnit(e.target.value)}
                className="h-10 bg-surface border border-outline-variant rounded-lg px-3 text-sm focus:outline-none focus:border-primary">
                <option value="HOURS">Hours</option>
                <option value="DAYS">Days</option>
                <option value="MONTHS">Months</option>
              </select>
              <button disabled={busy} onClick={() => run(`/admin/subscribers/${s.id}/extend`, { amount: Number(extendAmount), unit: extendUnit }, 'Subscription extended.')}
                className="h-10 px-4 rounded-lg border border-primary text-primary text-xs font-semibold hover:bg-primary/5 transition-colors disabled:opacity-60 cursor-pointer flex items-center gap-1">
                <Icon name="more_time" className="text-[16px]!" /> Extend
              </button>
            </div>
          </div>

          {/* Payment history */}
          <div>
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">Payment History</h4>
            {history === null ? <Skeleton className="h-16" /> : (
              <ul className="divide-y divide-[color:var(--color-outline-variant)]">
                {history.map((p) => (
                  <li key={p.id} className="py-2 flex justify-between items-center gap-2">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-on-surface">{fmtKES(p.amount)} · {p.months} month{p.months > 1 ? 's' : ''}</p>
                      <p className="text-xs text-on-surface-variant">
                        {p.method === 'MPESA' ? 'M-Pesa' : 'Cash'}{p.mpesaReceiptNumber ? ` · ${p.mpesaReceiptNumber}` : ''} · {fmtDate(p.createdAt)}
                      </p>
                    </div>
                    <StatusPill status={p.status} />
                  </li>
                ))}
                {history.length === 0 && <li className="py-2 text-sm text-on-surface-variant">No payments recorded yet.</li>}
              </ul>
            )}
          </div>

          {msg && <p className={`text-sm font-semibold ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}
        </div>

        <div className="p-4 border-t border-outline-variant bg-surface-container-low flex gap-3">
          {s.status === 'ACTIVE' ? (
            <button disabled={busy} onClick={() => run(`/admin/subscribers/${s.id}/suspend`, null, 'Subscriber suspended.')}
              className="flex-1 h-12 border border-error text-error rounded-lg text-base font-semibold hover:bg-error/5 transition-colors disabled:opacity-60 cursor-pointer">
              Suspend
            </button>
          ) : (
            <button disabled={busy} onClick={() => run(`/admin/subscribers/${s.id}/activate`, null, 'Subscriber reactivated.')}
              className="flex-1 h-12 bg-primary text-on-primary rounded-lg text-base font-semibold hover:bg-surface-tint transition-colors disabled:opacity-60 cursor-pointer">
              Reactivate
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function subscriberState(s) {
  if (s.status === 'SUSPENDED') return { label: 'Suspended', cls: 'bg-error-container text-on-error-container' }
  const days = (new Date(s.paidUntil) - Date.now()) / 86400000
  if (days < 0) return { label: 'Overdue', cls: 'bg-error-container text-on-error-container' }
  if (days <= 3) return { label: 'Expiring', cls: 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20' }
  return { label: 'Active', cls: 'bg-secondary-container text-on-secondary-container' }
}

/* Minimal RFC-4180-ish CSV parser: handles quoted fields, embedded commas and
   newlines, and doubled "" escapes. Returns an array of string arrays. */
function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let inQuotes = false
  const s = String(text).replace(/\r\n?/g, '\n')
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (inQuotes) {
      if (c === '"') {
        if (s[i + 1] === '"') { field += '"'; i++ } else inQuotes = false
      } else field += c
    } else if (c === '"') inQuotes = true
    else if (c === ',') { row.push(field); field = '' }
    else if (c === '\n') { row.push(field); rows.push(row); row = []; field = '' }
    else field += c
  }
  if (field.length || row.length) { row.push(field); rows.push(row) }
  return rows
}

const SAMPLE_CSV = `fullName,phoneNumber,pppoeUsername,pppoePassword,bandwidth,monthlyFee,expiry
Mary Kamau,254712345678,mkamau,secret12,10M/10M,2500,2026-09-30
John Otieno,254720000000,jotieno,,5,1500,30/09/2026`

/* Bulk-import subscribers from a CSV exported by a previous system. Parses on
   the client, previews, then posts the rows to /admin/subscribers/import. No
   M-Pesa prompt and no payment record is created — an optional expiry column
   preserves the time each customer already has. */
function ImportSubscribersModal({ auth, onClose, onDone }) {
  const [rows, setRows] = useState([])
  const [parseErr, setParseErr] = useState(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  function parse(raw) {
    setParseErr(null)
    setResult(null)
    try {
      const recs = parseCsv(raw).filter((r) => r.some((c) => c && c.trim()))
      if (recs.length < 2) { setRows([]); setParseErr('Need a header row plus at least one data row.'); return }
      const header = recs[0].map((h) => h.trim().toLowerCase())
      const find = (...names) => header.findIndex((h) => names.includes(h))
      const col = {
        fullName: find('fullname', 'name', 'full name', 'customer', 'customer name'),
        phoneNumber: find('phonenumber', 'phone', 'phone number', 'msisdn', 'mobile'),
        pppoeUsername: find('pppoeusername', 'username', 'user', 'pppoe', 'account'),
        pppoePassword: find('pppoepassword', 'password', 'pass', 'pppoe password'),
        bandwidth: find('bandwidth', 'speed', 'plan', 'mbps'),
        monthlyFee: find('monthlyfee', 'fee', 'amount', 'monthly', 'price'),
        expiry: find('expiry', 'expires', 'expiry date', 'paiduntil', 'due', 'due date', 'next due'),
      }
      if (col.fullName < 0 || col.pppoeUsername < 0) {
        setRows([]); setParseErr('CSV must include at least "fullName" and "pppoeUsername" columns.'); return
      }
      const get = (r, i) => (i >= 0 && r[i] != null ? r[i].trim() : '')
      const parsed = recs.slice(1).map((r) => ({
        fullName: get(r, col.fullName),
        phoneNumber: get(r, col.phoneNumber),
        pppoeUsername: get(r, col.pppoeUsername),
        pppoePassword: get(r, col.pppoePassword),
        bandwidth: get(r, col.bandwidth),
        monthlyFee: get(r, col.monthlyFee) ? Number(get(r, col.monthlyFee).replace(/[^0-9.]/g, '')) : null,
        expiry: get(r, col.expiry),
      }))
      setRows(parsed)
    } catch (e) {
      setRows([]); setParseErr('Could not read the CSV: ' + e.message)
    }
  }

  function onFile(e) {
    const f = e.target.files?.[0]
    if (!f) return
    const reader = new FileReader()
    reader.onload = () => parse(String(reader.result))
    reader.readAsText(f)
  }

  async function doImport() {
    setBusy(true)
    setResult(null)
    try {
      const res = await api('/admin/subscribers/import', { method: 'POST', auth, body: rows })
      setResult(res)
      if (res.created > 0) onDone()
    } catch (e) {
      setResult({ error: e.message })
    } finally {
      setBusy(false)
    }
  }

  const invalid = rows.filter((r) => !r.fullName || !r.pppoeUsername).length

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-2xl rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)] max-h-[90vh] flex flex-col">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center shrink-0">
          <div>
            <h3 className="text-lg font-semibold text-on-surface">Import subscribers</h3>
            <p className="text-xs text-on-surface-variant">Bring customers over from another system via CSV.</p>
          </div>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close"><Icon name="close" /></button>
        </div>

        <div className="p-6 overflow-y-auto">
          {!result && (
            <>
              <div className="flex flex-wrap items-center gap-3 mb-3">
                <label className="border border-outline-variant text-on-surface text-sm font-semibold px-4 h-10 rounded-md flex items-center gap-1.5 hover:bg-surface-container cursor-pointer">
                  <Icon name="attach_file" /> Choose CSV file
                  <input type="file" accept=".csv,text/csv" className="hidden" onChange={onFile} />
                </label>
                <button onClick={() => parse(SAMPLE_CSV)} className="text-sm text-primary hover:underline cursor-pointer">Load a sample</button>
                <span className="text-xs text-on-surface-variant">or paste below</span>
              </div>

              <textarea
                rows={6}
                placeholder={'fullName,phoneNumber,pppoeUsername,pppoePassword,bandwidth,monthlyFee,expiry\nMary Kamau,254712345678,mkamau,secret12,10M/10M,2500,2026-09-30'}
                onChange={(e) => parse(e.target.value)}
                className="w-full bg-surface border border-outline-variant rounded-lg px-3 py-2 text-xs font-mono text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none"
              />

              <p className="text-xs text-on-surface-variant mt-2 leading-relaxed">
                Required columns: <code className="text-primary">fullName</code>, <code className="text-primary">pppoeUsername</code>.
                Optional: phoneNumber, pppoePassword (auto-generated if blank), bandwidth (e.g. <code>10M/10M</code> or just <code>5</code>),
                monthlyFee, expiry (<code>YYYY-MM-DD</code>). No payment prompt is sent and no payment is recorded — the expiry just preserves remaining time.
              </p>

              {parseErr && <p className="text-sm text-error mt-3">{parseErr}</p>}

              {rows.length > 0 && (
                <div className="mt-4">
                  <p className="text-sm text-on-surface mb-2">
                    <span className="font-semibold">{rows.length}</span> row{rows.length === 1 ? '' : 's'} ready
                    {invalid > 0 && <span className="text-error"> · {invalid} missing name/username (will fail)</span>}
                  </p>
                  <div className="border border-outline-variant rounded-lg overflow-hidden max-h-48 overflow-y-auto">
                    <table className="w-full text-xs">
                      <thead className="bg-surface-container text-on-surface-variant sticky top-0">
                        <tr><th className="text-left px-3 py-2">Name</th><th className="text-left px-3 py-2">PPPoE user</th><th className="text-left px-3 py-2">Phone</th><th className="text-left px-3 py-2">Fee</th><th className="text-left px-3 py-2">Expiry</th></tr>
                      </thead>
                      <tbody>
                        {rows.slice(0, 50).map((r, i) => (
                          <tr key={i} className="border-t border-outline-variant/50">
                            <td className={`px-3 py-1.5 ${!r.fullName ? 'text-error' : 'text-on-surface'}`}>{r.fullName || '— missing —'}</td>
                            <td className={`px-3 py-1.5 font-mono ${!r.pppoeUsername ? 'text-error' : 'text-on-surface-variant'}`}>{r.pppoeUsername || '— missing —'}</td>
                            <td className="px-3 py-1.5 text-on-surface-variant">{r.phoneNumber || '—'}</td>
                            <td className="px-3 py-1.5 text-on-surface-variant tabular-nums">{r.monthlyFee || '—'}</td>
                            <td className="px-3 py-1.5 text-on-surface-variant">{r.expiry || '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  {rows.length > 50 && <p className="text-[11px] text-on-surface-variant mt-1">Showing first 50 of {rows.length}.</p>}
                </div>
              )}
            </>
          )}

          {result && !result.error && (
            <div>
              <div className="flex items-center gap-2 mb-3">
                <Icon name="check_circle" filled className="text-secondary text-[22px]!" />
                <p className="text-base font-semibold text-on-surface">Imported {result.created} subscriber{result.created === 1 ? '' : 's'}</p>
              </div>
              <ul className="text-sm text-on-surface-variant space-y-1">
                {result.generatedPasswords > 0 && <li>· {result.generatedPasswords} password{result.generatedPasswords === 1 ? '' : 's'} were auto-generated (edit each subscriber to view/set them).</li>}
                {result.failed > 0 && <li className="text-error">· {result.failed} row{result.failed === 1 ? '' : 's'} failed:</li>}
              </ul>
              {result.failed > 0 && (
                <div className="mt-2 border border-outline-variant rounded-lg max-h-40 overflow-y-auto">
                  <table className="w-full text-xs">
                    <tbody>
                      {result.errors.map((e, i) => (
                        <tr key={i} className="border-t border-outline-variant/50 first:border-t-0">
                          <td className="px-3 py-1.5 text-on-surface-variant">Row {e.row}</td>
                          <td className="px-3 py-1.5 font-mono text-on-surface-variant">{e.pppoeUsername || '—'}</td>
                          <td className="px-3 py-1.5 text-error">{e.message}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
          {result && result.error && <p className="text-sm text-error">{result.error}</p>}
        </div>

        <div className="p-6 border-t border-outline-variant/50 flex justify-end gap-2 shrink-0">
          {!result ? (
            <>
              <button onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold border border-outline-variant text-on-surface hover:bg-surface-container cursor-pointer">Cancel</button>
              <button onClick={doImport} disabled={busy || rows.length === 0} className="px-4 h-10 rounded-md text-sm font-semibold bg-primary text-on-primary hover:opacity-90 disabled:opacity-50 cursor-pointer">
                {busy ? 'Importing…' : `Import ${rows.length || ''} subscriber${rows.length === 1 ? '' : 's'}`}
              </button>
            </>
          ) : (
            <button onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold bg-primary text-on-primary hover:opacity-90 cursor-pointer">Done</button>
          )}
        </div>
      </div>
    </div>
  )
}

function Subscribers({ auth }) {
  const [subs, setSubs] = useState(null)
  const [modal, setModal] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [actionId, setActionId] = useState(null) // row with the payment form open
  const [months, setMonths] = useState(1)
  const [msg, setMsg] = useState(null)
  const [deleteId, setDeleteId] = useState(null)
  const [detailId, setDetailId] = useState(null)

  const load = () => api('/admin/subscribers', { auth }).then(setSubs).catch(() => setSubs([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (subs === null) return <Skeleton className="h-64" />

  const active = subs.filter((s) => s.status === 'ACTIVE')
  const mrr = active.reduce((a, s) => a + Number(s.monthlyFee), 0)
  const expiring = active.filter((s) => (new Date(s.paidUntil) - Date.now()) / 86400000 <= 3).length

  async function act(path, body) {
    setMsg(null)
    try {
      const r = await api(path, { method: body ? 'POST' : 'PATCH', auth, body })
      if (r?.message) setMsg({ ok: true, text: r.message })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  return (
    <div>
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2.5 mb-4">
        <div>
          <h2 className="text-xl font-semibold tracking-tight text-on-surface">Subscribers</h2>
          <p className="text-xs text-on-surface-variant">Monthly PPPoE home &amp; office customers.</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setImportOpen(true)}
            className="border border-outline-variant text-on-surface text-sm font-semibold px-4 h-10 rounded-md flex items-center gap-1.5 hover:bg-surface-container transition-colors active:scale-[0.98] whitespace-nowrap cursor-pointer"
          >
            <Icon name="download" />
            Import CSV
          </button>
          <button
            onClick={() => setModal(true)}
            className="bg-primary text-on-primary text-sm font-semibold px-4 h-10 rounded-md flex items-center gap-1.5 hover:opacity-90 transition-opacity active:scale-[0.98] whitespace-nowrap cursor-pointer"
          >
            <Icon name="add" />
            Add Subscriber
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5 mb-4">
        {[
          ['Active', active.length, 'border-l-primary'],
          ['Suspended', subs.length - active.length, ''],
          ['Expiring ≤3 days', expiring, 'border-l-[#f59e0b]'],
          ['Monthly Revenue', fmtKES(mrr), 'border-l-secondary'],
        ].map(([label, value, accent]) => (
          <div key={label} className={`bg-surface-container-lowest px-3.5 py-2.5 rounded-md border border-outline-variant ${accent ? `border-l-2 ${accent}` : ''}`}>
            <CardLabel>{label}</CardLabel>
            <div className="text-[22px] leading-tight font-semibold mt-0.5 text-on-surface tabular-nums">{value}</div>
          </div>
        ))}
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[900px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="border-b border-outline-variant/50">Customer</th>
                <th className="border-b border-outline-variant/50">PPPoE Login</th>
                <th className="border-b border-outline-variant/50">Package</th>
                <th className="border-b border-outline-variant/50">Paid Until</th>
                <th className="border-b border-outline-variant/50">Last Payment</th>
                <th className="border-b border-outline-variant/50">Status</th>
                <th className="border-b border-outline-variant/50 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm">
              {subs.map((s) => {
                const st = subscriberState(s)
                const days = Math.floor((new Date(s.paidUntil) - Date.now()) / 86400000)
                return (
                  <tr key={s.id} className="border-b border-outline-variant/30 hover:bg-surface-container-low/20 transition-colors align-top">
                    <td className="">
                      <button onClick={() => setDetailId(s.id)} className="text-base font-semibold text-primary hover:underline text-left cursor-pointer">
                        {s.fullName}
                      </button>
                      <div className="text-xs text-on-surface-variant mt-0.5">{s.phoneNumber}</div>
                      {s.createdBy && <div className="text-xs text-on-surface-variant mt-0.5 capitalize">added by {s.createdBy}</div>}
                    </td>
                    <td className="font-mono">{s.pppoeUsername}</td>
                    <td className="">
                      <div className="tabular-nums font-semibold">{fmtKES(s.monthlyFee)}/mo</div>
                      {s.bandwidth && (
                        <div className="flex items-center gap-1 text-xs text-on-surface-variant mt-0.5">
                          <Icon name="speed" className="text-[14px]!" /> {speedLabel(s.bandwidth)}
                        </div>
                      )}
                    </td>
                    <td className="">
                      <div className="whitespace-nowrap">{fmtDate(s.paidUntil)}</div>
                      <div className={`text-xs mt-0.5 ${days < 0 ? 'text-error font-semibold' : days <= 3 ? 'text-[#b45309] font-semibold' : 'text-on-surface-variant'}`}>
                        {days < 0 ? `${-days} day${days === -1 ? '' : 's'} overdue` : `${days} day${days === 1 ? '' : 's'} left`}
                      </div>
                    </td>
                    <td className="">
                      {s.lastPaymentMethod ? (
                        <>
                          <span className={`text-xs font-semibold tracking-wider px-2 py-0.5 rounded-full ${
                            s.lastPaymentMethod === 'MPESA' ? 'bg-secondary-container text-on-secondary-container' : 'bg-surface-container-high text-on-surface-variant'
                          }`}>
                            {s.lastPaymentMethod === 'MPESA' ? 'M-PESA' : 'CASH'}
                          </span>
                          {s.lastPaymentAt && <div className="text-xs text-on-surface-variant mt-1">{fmtDate(s.lastPaymentAt)}</div>}
                        </>
                      ) : (
                        <span className="text-on-surface-variant text-xs">No payment yet</span>
                      )}
                    </td>
                    <td className="">
                      <span className={`text-xs font-semibold tracking-wider px-2.5 py-1 rounded-full whitespace-nowrap ${st.cls}`}>{st.label}</span>
                    </td>
                    <td className="text-right">
                      <div className="flex items-center justify-end gap-2 flex-wrap">
                        <button
                          onClick={() => setDetailId(s.id)}
                          className="px-3 py-1.5 rounded-lg border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container transition-colors cursor-pointer"
                        >
                          Details
                        </button>
                        <button
                          onClick={() => { setActionId(actionId === s.id ? null : s.id); setMonths(1); setDeleteId(null) }}
                          className="px-3 py-1.5 rounded-md border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer"
                        >
                          Take Payment
                        </button>
                        {s.status === 'ACTIVE' ? (
                          <button onClick={() => act(`/admin/subscribers/${s.id}/suspend`)} className="px-3 py-1.5 rounded-lg border border-error text-error text-xs font-semibold hover:bg-error/5 transition-colors cursor-pointer">
                            Suspend
                          </button>
                        ) : (
                          <button onClick={() => act(`/admin/subscribers/${s.id}/activate`)} className="px-3 py-1.5 rounded-lg border border-primary text-primary text-xs font-semibold hover:bg-primary/5 transition-colors cursor-pointer">
                            Reactivate
                          </button>
                        )}
                        <button onClick={() => { setDeleteId(deleteId === s.id ? null : s.id); setActionId(null) }} className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer" aria-label={`Remove ${s.fullName}`}>
                          <Icon name="delete" className="text-[18px]!" />
                        </button>
                      </div>
                      {actionId === s.id && (
                        <div className="flex items-center gap-2 mt-3 justify-end flex-wrap">
                          <label className="text-xs text-on-surface-variant">Months:</label>
                          <input
                            type="number"
                            min="1"
                            max="12"
                            value={months}
                            onChange={(e) => setMonths(e.target.value)}
                            className="h-9 w-16 bg-surface border border-outline-variant rounded-lg px-2 text-sm text-center tabular-nums focus:outline-none focus:border-primary"
                          />
                          <span className="text-xs font-semibold text-primary tabular-nums">= {fmtKES(Number(s.monthlyFee) * (Number(months) || 0))}</span>
                          <button
                            onClick={() => { act(`/admin/subscribers/${s.id}/payments`, { months: Number(months) }); setActionId(null) }}
                            className="h-9 px-3 rounded-lg bg-secondary text-on-secondary text-xs font-semibold cursor-pointer"
                          >
                            Record Cash
                          </button>
                          <button
                            onClick={() => { act(`/admin/subscribers/${s.id}/stk`, { months: Number(months) }); setActionId(null) }}
                            className="h-9 px-3 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer"
                          >
                            Send M-Pesa STK
                          </button>
                        </div>
                      )}
                      {deleteId === s.id && (
                        <div className="flex items-center gap-2 mt-3 justify-end">
                          <span className="text-sm text-on-surface-variant">Remove <strong className="text-on-surface">{s.fullName}</strong>?</span>
                          <button
                            onClick={() => { api(`/admin/subscribers/${s.id}`, { method: 'DELETE', auth }).then(() => { setDeleteId(null); load() }).catch((err) => setMsg({ ok: false, text: err.message })) }}
                            className="h-9 px-4 rounded-lg bg-error text-on-error text-sm font-semibold cursor-pointer"
                          >
                            Yes, remove
                          </button>
                          <button onClick={() => setDeleteId(null)} className="h-9 px-4 rounded-lg border border-outline-variant text-on-surface text-sm font-semibold cursor-pointer">
                            Cancel
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
              {subs.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={7}>No subscribers yet — add your first monthly customer.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {modal && <SubscriberModal auth={auth} onClose={() => setModal(false)} onSaved={() => { setModal(false); load() }} />}
      {importOpen && <ImportSubscribersModal auth={auth} onClose={() => setImportOpen(false)} onDone={() => load()} />}
      {detailId && subs.find((s) => s.id === detailId) && (
        <SubscriberDetail
          auth={auth}
          subscriber={subs.find((s) => s.id === detailId)}
          onClose={() => setDetailId(null)}
          onChanged={load}
        />
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Payments                                                            */
/* ------------------------------------------------------------------ */

const PAYMENT_FILTERS = ['All', 'Success', 'Pending', 'Failed']

function exportCsv(payments) {
  const header = 'Date,Phone,Plan,Amount,Status,MpesaReceipt,Voucher'
  const rows = payments.map((p) =>
    [new Date(p.createdAt).toISOString(), p.phoneNumber, p.plan?.name || '', p.amount, p.status, p.mpesaReceiptNumber || '', p.voucher?.code || '']
      .map((v) => `"${String(v).replace(/"/g, '""')}"`).join(',')
  )
  const blob = new Blob([[header, ...rows].join('\n')], { type: 'text/csv' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'payments.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

function PaymentDetail({ payment, onClose }) {
  const [copied, setCopied] = useState(false)
  const pill = PILL_STYLES[payment.status] || PILL_STYLES.PENDING

  function copyVoucher() {
    if (!payment.voucher?.code) return
    navigator.clipboard.writeText(payment.voucher.code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  const Row = ({ label, children }) => (
    <div className="flex justify-between items-center py-2 border-b border-outline-variant border-dashed gap-4">
      <span className="text-sm text-on-surface-variant shrink-0">{label}</span>
      {children}
    </div>
  )

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="flex-1 bg-on-background/30 backdrop-blur-[2px]" onClick={onClose}></div>
      <div className="w-full max-w-md bg-surface-container-lowest h-full shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col overflow-hidden">
        <div className="p-6 border-b border-outline-variant bg-surface-container-low flex justify-between items-start">
          <div>
            <h3 className="text-lg font-semibold text-on-surface">Transaction Details</h3>
            <p className="text-sm text-on-surface-variant mt-1 font-mono">{payment.mpesaReceiptNumber || `Payment #${payment.id}`}</p>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-full hover:bg-surface-container flex items-center justify-center text-on-surface-variant transition-colors cursor-pointer" aria-label="Close details">
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1 space-y-6">
          <div className={`flex flex-col items-center justify-center py-4 bg-surface-container-low rounded-md border-l-2 ${
            payment.status === 'SUCCESS' ? 'border-secondary' : payment.status === 'FAILED' ? 'border-error' : 'border-outline'
          }`}>
            <div className={`w-12 h-12 rounded-full ${pill.bg} ${pill.text} flex items-center justify-center mb-3`}>
              <Icon name={payment.status === 'SUCCESS' ? 'check_circle' : payment.status === 'FAILED' ? 'error' : 'pending'} className="text-[28px]!" />
            </div>
            <span className="text-lg font-bold text-on-surface">{fmtKES(payment.amount)}</span>
            <span className={`text-sm font-medium ${payment.status === 'SUCCESS' ? 'text-secondary' : payment.status === 'FAILED' ? 'text-error' : 'text-on-surface-variant'}`}>
              Payment {pill.label}
            </span>
          </div>

          <div>
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">Payment Information</h4>
            <Row label="Receipt No.">
              <span className="font-mono text-sm font-semibold text-on-surface">{payment.mpesaReceiptNumber || '—'}</span>
            </Row>
            <Row label="Phone">
              <span className="text-sm font-medium text-on-surface">{payment.phoneNumber}</span>
            </Row>
            <Row label="Timestamp">
              <span className="text-sm font-medium text-on-surface">{fmtDate(payment.createdAt)}, {fmtTime(payment.createdAt)}</span>
            </Row>
          </div>

          <div>
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">Service Provision</h4>
            <Row label="Purchased Plan">
              <span className="text-sm font-medium text-on-surface">{payment.plan?.name || '—'}</span>
            </Row>
            <Row label="Voucher Code">
              {payment.voucher?.code ? (
                <span className="flex items-center gap-2">
                  <span className="font-mono text-sm font-bold text-primary">{payment.voucher.code}</span>
                  <button onClick={copyVoucher} className="text-on-surface-variant hover:text-primary cursor-pointer" aria-label="Copy voucher code">
                    <Icon name={copied ? 'check' : 'content_copy'} className="text-[16px]!" />
                  </button>
                </span>
              ) : <span className="text-sm text-on-surface-variant">—</span>}
            </Row>
          </div>
        </div>
      </div>
    </div>
  )
}

function Payments({ auth }) {
  const [payments, setPayments] = useState(null)
  const [filter, setFilter] = useState('All')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState(null)

  useEffect(() => {
    api('/admin/payments', { auth }).then(setPayments).catch(() => setPayments([]))
  }, [auth])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return [...(payments || [])]
      .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
      .filter((p) => filter === 'All' || p.status === filter.toUpperCase())
      .filter((p) =>
        !q ||
        p.phoneNumber?.toLowerCase().includes(q) ||
        p.mpesaReceiptNumber?.toLowerCase().includes(q) ||
        p.voucher?.code?.toLowerCase().includes(q) ||
        p.plan?.name?.toLowerCase().includes(q)
      )
  }, [payments, filter, search])

  // A ledger with no totals is just a list. These are of what is shown, so
  // they answer the question the filter was asked.
  const totals = useMemo(() => {
    const t = { count: 0, collected: 0, pending: 0, failed: 0, pendingCount: 0, failedCount: 0 }
    for (const p of filtered) {
      t.count++
      const amount = Number(p.amount) || 0
      if (p.status === 'SUCCESS') t.collected += amount
      else if (p.status === 'PENDING') { t.pending += amount; t.pendingCount++ }
      else if (p.status === 'FAILED') { t.failed += amount; t.failedCount++ }
    }
    return t
  }, [filtered])

  if (payments === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Payments" subtitle="Every payment taken, on every rail, and how it ended.">
        <button
          onClick={() => exportCsv(filtered)}
          disabled={filtered.length === 0}
          className="flex items-center gap-1.5 px-3 h-10 border border-outline-variant rounded-md text-sm hover:bg-surface-container-high transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Icon name="download" className="text-[18px]!" /> Export
        </button>
      </PageHeader>

      {/* Money first, and only what the current filter covers. */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
        <StatCard
          label="Collected"
          value={fmtKES(totals.collected)}
          hint={`${filter === 'All' ? 'all' : filter.toLowerCase()} · ${totals.count} transaction${totals.count === 1 ? '' : 's'}`}
          accent="border-l-primary"
        />
        <StatCard
          label="Awaiting confirmation"
          value={fmtKES(totals.pending)}
          hint={totals.pendingCount > 0 ? `${totals.pendingCount} still open` : 'nothing hanging'}
        />
        <StatCard
          label="Failed"
          value={fmtKES(totals.failed)}
          hint={totals.failedCount > 0 ? `${totals.failedCount} never completed` : 'none'}
          accent={totals.failedCount > 0 ? 'border-l-error' : ''}
        />
        <StatCard
          label="Average sale"
          value={totals.collected > 0
            ? fmtKES(Math.round(totals.collected / Math.max(1, filtered.filter((p) => p.status === 'SUCCESS').length)))
            : '—'}
          hint="successful payments only"
        />
      </div>

      {/* One row of chrome, not two. */}
      <div className="flex flex-wrap items-center gap-2 mb-3">
        {PAYMENT_FILTERS.map((f) => {
          const n = f === 'All'
            ? (payments || []).length
            : (payments || []).filter((p) => p.status === f.toUpperCase()).length
          return (
            <button
              key={f}
              onClick={() => setFilter(f)}
              aria-pressed={filter === f}
              className={`px-3 h-8 rounded-md text-[13px] transition-colors cursor-pointer ${
                filter === f
                  ? 'bg-primary-container text-on-primary-container font-semibold'
                  : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
              }`}
            >
              {f} <span className="opacity-60 tabular-nums">{n}</span>
            </button>
          )
        })}
        <div className="relative ml-auto w-full sm:w-64">
          <Icon name="search" className="absolute left-2.5 top-1/2 -translate-y-1/2 text-on-surface-variant text-[18px]!" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-surface-container-lowest border border-outline-variant rounded-md pl-9 pr-3 h-8 text-[13px] focus:outline-none focus:border-primary transition-colors"
            placeholder="Receipt, phone, code or plan…"
            aria-label="Search payments"
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="border border-outline-variant rounded-lg p-10 text-center">
          <Icon name="receipt_long" className="text-[36px]! text-on-surface-variant/40" />
          <p className="mt-2 text-sm text-on-surface-variant">
            {payments.length === 0
              ? 'No payments yet. They appear here the moment a customer pays through the portal.'
              : `Nothing matches ${search ? `"${search}"` : `the ${filter.toLowerCase()} filter`}.`}
          </p>
          {payments.length > 0 && (
            <button
              onClick={() => { setFilter('All'); setSearch('') }}
              className="mt-3 text-[13px] text-primary hover:underline cursor-pointer"
            >
              Clear filters
            </button>
          )}
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full text-left min-w-[820px]">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Phone</th>
                  <th>Plan</th>
                  <th className="text-right">Amount</th>
                  <th>Receipt</th>
                  <th>Voucher</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((p) => (
                  <tr
                    key={p.id}
                    onClick={() => setSelected(p)}
                    className="cursor-pointer"
                  >
                    <td className="whitespace-nowrap">
                      <span>{fmtDate(p.createdAt)}</span>
                      <span className="text-on-surface-variant text-xs"> {fmtTime(p.createdAt)}</span>
                    </td>
                    <td className="font-mono text-[12px]">{p.phoneNumber}</td>
                    <td>{p.plan?.name || <span className="text-on-surface-variant">Custom</span>}</td>
                    <td className="text-right font-semibold tabular-nums">{fmtKES(p.amount)}</td>
                    <td className="font-mono text-[12px]">
                      {p.mpesaReceiptNumber || <span className="text-on-surface-variant">—</span>}
                    </td>
                    <td className="font-mono text-[12px]">
                      {p.voucher?.code || <span className="text-on-surface-variant">—</span>}
                    </td>
                    <td><StatusPill status={p.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="px-3 py-2 border-t border-outline-variant text-xs text-on-surface-variant">
            {filtered.length === payments.length
              ? `${payments.length} payment${payments.length === 1 ? '' : 's'}`
              : `${filtered.length} of ${payments.length} payments`}
            {totals.pendingCount > 0 && (
              <span className="text-[#FDBF2D]">
                {' '}· {totals.pendingCount} still awaiting an M-Pesa callback
              </span>
            )}
          </div>
        </div>
      )}

      {selected && <PaymentDetail payment={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Support tickets                                                     */
/* ------------------------------------------------------------------ */

const TICKET_FILTERS = { All: null, Open: 'OPEN', 'In Progress': 'IN_PROGRESS', Resolved: 'RESOLVED' }

const TICKET_STATUS = {
  OPEN: { label: 'Open', cls: 'bg-surface-container-highest text-on-surface' },
  IN_PROGRESS: { label: 'In Progress', cls: 'bg-primary-container text-on-primary-container' },
  RESOLVED: { label: 'Resolved', cls: 'bg-secondary-container text-on-secondary-container' },
}

const PRIORITY_BADGE = {
  HIGH: 'bg-error-container text-on-error-container',
  MEDIUM: 'bg-surface-variant text-on-surface-variant',
  LOW: 'bg-secondary-container text-on-secondary-container',
}

const REPLY_TEMPLATES = [
  { label: 'Payment Confirmation', text: 'We have confirmed your M-Pesa payment and your voucher is active. Use the code as both WiFi username and password. Thank you for choosing SPA WiFi!' },
  { label: 'Connection Troubleshooting', text: 'Sorry for the trouble. Please forget the SPA WiFi network on your device, reconnect, and log in with your voucher code again. If it still fails, reply here and we will check your access point.' },
  { label: 'Ticket Resolved', text: 'Glad we could help! We are marking this ticket as resolved. If the issue comes back, just reply and it will reopen with our team.' },
  { label: 'Maintenance Alert', text: 'Our network will undergo brief scheduled maintenance tonight. You may notice a short interruption; your remaining voucher time is not affected.' },
]

function initials(name) {
  return (name || '?').trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2).toUpperCase()
}

function TicketStatusPill({ status }) {
  const s = TICKET_STATUS[status] || TICKET_STATUS.OPEN
  return <span className={`px-2 py-1 rounded-full text-xs font-medium whitespace-nowrap ${s.cls}`}>{s.label}</span>
}

function PriorityBadge({ priority }) {
  return (
    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${PRIORITY_BADGE[priority] || PRIORITY_BADGE.MEDIUM}`}>
      {priority?.toLowerCase()}
    </span>
  )
}

/** Minutes as something a person reads at a glance: "2h 15m", "3 days". */
function humanMinutes(mins) {
  if (mins === null || mins === undefined) return 'No data yet'
  if (mins < 1) return 'Under a minute'
  if (mins < 60) return `${mins}m`
  if (mins < 1440) return `${Math.floor(mins / 60)}h ${mins % 60}m`
  const days = Math.floor(mins / 1440)
  const hours = Math.floor((mins % 1440) / 60)
  return `${days}d ${hours}h`
}

function TicketAnalytics({ auth }) {
  const [data, setData] = useState(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    api('/admin/tickets/analytics', { auth }).then(setData).catch(() => setFailed(true))
  }, [auth])

  if (failed) {
    return (
      <div className="p-8 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
        <p className="text-on-surface-variant">Could not load ticket analytics. Is the server running?</p>
      </div>
    )
  }
  if (!data) return <Skeleton className="h-72" />

  const peak = Math.max(1, ...data.perDay.map((d) => d.count))
  const statusColours = {
    OPEN: 'bg-[#f59e0b]',
    IN_PROGRESS: 'bg-primary',
    RESOLVED: 'bg-secondary',
  }
  const priorityColours = { HIGH: 'bg-error', MEDIUM: 'bg-[#f59e0b]', LOW: 'bg-secondary' }

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <KpiCard label="Opened This Week" icon="inbox" value={data.openedLast7Days} />
        <KpiCard
          label="Awaiting First Reply"
          icon="hourglass_empty"
          value={data.awaitingFirstReply}
          accent={data.awaitingFirstReply > 0 ? 'border-l-error' : undefined}
        />
        <KpiCard label="Median First Reply" icon="schedule" value={humanMinutes(data.medianFirstReplyMinutes)} />
        <KpiCard label="Average Time To Resolve" icon="task_alt" value={humanMinutes(data.avgResolveMinutes)} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
          <CardLabel>Tickets opened, last 14 days</CardLabel>
          <div className="mt-4 flex items-end gap-1.5 h-32">
            {data.perDay.map((d) => (
              <div key={d.date} className="flex-1 flex flex-col items-center justify-end h-full gap-1"
                title={`${d.count} on ${d.date}`}>
                <span className="text-[10px] text-on-surface-variant">{d.count || ''}</span>
                <div
                  className={`w-full rounded-t ${d.count ? 'bg-primary' : 'bg-surface-container-high'}`}
                  style={{ height: `${Math.max(4, (d.count / peak) * 100)}%` }}
                />
              </div>
            ))}
          </div>
          <div className="mt-2 flex justify-between text-[10px] text-on-surface-variant">
            <span>{data.perDay[0]?.date}</span>
            <span>Today</span>
          </div>
        </div>

        <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant space-y-5">
          <div>
            <CardLabel>By status</CardLabel>
            <div className="mt-3 space-y-2">
              {Object.entries(data.byStatus).map(([status, count]) => (
                <div key={status} className="flex items-center gap-3">
                  <span className="text-sm w-28 shrink-0">{status.replace(/_/g, ' ').toLowerCase()}</span>
                  <div className="flex-1 h-2 rounded-full bg-surface-container-high overflow-hidden">
                    <div className={`h-full rounded-full ${statusColours[status] || 'bg-primary'}`}
                      style={{ width: `${data.total ? (count / data.total) * 100 : 0}%` }} />
                  </div>
                  <span className="text-sm font-semibold w-8 text-right">{count}</span>
                </div>
              ))}
            </div>
          </div>
          <div>
            <CardLabel>By priority</CardLabel>
            <div className="mt-3 space-y-2">
              {Object.entries(data.byPriority).map(([priority, count]) => (
                <div key={priority} className="flex items-center gap-3">
                  <span className="text-sm w-28 shrink-0">{priority.toLowerCase()}</span>
                  <div className="flex-1 h-2 rounded-full bg-surface-container-high overflow-hidden">
                    <div className={`h-full rounded-full ${priorityColours[priority] || 'bg-primary'}`}
                      style={{ width: `${data.total ? (count / data.total) * 100 : 0}%` }} />
                  </div>
                  <span className="text-sm font-semibold w-8 text-right">{count}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
        <CardLabel>What customers complain about most</CardLabel>
        {data.topSubjects.length === 0 ? (
          <p className="mt-3 text-sm text-on-surface-variant">No tickets yet.</p>
        ) : (
          <ul className="mt-3 divide-y divide-outline-variant/30">
            {data.topSubjects.map((s) => (
              <li key={s.subject} className="py-2.5 flex items-center justify-between gap-4">
                <span className="text-sm">{s.subject}</span>
                <span className="text-sm font-semibold text-on-surface-variant shrink-0">
                  {s.count} {s.count === 1 ? 'ticket' : 'tickets'}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

/** Avatar stack for whoever is on a job, with a "+N" once it gets crowded. */
function AssigneeChips({ ids = [], technicians, empty = 'Unassigned' }) {
  if (!ids.length) {
    return <span className="text-xs text-on-surface-variant">{empty}</span>
  }
  const named = ids.map((id) => technicians.find((t) => String(t.id) === String(id))).filter(Boolean)
  const shown = named.slice(0, 3)
  return (
    <span className="flex items-center gap-1" title={named.map((t) => t.fullName).join(', ')}>
      {shown.map((t) => (
        <span key={t.id} className="w-6 h-6 rounded-full bg-primary-container text-on-primary-container text-[10px] font-bold flex items-center justify-center">
          {initials(t.fullName)}
        </span>
      ))}
      {named.length > shown.length && (
        <span className="text-xs text-on-surface-variant">+{named.length - shown.length}</span>
      )}
      {named.length === 0 && <span className="text-xs text-on-surface-variant">{ids.length} assigned</span>}
    </span>
  )
}

/** Pick any number of technicians for a ticket; empty means back in the pool. */
function AssigneePicker({ technicians, value, onChange, disabled }) {
  const selected = value.map(String)
  const available = technicians.filter((t) => t.active)
  if (available.length === 0) {
    return (
      <p className="text-xs text-on-surface-variant">
        No active technicians yet — add one under Organisation → Team.
      </p>
    )
  }
  return (
    <div className="flex flex-wrap gap-2">
      {available.map((t) => {
        const on = selected.includes(String(t.id))
        return (
          <button
            key={t.id}
            type="button"
            disabled={disabled}
            aria-pressed={on}
            onClick={() => onChange(on
              ? value.filter((id) => String(id) !== String(t.id))
              : [...value, t.id])}
            className={'px-3 py-1.5 rounded-full text-xs font-semibold cursor-pointer transition-colors disabled:opacity-60 ' + (
              on
                ? 'bg-primary text-on-primary'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            )}
          >
            {on ? '\u2713 ' : ''}{t.fullName}
          </button>
        )
      })}
    </div>
  )
}

/** Staff raising a ticket themselves — a walk-in, or a fault we spotted. */
function NewTicketForm({ auth, technicians, onCancel, onCreated }) {
  const [form, setForm] = useState({
    customerName: '', phoneNumber: '', subject: '', message: '', priority: 'MEDIUM',
  })
  const [assignees, setAssignees] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/admin/tickets', {
        method: 'POST',
        auth,
        body: {
          ...form,
          phoneNumber: form.phoneNumber.replace(/\D/g, ''),
          assigneeIds: assignees.map(Number),
        },
      })
      onCreated()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mb-6 p-5 rounded-lg bg-surface-container-lowest border border-outline-variant space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div>
          <label className={LABEL_CLS}>Customer name</label>
          <input className={INPUT_CLS} required value={form.customerName} onChange={(e) => setForm({ ...form, customerName: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Phone</label>
          <input className={INPUT_CLS} required placeholder="0712345678" value={form.phoneNumber} onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })} />
        </div>
        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Subject</label>
          <input className={INPUT_CLS} required placeholder="e.g. No connection since morning" value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} />
        </div>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="md:col-span-3">
          <label className={LABEL_CLS}>What is the problem?</label>
          <textarea className={INPUT_CLS + ' min-h-[80px]'} required value={form.message} onChange={(e) => setForm({ ...form, message: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Priority</label>
          <select className={INPUT_CLS} value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })}>
            <option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option>
            <option value="HIGH">High</option>
          </select>
        </div>
      </div>
      <div>
        <label className={LABEL_CLS}>Assign to</label>
        <AssigneePicker technicians={technicians} value={assignees} onChange={setAssignees} />
        <p className="text-xs text-on-surface-variant mt-2">
          {assignees.length === 0
            ? 'Leave empty to triage later. Whoever you pick gets a text with the details.'
            : `${assignees.length} technician${assignees.length > 1 ? 's' : ''} will be texted the job details.`}
        </p>
      </div>
      {error && <p className="text-sm text-error">{error}</p>}
      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Creating\u2026' : 'Create ticket'}</PrimaryButton>
        <button type="button" onClick={onCancel} className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
    </form>
  )
}

function Support({ auth }) {
  const [view, setView] = useState('inbox')
  const [tickets, setTickets] = useState(null)
  const [technicians, setTechnicians] = useState([])
  const [showNew, setShowNew] = useState(false)
  const [savingAssignees, setSavingAssignees] = useState(false)
  const [assignError, setAssignError] = useState(null)
  const [filter, setFilter] = useState('All')
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState(null)
  const [reply, setReply] = useState('')
  const [sending, setSending] = useState(false)
  const [showTemplates, setShowTemplates] = useState(false)

  const load = () => api('/admin/tickets', { auth }).then(setTickets).catch(() => setTickets([]))
  useEffect(() => {
    load()
    api('/admin/technicians', { auth }).then(setTechnicians).catch(() => setTechnicians([]))
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function setAssignees(ticketId, assigneeIds) {
    setSavingAssignees(true)
    setAssignError(null)
    try {
      await api(`/admin/tickets/${ticketId}/assignees`, {
        method: 'PATCH', auth, body: { assigneeIds: assigneeIds.map(Number) },
      })
      await load()
    } catch (err) {
      // Without this the picker kept showing the name it had optimistically
      // ticked, so the ticket read as assigned while the server had nothing —
      // and the technician was blamed for ignoring a job never given to them.
      setAssignError(err.message)
      // Put the display back in step with whatever the server actually holds.
      await load().catch(() => {})
    } finally {
      setSavingAssignees(false)
    }
  }

  const list = useMemo(() => {
    const q = search.trim().toLowerCase()
    return (tickets || [])
      .filter((t) => !TICKET_FILTERS[filter] || t.status === TICKET_FILTERS[filter])
      .filter((t) =>
        !q ||
        t.subject?.toLowerCase().includes(q) ||
        t.customerName?.toLowerCase().includes(q) ||
        t.phoneNumber?.includes(q)
      )
  }, [tickets, filter, search])

  const selected = (tickets || []).find((t) => t.id === selectedId)

  async function send() {
    if (!reply.trim() || !selected) return
    setSending(true)
    try {
      await api(`/admin/tickets/${selected.id}/reply`, { method: 'POST', auth, body: { body: reply.trim() } })
      setReply('')
      await load()
    } catch { /* keep draft on failure */ } finally {
      setSending(false)
    }
  }

  async function setStatus(status) {
    await api(`/admin/tickets/${selected.id}/status`, { method: 'PATCH', auth, body: { status } }).catch(() => {})
    load()
  }

  if (tickets === null) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        <Skeleton className="h-96 lg:col-span-2" />
        <Skeleton className="h-96 lg:col-span-3" />
      </div>
    )
  }

  return (
    <div>
      <div className="mb-6 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-semibold tracking-tight text-on-surface">Support Tickets</h2>
          <p className="text-xs text-on-surface-variant">Help customers with connection and payment issues.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {view === 'inbox' && Object.keys(TICKET_FILTERS).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-4 py-1.5 rounded-full text-sm whitespace-nowrap transition-colors cursor-pointer ${
                filter === f
                  ? 'bg-primary-container text-on-primary-container font-semibold'
                  : 'bg-surface-container border border-outline-variant text-on-surface-variant hover:bg-surface-container-high'
              }`}
            >
              {f}
            </button>
          ))}
          <PrimaryButton onClick={() => { setView('inbox'); setShowNew((v) => !v) }}>
            <Icon name="add" /> New Ticket
          </PrimaryButton>
          <div className="flex rounded-full border border-outline-variant overflow-hidden ml-1">
            {[['inbox', 'Inbox', 'inbox'], ['analytics', 'Analytics', 'insights']].map(([key, label, icon]) => (
              <button
                key={key}
                onClick={() => setView(key)}
                aria-pressed={view === key}
                className={`px-4 py-1.5 text-sm flex items-center gap-1.5 cursor-pointer transition-colors ${
                  view === key
                    ? 'bg-inverse-surface text-primary-fixed font-semibold'
                    : 'text-on-surface-variant hover:bg-surface-container-high'
                }`}
              >
                <Icon name={icon} className="text-[16px]!" /> {label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {view === 'inbox' && showNew && (
        <NewTicketForm
          auth={auth}
          technicians={technicians}
          onCancel={() => setShowNew(false)}
          onCreated={() => { setShowNew(false); load() }}
        />
      )}

      {view === 'analytics' && <TicketAnalytics auth={auth} />}

      <div className={`grid grid-cols-1 lg:grid-cols-5 gap-6 items-start ${view === 'analytics' ? 'hidden' : ''}`}>
        {/* Ticket list */}
        <div className="lg:col-span-2 bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="p-4 border-b border-outline-variant/30">
            <div className="relative">
              <Icon name="search" className="absolute left-3 top-1/2 -translate-y-1/2 text-on-surface-variant text-[20px]!" />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-full bg-surface-container-low border border-outline-variant rounded-full py-2 pl-10 pr-4 text-sm focus:outline-none focus:border-primary focus:ring-1 focus:ring-primary transition-colors"
                placeholder="Search tickets..."
                type="text"
              />
            </div>
          </div>
          <div className="max-h-[65vh] overflow-y-auto">
            {list.map((t) => (
              <button
                key={t.id}
                onClick={() => { setSelectedId(t.id); setShowTemplates(false) }}
                className={`w-full text-left p-4 border-b border-outline-variant/20 hover:bg-surface-container transition-colors cursor-pointer relative ${
                  selectedId === t.id ? 'bg-primary-container/10' : ''
                }`}
              >
                {selectedId === t.id && <div className="absolute left-0 top-0 bottom-0 w-1 bg-primary"></div>}
                <div className="flex justify-between items-start mb-2">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-semibold tracking-wider text-on-surface-variant">#TCK-{t.id}</span>
                    <PriorityBadge priority={t.priority} />
                  </div>
                  <span className="text-xs text-on-surface-variant">{relativeTime(t.updatedAt)}</span>
                </div>
                <h3 className="text-lg font-semibold text-on-surface mb-1 truncate">{t.subject}</h3>
                <div className="flex justify-between items-end gap-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <span className="w-6 h-6 rounded-full bg-surface-variant flex items-center justify-center text-xs font-bold text-on-surface-variant shrink-0">
                      {initials(t.customerName)}
                    </span>
                    <span className="text-sm text-on-surface-variant truncate">{t.customerName} ({t.phoneNumber})</span>
                  </div>
                  <TicketStatusPill status={t.status} />
                  <AssigneeChips ids={t.assigneeIds} technicians={technicians} />
                </div>
              </button>
            ))}
            {list.length === 0 && (
              <div className="p-8 text-center text-on-surface-variant text-sm">
                No tickets{filter !== 'All' ? ` with status "${filter}"` : ''} yet.
              </div>
            )}
          </div>
        </div>

        {/* Detail / conversation */}
        <div className="lg:col-span-3 bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden flex flex-col min-h-[400px]">
          {!selected ? (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 p-8 text-center">
              <Icon name="support_agent" className="text-[48px]! text-outline" />
              <p className="text-on-surface-variant">Select a ticket to read the conversation and reply.</p>
            </div>
          ) : (
            <>
              <div className="p-5 border-b border-outline-variant/30">
                <div className="flex justify-between items-start mb-3">
                  <div className="flex gap-2 items-center">
                    <span className="text-sm font-semibold tracking-wider text-on-surface-variant">#TCK-{selected.id}</span>
                    <PriorityBadge priority={selected.priority} />
                  </div>
                  <TicketStatusPill status={selected.status} />
                </div>
                <h2 className="text-xl font-bold text-on-surface mb-4">{selected.subject}</h2>
                <div className="flex items-center gap-4 text-sm text-on-surface-variant bg-surface-container-low p-3 rounded-lg">
                  <div className="flex items-center gap-2">
                    <span className="w-8 h-8 rounded-full bg-surface-variant flex items-center justify-center text-sm font-bold text-on-surface-variant">
                      {initials(selected.customerName)}
                    </span>
                    <div>
                      <p className="font-medium text-on-surface">{selected.customerName}</p>
                      <p className="text-xs">{selected.phoneNumber}</p>
                    </div>
                  </div>
                  <div className="h-8 w-px bg-outline-variant/50"></div>
                  <div>
                    <p className="text-xs">Opened</p>
                    <p className="font-medium text-on-surface">{fmtDate(selected.createdAt)}, {fmtTime(selected.createdAt)}</p>
                    {selected.createdBy && <p className="text-xs">Raised by {selected.createdBy}</p>}
                  </div>
                </div>

                <div className="mt-4">
                  <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">
                    Assigned to
                  </p>
                  <AssigneePicker
                    technicians={technicians}
                    value={selected.assigneeIds || []}
                    disabled={savingAssignees}
                    onChange={(ids) => setAssignees(selected.id, ids)}
                  />
                  {assignError ? (
                    <p className="text-xs text-[#b91c1c] mt-2 flex items-start gap-1.5">
                      <Icon name="error" className="text-[15px]! mt-0.5" />
                      Not saved — {assignError}
                    </p>
                  ) : (
                    <p className="text-xs text-on-surface-variant mt-2">
                      {(selected.assigneeIds || []).length === 0
                        ? 'Nobody is on this yet. Picking someone moves it to In Progress and texts them.'
                        : 'Tap a name to add or remove. New assignees get a text with the job details.'}
                    </p>
                  )}
                </div>
              </div>

              <div className="flex-1 overflow-y-auto p-5 space-y-5 max-h-[45vh]">
                {selected.messages?.map((m) => (
                  <div key={m.id} className={`flex gap-3 max-w-[85%] ${m.fromAdmin ? 'ml-auto flex-row-reverse' : ''}`}>
                    <span className={`w-8 h-8 rounded-full shrink-0 flex items-center justify-center text-xs font-bold mt-1 ${
                      m.fromAdmin ? 'bg-primary text-on-primary' : 'bg-surface-variant text-on-surface-variant'
                    }`}>
                      {m.fromAdmin ? 'SW' : initials(selected.customerName)}
                    </span>
                    <div className={`p-4 rounded-2xl ${
                      m.fromAdmin
                        ? 'bg-primary-container text-on-primary-container rounded-tr-sm'
                        : 'bg-surface-container-low border border-outline-variant/20 rounded-tl-sm'
                    }`}>
                      <p className="text-sm whitespace-pre-wrap">{m.body}</p>
                      <p className={`text-[10px] mt-2 ${m.fromAdmin ? 'opacity-70 text-left' : 'text-on-surface-variant text-right'}`}>
                        {fmtDate(m.createdAt)}, {fmtTime(m.createdAt)}
                      </p>
                    </div>
                  </div>
                ))}
              </div>

              <SuggestedReply
                ticket={selected}
                auth={auth}
                onUse={(text) => setReply(text)}
              />

              <div className="p-4 border-t border-outline-variant/30">
                <div className="border border-outline-variant rounded-lg bg-surface focus-within:border-primary focus-within:ring-1 focus-within:ring-primary transition-all overflow-hidden">
                  <textarea
                    value={reply}
                    onChange={(e) => setReply(e.target.value)}
                    className="w-full p-3 bg-transparent border-none resize-none focus:outline-none text-sm text-on-surface"
                    placeholder="Type your reply here..."
                    rows="3"
                  />
                  <div className="bg-surface-container-low px-3 py-2 flex justify-between items-center border-t border-outline-variant/30">
                    <div className="relative">
                      <button
                        onClick={() => setShowTemplates(!showTemplates)}
                        className="p-1.5 text-on-surface-variant hover:text-primary transition-colors rounded hover:bg-surface-container flex items-center gap-1 cursor-pointer"
                      >
                        <Icon name="bolt" className="text-[18px]!" />
                        <span className="text-xs font-medium">Quick Reply</span>
                      </button>
                      {showTemplates && (
                        <div className="absolute bottom-full left-0 mb-2 w-64 bg-surface-container-lowest border border-outline-variant rounded-lg  z-50 overflow-hidden">
                          <div className="p-2 border-b border-outline-variant/20 bg-surface-container-low">
                            <span className="text-[10px] font-bold uppercase tracking-wider text-on-surface-variant px-2">Templates</span>
                          </div>
                          {REPLY_TEMPLATES.map((tpl) => (
                            <button
                              key={tpl.label}
                              onClick={() => { setReply(tpl.text); setShowTemplates(false) }}
                              className="w-full text-left px-4 py-2 text-sm text-on-surface hover:bg-primary/5 transition-colors cursor-pointer"
                            >
                              {tpl.label}
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                    <div className="flex gap-2">
                      {selected.status !== 'RESOLVED' ? (
                        <button onClick={() => setStatus('RESOLVED')} className="px-4 py-1.5 rounded-lg border border-primary text-primary text-sm font-semibold hover:bg-primary/5 transition-colors cursor-pointer">
                          Resolve
                        </button>
                      ) : (
                        <button onClick={() => setStatus('OPEN')} className="px-4 py-1.5 rounded-lg border border-primary text-primary text-sm font-semibold hover:bg-primary/5 transition-colors cursor-pointer">
                          Reopen
                        </button>
                      )}
                      <button
                        onClick={send}
                        disabled={sending || !reply.trim()}
                        className="px-4 py-1.5 rounded-lg bg-primary text-on-primary text-sm font-semibold hover:opacity-90 transition-opacity flex items-center gap-1 disabled:opacity-50 cursor-pointer"
                      >
                        {sending ? 'Sending…' : 'Send'} <Icon name="send" className="text-[16px]!" />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * The suggested first reply, and — collapsed underneath it — the facts it was
 * written from. The facts are shown deliberately: an agent who can see that
 * the draft says "your subscription expired on 3 Aug" *because* the record
 * says so will trust it where it is right and catch it where it is wrong.
 * Sending is still a human pressing Send; "Use this" only fills the box.
 */
function SuggestedReply({ ticket, auth, onUse }) {
  const [state, setState] = useState(null)
  const [busy, setBusy] = useState(false)
  const [showBasis, setShowBasis] = useState(false)

  // A different ticket is a different draft; keep whatever the sweep left.
  useEffect(() => {
    setState(ticket.aiDraft
      ? { draft: ticket.aiDraft, basis: (ticket.aiDraftBasis || '').split('\n').filter(Boolean), drafted: true }
      : null)
    setShowBasis(false)
  }, [ticket.id, ticket.aiDraft, ticket.aiDraftBasis])

  async function suggest() {
    setBusy(true)
    try {
      setState(await api(`/admin/tickets/${ticket.id}/draft`, { method: 'POST', auth }))
    } catch (e) {
      setState({ draft: null, basis: [], drafted: false, error: e.message })
    } finally {
      setBusy(false)
    }
  }

  if (!state) {
    return (
      <div className="px-4 pt-3">
        <button
          onClick={suggest}
          disabled={busy}
          className="text-xs font-semibold text-primary hover:underline flex items-center gap-1 cursor-pointer disabled:opacity-50"
        >
          <Icon name="auto_awesome" className="text-[16px]!" />
          {busy ? 'Looking it up…' : 'Suggest a reply'}
        </button>
      </div>
    )
  }

  return (
    <div className="mx-4 mt-3 rounded-lg border border-primary/30 bg-primary/5 overflow-hidden">
      <div className="px-3 py-2 flex items-center justify-between gap-2 border-b border-primary/20">
        <span className="text-[11px] font-bold uppercase tracking-wider text-primary flex items-center gap-1">
          <Icon name="auto_awesome" className="text-[14px]!" /> Suggested reply
        </span>
        <div className="flex items-center gap-2">
          <button onClick={suggest} disabled={busy}
            className="text-xs text-on-surface-variant hover:text-primary cursor-pointer disabled:opacity-50">
            {busy ? 'Redrafting…' : 'Redraft'}
          </button>
          {state.draft && (
            <button onClick={() => onUse(state.draft)}
              className="px-3 py-1 rounded-lg bg-primary text-on-primary text-xs font-semibold hover:opacity-90 cursor-pointer">
              Use this
            </button>
          )}
        </div>
      </div>

      <div className="p-3">
        {state.draft
          ? <p className="text-sm whitespace-pre-wrap text-on-surface">{state.draft}</p>
          : (
            <p className="text-xs text-on-surface-variant">
              {state.error || 'No wording was drafted — the assistant is off or could not be reached. The facts below still stand.'}
            </p>
          )}

        {state.basis?.length > 0 && (
          <div className="mt-3 pt-2 border-t border-primary/15">
            <button onClick={() => setShowBasis(!showBasis)}
              className="text-[11px] font-semibold text-on-surface-variant hover:text-primary flex items-center gap-1 cursor-pointer">
              <Icon name={showBasis ? 'expand_less' : 'expand_more'} className="text-[14px]!" />
              What this is based on ({state.basis.length})
            </button>
            {showBasis && (
              <ul className="mt-2 space-y-1">
                {state.basis.map((b, i) => (
                  <li key={i} className="text-xs text-on-surface-variant whitespace-pre-wrap">{b}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Maintenance schedule                                                */
/* ------------------------------------------------------------------ */

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function eventTone(ev) {
  if (ev.status === 'COMPLETED') return 'bg-secondary/10 border-secondary/20 text-secondary'
  const days = (new Date(ev.scheduledStart) - Date.now()) / 86400000
  if (days >= 0 && days <= 7) return 'bg-[#f59e0b]/10 border-[#f59e0b]/30 text-[#b45309]'
  return 'bg-primary/10 border-primary/20 text-primary'
}

function eventChipLabel(ev) {
  if (ev.status === 'COMPLETED') return { label: 'Completed', cls: 'bg-secondary-container text-on-secondary-container' }
  const days = (new Date(ev.scheduledStart) - Date.now()) / 86400000
  if (days >= 0 && days <= 7) return { label: 'Upcoming', cls: 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20' }
  return { label: 'Planned', cls: 'bg-primary-container/20 text-primary' }
}

/** Solid status dot for the calendar chips — completed green, upcoming amber,
 *  planned the brand amber. Matches the legend and eventChipLabel above. */
function eventDot(ev) {
  if (ev.status === 'COMPLETED') return 'bg-secondary'
  const days = (new Date(ev.scheduledStart) - Date.now()) / 86400000
  if (days >= 0 && days <= 7) return 'bg-[#f59e0b]'
  return 'bg-primary'
}

function MaintenanceModal({ auth, onClose, onSaved }) {
  const [form, setForm] = useState({ title: '', description: '', date: '', start: '02:00', end: '04:00', downtime: 15 })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/admin/maintenance', {
        method: 'POST',
        auth,
        body: {
          title: form.title,
          description: form.description || null,
          scheduledStart: new Date(`${form.date}T${form.start}`).toISOString(),
          scheduledEnd: new Date(`${form.date}T${form.end}`).toISOString(),
          estimatedDowntimeMinutes: Number(form.downtime) || null,
        },
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const inputCls =
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[40px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">Schedule Maintenance</h3>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4">
            <div>
              <label className={labelCls}>Router / Node ID</label>
              <input className={inputCls} placeholder="e.g. Node-12-East" required value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className={labelCls}>Date</label>
                <input className={inputCls} type="date" required value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>Start</label>
                <input className={inputCls} type="time" required value={form.start} onChange={(e) => setForm({ ...form, start: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>End</label>
                <input className={inputCls} type="time" required value={form.end} onChange={(e) => setForm({ ...form, end: e.target.value })} />
              </div>
            </div>
            <div>
              <label className={labelCls}>Estimated Downtime (minutes)</label>
              <input className={inputCls} type="number" min="0" value={form.downtime} onChange={(e) => setForm({ ...form, downtime: e.target.value })} />
            </div>
            <div>
              <label className={labelCls}>Description</label>
              <textarea className={`${inputCls} resize-none`} rows="3" placeholder="e.g. Firmware upgrade to v2.4.1" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
            </div>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[40px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-4 h-10 rounded-md text-sm font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[40px] disabled:opacity-60 cursor-pointer">
              {busy ? 'Saving…' : 'Schedule'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function Maintenance({ auth }) {
  const [events, setEvents] = useState([])
  const [cursor, setCursor] = useState(() => { const d = new Date(); return { y: d.getFullYear(), m: d.getMonth() } })
  const [selectedId, setSelectedId] = useState(null)
  const [modal, setModal] = useState(false)

  const load = () => api('/admin/maintenance', { auth }).then(setEvents).catch(() => {})
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const selected = events.find((e) => e.id === selectedId)

  const byDay = useMemo(() => {
    const map = {}
    for (const ev of events) {
      const d = new Date(ev.scheduledStart)
      const key = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
      ;(map[key] = map[key] || []).push(ev)
    }
    return map
  }, [events])

  const firstDay = new Date(cursor.y, cursor.m, 1).getDay()
  const daysInMonth = new Date(cursor.y, cursor.m + 1, 0).getDate()
  const cellCount = Math.ceil((firstDay + daysInMonth) / 7) * 7
  const today = new Date()
  const isToday = (day) => day === today.getDate() && cursor.m === today.getMonth() && cursor.y === today.getFullYear()

  function shift(delta) {
    const d = new Date(cursor.y, cursor.m + delta, 1)
    setCursor({ y: d.getFullYear(), m: d.getMonth() })
  }

  async function complete() {
    await api(`/admin/maintenance/${selected.id}/complete`, { method: 'PATCH', auth }).catch(() => {})
    load()
  }

  async function remove() {
    await api(`/admin/maintenance/${selected.id}`, { method: 'DELETE', auth }).catch(() => {})
    setSelectedId(null)
    load()
  }

  const goToday = () => { const d = new Date(); setCursor({ y: d.getFullYear(), m: d.getMonth() }) }
  const viewingThisMonth = cursor.y === today.getFullYear() && cursor.m === today.getMonth()

  return (
    <div>
      <PageHeader title="Maintenance" subtitle="Plan and track network upgrades and downtime.">
        <PrimaryButton onClick={() => setModal(true)}>
          <Icon name="add" className="text-[18px]!" />
          Schedule
        </PrimaryButton>
      </PageHeader>

      <div className="flex flex-col xl:flex-row gap-6 items-start">
        {/* Calendar */}
        <div className="flex-1 w-full bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="px-4 h-14 border-b border-outline-variant flex justify-between items-center gap-3">
            <div className="flex items-center gap-1">
              <button onClick={() => shift(-1)} className="w-8 h-8 flex items-center justify-center rounded-md hover:bg-surface-container-high transition-colors text-on-surface-variant cursor-pointer" aria-label="Previous month">
                <Icon name="chevron_left" className="text-[20px]!" />
              </button>
              <h3 className="text-sm font-semibold text-on-surface w-36 text-center tabular-nums">{MONTH_NAMES[cursor.m]} {cursor.y}</h3>
              <button onClick={() => shift(1)} className="w-8 h-8 flex items-center justify-center rounded-md hover:bg-surface-container-high transition-colors text-on-surface-variant cursor-pointer" aria-label="Next month">
                <Icon name="chevron_right" className="text-[20px]!" />
              </button>
              <button
                onClick={goToday}
                disabled={viewingThisMonth}
                className="ml-1 h-8 px-3 rounded-md border border-outline-variant text-xs font-semibold text-on-surface-variant hover:bg-surface-container-high transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-default"
              >
                Today
              </button>
            </div>
            <div className="hidden sm:flex items-center gap-3">
              {[['bg-primary', 'Planned'], ['bg-[#f59e0b]', 'Upcoming'], ['bg-secondary', 'Completed']].map(([dot, label]) => (
                <div key={label} className="flex items-center gap-1.5">
                  <span className={`w-2 h-2 rounded-full ${dot}`}></span>
                  <span className="text-[11px] font-medium text-on-surface-variant">{label}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Weekday header */}
          <div className="grid grid-cols-7 border-b border-outline-variant">
            {DAY_NAMES.map((d, i) => (
              <div
                key={d}
                className={`py-2 text-center text-[11px] font-semibold tracking-wider uppercase ${
                  i === 0 || i === 6 ? 'text-on-surface-variant/60' : 'text-on-surface-variant'
                }`}
              >
                {d}
              </div>
            ))}
          </div>

          {/* Day grid */}
          <div className="grid grid-cols-7">
            {Array.from({ length: cellCount }, (_, i) => {
              const day = i - firstDay + 1
              const inMonth = day >= 1 && day <= daysInMonth
              const weekend = i % 7 === 0 || i % 7 === 6
              const dayEvents = inMonth ? byDay[`${cursor.y}-${cursor.m}-${day}`] || [] : []
              const shown = dayEvents.slice(0, 3)
              const overflow = dayEvents.length - shown.length
              const isCurrentDay = isToday(day)
              return (
                <div
                  key={i}
                  className={`min-h-[104px] p-1.5 flex flex-col gap-1 border-b border-r border-outline-variant [&:nth-child(7n)]:border-r-0 ${
                    inMonth ? (weekend ? 'bg-surface-container-lowest/40' : 'bg-surface-container-lowest') : 'bg-surface-dim/40'
                  }`}
                >
                  {inMonth && (
                    <>
                      <span
                        className={`text-xs font-medium w-6 h-6 flex items-center justify-center rounded-full shrink-0 ${
                          isCurrentDay ? 'bg-primary text-on-primary font-bold' : 'text-on-surface-variant'
                        }`}
                      >
                        {day}
                      </span>
                      {shown.map((ev) => {
                        const active = selectedId === ev.id
                        return (
                          <button
                            key={ev.id}
                            onClick={() => setSelectedId(ev.id)}
                            title={ev.title}
                            className={`w-full text-left rounded px-1.5 py-1 flex items-center gap-1.5 cursor-pointer transition-colors ${
                              active ? 'bg-primary/15 ring-1 ring-primary' : 'bg-surface-container hover:bg-surface-container-high'
                            }`}
                          >
                            <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${eventDot(ev)}`} />
                            <span className="text-[11px] leading-tight text-on-surface truncate">{ev.title}</span>
                          </button>
                        )
                      })}
                      {overflow > 0 && (
                        <button
                          onClick={() => setSelectedId(dayEvents[shown.length].id)}
                          className="text-[11px] text-on-surface-variant hover:text-primary text-left px-1.5 cursor-pointer"
                        >
                          +{overflow} more
                        </button>
                      )}
                    </>
                  )}
                </div>
              )
            })}
          </div>
        </div>

        {/* Detail panel */}
        <aside className="w-full xl:w-96 shrink-0">
          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant">
            {!selected ? (
              <div className="p-8 flex flex-col items-center text-center gap-3">
                <Icon name="calendar_month" className="text-[48px]! text-outline" />
                <p className="text-on-surface-variant text-sm">Select an event on the calendar to see its details, or schedule new maintenance.</p>
              </div>
            ) : (
              <>
                <div className="p-6 border-b border-outline-variant">
                  <div className="flex justify-between items-start">
                    <div>
                      <h3 className="text-lg font-semibold text-on-background">Maintenance Details</h3>
                      <p className="text-sm text-on-surface-variant mt-1">{fmtDate(selected.scheduledStart)}</p>
                    </div>
                    <span className={`text-xs font-semibold tracking-wider px-2 py-1 rounded-full ${eventChipLabel(selected).cls}`}>
                      {eventChipLabel(selected).label}
                    </span>
                  </div>
                </div>
                <div className="p-6 flex flex-col gap-4">
                  <div>
                    <span className="text-xs font-semibold tracking-wider uppercase text-outline block mb-1">Router ID</span>
                    <div className="text-base text-on-background flex items-center gap-2">
                      <Icon name="router" className="text-primary text-[18px]!" />
                      {selected.title}
                    </div>
                  </div>
                  <div className="h-px bg-surface-container w-full"></div>
                  <div>
                    <span className="text-xs font-semibold tracking-wider uppercase text-outline block mb-1">Scheduled Time</span>
                    <p className="text-base text-on-background">{fmtTime(selected.scheduledStart)} – {fmtTime(selected.scheduledEnd)}</p>
                  </div>
                  {selected.estimatedDowntimeMinutes != null && (
                    <>
                      <div className="h-px bg-surface-container w-full"></div>
                      <div>
                        <span className="text-xs font-semibold tracking-wider uppercase text-outline block mb-1">Estimated Downtime</span>
                        <p className="text-base text-on-background">{selected.estimatedDowntimeMinutes} mins</p>
                      </div>
                    </>
                  )}
                  {selected.description && (
                    <>
                      <div className="h-px bg-surface-container w-full"></div>
                      <div>
                        <span className="text-xs font-semibold tracking-wider uppercase text-outline block mb-1">Description</span>
                        <p className="text-sm text-on-surface-variant leading-relaxed">{selected.description}</p>
                      </div>
                    </>
                  )}
                  <div className="pt-4 border-t border-surface-container-high">
                    <span className="text-xs font-semibold tracking-wider uppercase text-outline block mb-3">Site Notes &amp; Photos</span>
                    <TaskNotes auth={auth} taskId={selected.id} />
                  </div>
                  <div className="pt-4 border-t border-outline-variant flex flex-col gap-2.5">
                    {selected.status !== 'COMPLETED' && (
                      <button onClick={complete} className="w-full h-10 bg-primary hover:opacity-90 text-on-primary rounded-md text-sm font-semibold transition-opacity active:scale-[0.98] cursor-pointer flex justify-center items-center gap-1.5">
                        <Icon name="check_circle" className="text-[18px]!" />
                        Mark Completed
                      </button>
                    )}
                    <button onClick={remove} className="w-full h-10 border border-error/60 text-error hover:bg-error/10 rounded-md text-sm font-semibold transition-colors cursor-pointer">
                      Delete Event
                    </button>
                  </div>
                </div>
              </>
            )}
          </div>
        </aside>
      </div>

      {modal && <MaintenanceModal auth={auth} onClose={() => setModal(false)} onSaved={() => { setModal(false); load() }} />}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Messages (direct chat with technicians)                             */
/* ------------------------------------------------------------------ */

function Messages({ auth }) {
  const [channels, setChannels] = useState(null)
  const [selected, setSelected] = useState(null) // technician username
  const [thread, setThread] = useState(null)

  const loadChannels = () => api('/admin/messages/channels', { auth }).then(setChannels).catch(() => setChannels([]))
  useEffect(() => { loadChannels() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!selected) return
    setThread(null)
    const load = () => api(`/admin/messages/${selected}`, { auth }).then(setThread).catch(() => setThread([]))
    load()
    loadChannels()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [selected, auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function send(text, file) {
    const form = new FormData()
    if (text) form.append('message', text)
    if (file) form.append('photo', file)
    await api(`/admin/messages/${selected}`, { method: 'POST', auth, body: form })
    const fresh = await api(`/admin/messages/${selected}`, { auth })
    setThread(fresh)
    loadChannels()
  }

  if (channels === null) {
    return (
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Skeleton className="h-96" />
        <Skeleton className="h-96 lg:col-span-2" />
      </div>
    )
  }

  const current = channels.find((c) => c.username === selected)

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-semibold tracking-tight text-on-surface">Messages</h2>
        <p className="text-xs text-on-surface-variant">Direct chat with your field technicians.</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 items-start">
        {/* Channel list */}
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          {channels.map((c) => (
            <button
              key={c.username}
              onClick={() => setSelected(c.username)}
              className={`w-full text-left p-4 border-b border-outline-variant/20 hover:bg-surface-container transition-colors cursor-pointer relative ${
                selected === c.username ? 'bg-primary-container/10' : ''
              }`}
            >
              {selected === c.username && <div className="absolute left-0 top-0 bottom-0 w-1 bg-primary"></div>}
              <div className="flex items-center gap-3">
                <span className="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center text-sm font-bold uppercase shrink-0">
                  {c.fullName?.trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2)}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex justify-between items-center gap-2">
                    <span className="text-base font-semibold text-on-surface truncate">{c.fullName}</span>
                    {c.lastMessage && <span className="text-xs text-on-surface-variant whitespace-nowrap">{relativeTime(c.lastMessage.createdAt)}</span>}
                  </div>
                  <div className="flex justify-between items-center gap-2">
                    <span className="text-sm text-on-surface-variant truncate">
                      {c.lastMessage
                        ? `${c.lastMessage.fromAdmin ? 'You: ' : ''}${c.lastMessage.body || '📷 Photo'}`
                        : 'No messages yet'}
                    </span>
                    {c.unread > 0 && (
                      <span className="min-w-[20px] h-5 px-1.5 bg-error text-on-error text-xs font-bold rounded-full flex items-center justify-center shrink-0">
                        {c.unread}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            </button>
          ))}
          {channels.length === 0 && (
            <p className="p-4 text-sm text-on-surface-variant">No technicians yet — add one on the Team page first.</p>
          )}
        </div>

        {/* Conversation */}
        <div className="lg:col-span-2 bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden flex flex-col h-[70vh]">
          {!selected ? (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 p-8 text-center">
              <Icon name="chat" className="text-[48px]! text-outline" />
              <p className="text-on-surface-variant">Select a technician to open the conversation.</p>
            </div>
          ) : (
            <>
              <div className="p-4 border-b border-outline-variant/30 flex items-center gap-3 bg-surface-container-low">
                <span className="w-9 h-9 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center text-sm font-bold uppercase">
                  {current?.fullName?.trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2)}
                </span>
                <div>
                  <p className="text-base font-semibold text-on-surface">{current?.fullName}</p>
                  <p className="text-xs text-on-surface-variant font-mono">{selected}</p>
                </div>
              </div>
              <ChatThread
                messages={thread}
                viewerIsAdmin={true}
                onSend={send}
                emptyHint="No messages yet — send the first one."
              />
            </>
          )}
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Team (technician accounts)                                          */
/* ------------------------------------------------------------------ */

function TechnicianModal({ auth, onClose, onSaved }) {
  const [form, setForm] = useState({ username: '', password: '', fullName: '', phoneNumber: '', canVouchers: true, canPppoe: false })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/admin/technicians', {
        method: 'POST',
        auth,
        body: { ...form, phoneNumber: form.phoneNumber || null, canVouchers: form.canVouchers, canPppoe: form.canPppoe },
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const inputCls =
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[40px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">Add Technician</h3>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4">
            <div>
              <label className={labelCls}>Full Name</label>
              <input className={inputCls} placeholder="e.g. James Mwangi" required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={labelCls}>Username</label>
                <input className={inputCls} placeholder="e.g. jmwangi" required value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
              </div>
              <div>
                <label className={labelCls}>Phone Number</label>
                <input className={inputCls} placeholder="2547XXXXXXXX" value={form.phoneNumber} onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })} />
              </div>
            </div>
            <div>
              <label className={labelCls}>Password</label>
              <input className={inputCls} type="text" placeholder="At least 6 characters" required minLength={6} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
              <p className="text-xs font-semibold tracking-wider text-tertiary mt-1">Share these credentials with the technician — they sign in at /tech.</p>
            </div>
            <div>
              <label className={labelCls}>Permissions</label>
              <div className="flex flex-col gap-2">
                <label className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors">
                  <input type="checkbox" checked={form.canVouchers} onChange={(e) => setForm({ ...form, canVouchers: e.target.checked })} className="w-4 h-4 accent-[#fdbf2d]" />
                  <span className="text-sm text-on-surface"><strong>Issue vouchers</strong> — generate and print WiFi passes in the field</span>
                </label>
                <label className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors">
                  <input type="checkbox" checked={form.canPppoe} onChange={(e) => setForm({ ...form, canPppoe: e.target.checked })} className="w-4 h-4 accent-[#fdbf2d]" />
                  <span className="text-sm text-on-surface"><strong>Manage PPPoE subscribers</strong> — sign up monthly home customers and take payments</span>
                </label>
              </div>
            </div>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[40px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-4 h-10 rounded-md text-sm font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[40px] disabled:opacity-60 cursor-pointer">
              {busy ? 'Creating…' : 'Create Account'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function Team({ auth }) {
  const [techs, setTechs] = useState(null)
  const [modal, setModal] = useState(false)
  const [resetId, setResetId] = useState(null)
  const [deleteId, setDeleteId] = useState(null)
  const [newPass, setNewPass] = useState('')
  const [resetMsg, setResetMsg] = useState(null)

  const load = () => api('/admin/technicians', { auth }).then(setTechs).catch(() => setTechs([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function resetPassword(e) {
    e.preventDefault()
    try {
      await api(`/admin/technicians/${resetId}/password`, { method: 'PATCH', auth, body: { password: newPass } })
      setResetMsg({ ok: true, text: 'Password updated.' })
      setResetId(null)
      setNewPass('')
    } catch (err) {
      setResetMsg({ ok: false, text: err.message })
    }
  }

  if (techs === null) return <Skeleton className="h-64" />

  return (
    <div>
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-2.5 mb-4">
        <div>
          <h2 className="text-xl font-semibold tracking-tight text-on-surface">Team</h2>
          <p className="text-xs text-on-surface-variant">Field technician accounts for the Field Connect app.</p>
        </div>
        <button
          onClick={() => setModal(true)}
          className="bg-primary text-on-primary text-sm font-semibold px-4 h-10 rounded-md flex items-center gap-1.5 hover:opacity-90 transition-opacity active:scale-[0.98] whitespace-nowrap cursor-pointer"
        >
          <Icon name="person_add" />
          Add Technician
        </button>
      </div>

      {resetMsg && <p className={`text-sm mb-4 ${resetMsg.ok ? 'text-surface-tint' : 'text-error'}`}>{resetMsg.text}</p>}

      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[700px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="border-b border-outline-variant/50">Technician</th>
                <th className="border-b border-outline-variant/50">Username</th>
                <th className="border-b border-outline-variant/50">Phone</th>
                <th className="border-b border-outline-variant/50">Since</th>
                <th className="border-b border-outline-variant/50">Permissions</th>
                <th className="border-b border-outline-variant/50">Status</th>
                <th className="border-b border-outline-variant/50 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm">
              {techs.map((t) => (
                <tr key={t.id} className="border-b border-outline-variant/30 hover:bg-surface-container-low/20 transition-colors">
                  <td className="">
                    <div className="flex items-center gap-3">
                      <span className="w-9 h-9 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center text-sm font-bold uppercase">
                        {t.fullName?.trim().split(/\s+/).map((w) => w[0]).join('').slice(0, 2)}
                      </span>
                      <span className="text-base font-semibold text-on-background">{t.fullName}</span>
                    </div>
                  </td>
                  <td className="font-mono">{t.username}</td>
                  <td className="">{t.phoneNumber || <span className="text-on-surface-variant">—</span>}</td>
                  <td className="text-on-surface-variant">{fmtDate(t.createdAt)}</td>
                  <td className="">
                    <div className="flex gap-1.5 flex-wrap">
                      <button
                        onClick={() => api(`/admin/technicians/${t.id}/permissions`, { method: 'PATCH', auth, body: { canVouchers: !t.vouchersAllowed } }).then(load)}
                        title={t.vouchersAllowed ? 'Click to remove voucher access' : 'Click to grant voucher access'}
                        className={`px-2 py-1 rounded-full text-[11px] font-bold tracking-wide cursor-pointer transition-colors ${
                          t.vouchersAllowed ? 'bg-secondary-container text-on-secondary-container' : 'bg-surface-variant text-on-surface-variant line-through'
                        }`}
                      >
                        VOUCHERS
                      </button>
                      <button
                        onClick={() => api(`/admin/technicians/${t.id}/permissions`, { method: 'PATCH', auth, body: { canPppoe: !t.pppoeAllowed } }).then(load)}
                        title={t.pppoeAllowed ? 'Click to remove PPPoE access' : 'Click to grant PPPoE access'}
                        className={`px-2 py-1 rounded-full text-[11px] font-bold tracking-wide cursor-pointer transition-colors ${
                          t.pppoeAllowed ? 'bg-primary-container/30 text-primary' : 'bg-surface-variant text-on-surface-variant line-through'
                        }`}
                      >
                        PPPOE
                      </button>
                    </div>
                  </td>
                  <td className=""><StatusPill status={t.active ? 'ACTIVE' : 'INACTIVE'} /></td>
                  <td className="text-right">
                    <div className="flex items-center justify-end gap-3">
                      <button
                        onClick={() => { setResetId(resetId === t.id ? null : t.id); setDeleteId(null); setNewPass(''); setResetMsg(null) }}
                        className="text-xs font-semibold tracking-wider text-primary hover:underline cursor-pointer"
                      >
                        RESET PASSWORD
                      </button>
                      <Toggle checked={t.active} onChange={() => api(`/admin/technicians/${t.id}/toggle`, { method: 'PATCH', auth }).then(load)} />
                      <button
                        onClick={() => { setDeleteId(deleteId === t.id ? null : t.id); setResetId(null) }}
                        className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer"
                        aria-label={`Remove ${t.fullName}`}
                      >
                        <Icon name="delete" className="text-[20px]!" />
                      </button>
                    </div>
                    {resetId === t.id && (
                      <form onSubmit={resetPassword} className="flex gap-2 mt-3 justify-end">
                        <input
                          type="text"
                          minLength={6}
                          required
                          value={newPass}
                          onChange={(e) => setNewPass(e.target.value)}
                          placeholder="New password"
                          className="h-10 bg-surface border border-outline-variant rounded-lg px-3 text-sm focus:outline-none focus:border-primary w-44"
                        />
                        <button type="submit" className="h-10 px-4 rounded-lg bg-primary text-on-primary text-sm font-semibold cursor-pointer">Save</button>
                      </form>
                    )}
                    {deleteId === t.id && (
                      <div className="flex items-center gap-2 mt-3 justify-end">
                        <span className="text-sm text-on-surface-variant">Remove <strong className="text-on-surface">{t.fullName}</strong> permanently?</span>
                        <button
                          onClick={() => api(`/admin/technicians/${t.id}`, { method: 'DELETE', auth }).then(() => { setDeleteId(null); load() }).catch(() => {})}
                          className="h-9 px-4 rounded-lg bg-error text-on-error text-sm font-semibold cursor-pointer"
                        >
                          Yes, remove
                        </button>
                        <button onClick={() => setDeleteId(null)} className="h-9 px-4 rounded-lg border border-outline-variant text-on-surface text-sm font-semibold cursor-pointer">
                          Cancel
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
              {techs.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={7}>No technicians yet — add the first account.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {modal && <TechnicianModal auth={auth} onClose={() => setModal(false)} onSaved={() => { setModal(false); load() }} />}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Settings: limited-time offers (promotions)                          */
/* ------------------------------------------------------------------ */

function PromoCountdown({ endsAt, onExpire }) {
  const [remaining, setRemaining] = useState(() => new Date(endsAt).getTime() - Date.now())
  useEffect(() => {
    const t = setInterval(() => setRemaining(new Date(endsAt).getTime() - Date.now()), 1000)
    return () => clearInterval(t)
  }, [endsAt])
  useEffect(() => {
    if (remaining <= 0) onExpire()
  }, [remaining <= 0]) // eslint-disable-line react-hooks/exhaustive-deps

  if (remaining <= 0) return <span>ended</span>
  const s = Math.floor(remaining / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n) => String(n).padStart(2, '0')
  return (
    <span className="font-mono font-bold tabular-nums">
      {d > 0 ? `${d}d ${h}h ${m}m ${sec}s` : `${pad(h)}:${pad(m)}:${pad(sec)}`}
    </span>
  )
}

function PromotionCard({ auth }) {
  const [promos, setPromos] = useState(null)
  const [form, setForm] = useState({ title: 'Enjoy the Weekend Offer!', discountPercent: 20, durationValue: 2, durationUnit: 'days' })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/promotions', { auth }).then(setPromos).catch(() => setPromos([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (promos === null) return <Skeleton className="h-40" />

  const now = Date.now()
  const current = promos.find((p) => new Date(p.startsAt) <= now && new Date(p.endsAt) > now)

  async function launch(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      const ms = Number(form.durationValue) * (form.durationUnit === 'hours' ? 3600000 : 86400000)
      await api('/admin/promotions', {
        method: 'POST',
        auth,
        body: { title: form.title, discountPercent: Number(form.discountPercent), endsAt: new Date(Date.now() + ms).toISOString() },
      })
      setMsg({ ok: true, text: 'Offer is live — the portal banner and prices update immediately.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function endNow() {
    await api(`/admin/promotions/${current.id}/end`, { method: 'PATCH', auth }).catch(() => {})
    setMsg({ ok: true, text: 'Offer ended — prices reverted to normal.' })
    load()
  }

  const inputCls =
    'w-full bg-surface-container-low border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all min-h-[40px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-outline mb-2'

  return (
    <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant border-l-2 border-l-[#f59e0b]">
      <div className="flex items-center gap-3 mb-4">
        <Icon name="celebration" className="text-[#b45309] bg-[#f59e0b]/10 p-2 rounded-lg text-[40px]!" />
        <div>
          <h3 className="text-lg font-semibold text-on-surface">Limited-Time Offer</h3>
          <p className="text-sm text-on-surface-variant">
            Runs a discount on every price for a fixed window, with a banner on the portal. Prices revert automatically when it ends.
          </p>
        </div>
      </div>

      {current ? (
        <div className="rounded-xl bg-gradient-to-r from-[#b45309] to-[#f59e0b] text-white p-4 flex items-center gap-3 flex-wrap">
          <Icon name="celebration" filled className="text-[28px]!" />
          <div className="flex-1 min-w-0">
            <p className="font-bold">{current.title}</p>
            <p className="text-sm text-white/90 flex items-center gap-1.5">
              <Icon name="timer" className="text-[16px]!" />
              -{current.discountPercent}% · ends in <PromoCountdown endsAt={current.endsAt} onExpire={load} />
            </p>
          </div>
          <button onClick={endNow} className="px-4 py-2 rounded-lg bg-white/15 hover:bg-white/25 border border-white/40 text-sm font-semibold transition-colors cursor-pointer">
            End offer now
          </button>
        </div>
      ) : (
        <form onSubmit={launch} className="grid grid-cols-1 md:grid-cols-4 gap-4 items-end">
          <div className="md:col-span-2">
            <label className={labelCls}>Banner text</label>
            <input className={inputCls} required maxLength={80} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          </div>
          <div>
            <label className={labelCls}>Discount %</label>
            <input className={inputCls} type="number" min="1" max="90" required value={form.discountPercent} onChange={(e) => setForm({ ...form, discountPercent: e.target.value })} />
          </div>
          <div>
            <label className={labelCls}>Runs for</label>
            <div className="flex gap-2">
              <input className={`${inputCls} flex-1 min-w-0`} type="number" min="1" required value={form.durationValue} onChange={(e) => setForm({ ...form, durationValue: e.target.value })} />
              <select className={`${inputCls} w-auto`} value={form.durationUnit} onChange={(e) => setForm({ ...form, durationUnit: e.target.value })}>
                <option value="hours">Hours</option>
                <option value="days">Days</option>
              </select>
            </div>
          </div>
          <button type="submit" disabled={busy} className="md:col-span-4 justify-self-end px-6 py-3 rounded-xl bg-primary text-on-primary text-lg font-semibold shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 disabled:opacity-60 cursor-pointer">
            {busy ? 'Launching…' : 'Launch Offer'}
          </button>
        </form>
      )}
      {msg && <p className={`text-sm font-semibold mt-3 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}
    </section>
  )
}

/* ------------------------------------------------------------------ */
/* Settings: SMS campaigns                                             */
/* ------------------------------------------------------------------ */

function SmsCard({ auth }) {
  const [info, setInfo] = useState(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => {
    api('/admin/sms/recipients', { auth }).then(setInfo).catch(() => setInfo({ count: 0, enabled: false }))
  }, [auth])

  if (!info) return <Skeleton className="h-40" />

  async function send() {
    setBusy(true)
    setMsg(null)
    try {
      const r = await api('/admin/sms/campaign', { method: 'POST', auth, body: { message: message.trim() } })
      setMsg({ ok: true, text: r.message })
      setMessage('')
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="bg-surface-container-lowest rounded-lg p-4 ">
      <div className="flex items-center gap-3 mb-4">
        <Icon name="sms" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
        <div>
          <h3 className="text-lg font-semibold text-on-surface">SMS Campaign</h3>
          <p className="text-sm text-on-surface-variant">
            Text every customer who has bought or redeemed a voucher — {info.count.toLocaleString()} number{info.count === 1 ? '' : 's'} on file.
          </p>
        </div>
      </div>

      {!info.enabled && (
        <div className="flex items-start gap-2 bg-[#f59e0b]/10 border border-[#f59e0b]/30 text-[#b45309] rounded-lg p-3 mb-4 text-sm">
          <Icon name="info" className="text-[18px]! mt-0.5" />
          <span>
            SMS is not configured yet. Create a free <strong>Africa's Talking</strong> account, then set the
            <code className="mx-1">SMS_ENABLED=true</code>, <code>SMS_USERNAME</code> and <code>SMS_API_KEY</code> environment
            variables and restart the backend. Voucher codes will also be texted to buyers automatically once enabled.
          </span>
        </div>
      )}

      <textarea
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        rows="3"
        maxLength={320}
        placeholder="e.g. Weekend Offer! All SPA WiFi passes 20% off until Sunday midnight. Connect at our hotspot and save."
        className="w-full bg-surface-container-low border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all resize-none"
      />
      <div className="mt-3 flex items-center justify-between gap-3 flex-wrap">
        <span className="text-xs text-on-surface-variant">{message.length}/320 characters {message.length > 160 ? '(2 SMS per recipient)' : ''}</span>
        <div className="flex items-center gap-3">
          {msg && <span className={`text-sm font-semibold ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</span>}
          <button
            onClick={send}
            disabled={busy || !message.trim() || !info.enabled}
            className="px-6 py-3 rounded-xl bg-primary text-on-primary text-lg font-semibold shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 disabled:opacity-50 cursor-pointer flex items-center gap-2"
          >
            <Icon name="send" className="text-[20px]!" />
            {busy ? 'Sending…' : `Send to ${info.count.toLocaleString()}`}
          </button>
        </div>
      </div>
    </section>
  )
}

/* ------------------------------------------------------------------ */
/* Settings: pay-per-minute custom pass                                */
/* ------------------------------------------------------------------ */

function CustomPlanCard({ auth }) {
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => {
    api('/admin/settings/custom-plan', { auth })
      .then((s) => setForm({
        enabled: !!s.enabled,
        pricePerHour: s.pricePerHour ?? 20,
        mbps: s.bandwidth ? parseInt(s.bandwidth) || '' : '',
        minMinutes: s.minMinutes ?? 10,
        maxMinutes: s.maxMinutes ?? 1440,
      }))
      .catch(() => {})
  }, [auth])

  if (!form) return <Skeleton className="h-40" />

  const set = (key, value) => { setForm({ ...form, [key]: value }); setMsg(null) }
  const previewMinutes = 45
  const previewPrice = Math.max(1, Math.ceil((Number(form.pricePerHour) || 0) * previewMinutes / 60))

  async function save() {
    setSaving(true)
    setMsg(null)
    try {
      await api('/admin/settings/custom-plan', {
        method: 'PUT',
        auth,
        body: {
          enabled: form.enabled,
          pricePerHour: Number(form.pricePerHour),
          bandwidth: form.mbps ? `${form.mbps}M/${form.mbps}M` : null,
          minMinutes: Number(form.minMinutes),
          maxMinutes: Number(form.maxMinutes),
        },
      })
      setMsg({ ok: true, text: 'Saved — the portal updates immediately.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setSaving(false)
    }
  }

  const inputCls =
    'w-full bg-surface-container-low border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all min-h-[40px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-outline mb-2'

  return (
    <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant border-l-2 border-l-secondary">
      <div className="flex items-center justify-between gap-4 mb-4 flex-wrap">
        <div className="flex items-center gap-3">
          <Icon name="timer" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
          <div>
            <h3 className="text-lg font-semibold text-on-surface">Custom Time Pass (Pay per minute)</h3>
            <p className="text-sm text-on-surface-variant">Customers type the minutes they need; the portal prices it from your hourly rate.</p>
          </div>
        </div>
        <Toggle checked={form.enabled} onChange={() => set('enabled', !form.enabled)} />
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div>
          <label className={labelCls} htmlFor="cp-rate">Price per hour (KES)</label>
          <input id="cp-rate" className={inputCls} type="number" min="1" value={form.pricePerHour} onChange={(e) => set('pricePerHour', e.target.value)} />
        </div>
        <div>
          <label className={labelCls} htmlFor="cp-mbps">Bandwidth (Mbps)</label>
          <input id="cp-mbps" className={inputCls} type="number" min="1" placeholder="e.g. 5" value={form.mbps} onChange={(e) => set('mbps', e.target.value)} />
        </div>
        <div>
          <label className={labelCls} htmlFor="cp-min">Min minutes</label>
          <input id="cp-min" className={inputCls} type="number" min="1" value={form.minMinutes} onChange={(e) => set('minMinutes', e.target.value)} />
        </div>
        <div>
          <label className={labelCls} htmlFor="cp-max">Max minutes</label>
          <input id="cp-max" className={inputCls} type="number" min="1" value={form.maxMinutes} onChange={(e) => set('maxMinutes', e.target.value)} />
        </div>
      </div>
      <div className="mt-4 flex flex-col sm:flex-row items-start sm:items-center gap-3 justify-between border-t border-outline-variant pt-4">
        <p className="text-sm text-on-surface-variant">
          Example: {previewMinutes} minutes would cost <strong className="text-primary">{money(previewPrice)}</strong> at this rate.
        </p>
        <div className="flex items-center gap-3">
          {msg && <span className={`text-sm font-semibold ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</span>}
          <button
            onClick={save}
            disabled={saving}
            className="px-6 py-3 rounded-xl bg-primary text-on-primary text-lg font-semibold shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 disabled:opacity-60 cursor-pointer"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </section>
  )
}

/* ------------------------------------------------------------------ */
/* Settings (MikroTik integration)                                     */
/* ------------------------------------------------------------------ */

function Settings({ auth }) {
  const [form, setForm] = useState(null)
  const [original, setOriginal] = useState(null)
  const [showPass, setShowPass] = useState(false)
  const [test, setTest] = useState(null)
  const [testing, setTesting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState(null)

  useEffect(() => {
    api('/admin/settings/mikrotik', { auth })
      .then((s) => {
        const f = {
          enabled: !!s.enabled,
          host: s.host || '',
          port: s.port || 8728,
          username: s.username || '',
          password: s.password || '',
          useSsl: !!s.useSsl,
          certificate: s.certificate || '',
          hotspotServer: s.hotspotServer || '',
          interfaceName: s.interfaceName || '',
          dnsName: s.dnsName || '',
          macBinding: !!s.macBinding,
        }
        setForm(f)
        setOriginal(f)
      })
      .catch(() => {})
  }, [auth])

  if (!form) {
    return (
      <div className="max-w-4xl">
        <Skeleton className="h-10 w-72 mb-3" />
        <Skeleton className="h-5 w-96 mb-8" />
        <Skeleton className="h-64 mb-6" />
        <Skeleton className="h-40" />
      </div>
    )
  }

  const dirty = JSON.stringify(form) !== JSON.stringify(original)
  const set = (key, value) => { setForm({ ...form, [key]: value }); setSaveMsg(null) }
  const payload = () => ({ ...form, port: Number(form.port) || 8728 })

  async function testConnection() {
    setTesting(true)
    setTest(null)
    try {
      const r = await api('/admin/settings/mikrotik/test', { method: 'POST', auth, body: payload() })
      setTest({ ok: true, message: r.message })
    } catch (err) {
      setTest({ ok: false, message: err.message })
    } finally {
      setTesting(false)
    }
  }

  async function save() {
    setSaving(true)
    setSaveMsg(null)
    try {
      await api('/admin/settings/mikrotik', { method: 'PUT', auth, body: payload() })
      setOriginal(form)
      setSaveMsg({ ok: true, text: 'Settings saved.' })
    } catch (err) {
      setSaveMsg({ ok: false, text: err.message })
    } finally {
      setSaving(false)
    }
  }

  const inputCls =
    'w-full bg-surface-container-low border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all min-h-[40px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-outline mb-2'

  return (
    <div className="max-w-4xl">
      <div className="mb-6">
        <h2 className="text-xl font-semibold tracking-tight text-on-surface">MikroTik Integration</h2>
        <p className="text-xs text-on-surface-variant">Configure your router API credentials and network parameters.</p>
      </div>

      <div className="space-y-6 pb-24">
        {/* Master toggle */}
        <section className="bg-surface-container-lowest rounded-lg p-4  flex items-center justify-between gap-4">
          <div>
            <p className="text-lg font-semibold text-on-surface">Enable MikroTik integration</p>
            <p className="text-sm text-on-surface-variant">When off, vouchers are issued without provisioning hotspot users on the router.</p>
          </div>
          <Toggle checked={form.enabled} onChange={() => set('enabled', !form.enabled)} />
        </section>

        {/* API credentials */}
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant border-l-2 border-l-primary">
          <div className="flex items-center gap-3 mb-4">
            <Icon name="router" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
            <h3 className="text-lg font-semibold text-on-surface">MikroTik API Credentials</h3>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={labelCls} htmlFor="mk-host">IP Address / Host</label>
              <input id="mk-host" className={inputCls} placeholder="e.g. 192.168.88.1" type="text" value={form.host} onChange={(e) => set('host', e.target.value)} />
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-port">API Port</label>
              <input id="mk-port" className={inputCls} placeholder="8728" type="number" value={form.port} onChange={(e) => set('port', e.target.value)} />
              <p className="text-sm text-outline mt-1">Default is 8728 (or 8729 for SSL)</p>
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-user">Username</label>
              <input id="mk-user" className={inputCls} placeholder="e.g. admin" type="text" value={form.username} onChange={(e) => set('username', e.target.value)} />
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-pass">Password</label>
              <div className="relative">
                <input id="mk-pass" className={`${inputCls} pr-12`} placeholder="Router API password" type={showPass ? 'text' : 'password'} value={form.password} onChange={(e) => set('password', e.target.value)} />
                <button
                  type="button"
                  onClick={() => setShowPass(!showPass)}
                  aria-label={showPass ? 'Hide password' : 'Show password'}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-outline hover:text-primary transition-colors cursor-pointer"
                >
                  <Icon name={showPass ? 'visibility_off' : 'visibility'} className="text-[20px]!" />
                </button>
              </div>
            </div>
          </div>
          <div className="mt-4 flex flex-col sm:flex-row items-start sm:items-center gap-3 justify-between border-t border-outline-variant pt-4">
            {test && (
              <div className={`flex items-center gap-2 text-sm font-semibold ${test.ok ? 'text-surface-tint' : 'text-error'}`}>
                <Icon name={test.ok ? 'check_circle' : 'error'} className="text-[18px]!" />
                {test.message}
              </div>
            )}
            <button
              type="button"
              onClick={testConnection}
              disabled={testing || !form.host}
              className="px-6 py-3 rounded-xl border border-primary text-primary text-lg font-semibold hover:bg-primary/5 transition-colors sm:ml-auto disabled:opacity-50 cursor-pointer"
            >
              {testing ? 'Testing…' : 'Test Connection'}
            </button>
          </div>
        </section>

        {/* Network settings */}
        <section className="bg-surface-container-lowest rounded-lg p-4 ">
          <div className="flex items-center gap-3 mb-4">
            <Icon name="wifi" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
            <h3 className="text-lg font-semibold text-on-surface">Network Settings</h3>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <label className={labelCls} htmlFor="mk-server">Hotspot Server Name</label>
              <input id="mk-server" className={inputCls} placeholder="hs-server1" type="text" value={form.hotspotServer} onChange={(e) => set('hotspotServer', e.target.value)} />
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-iface">Interface</label>
              <input id="mk-iface" className={inputCls} placeholder="bridge1" type="text" value={form.interfaceName} onChange={(e) => set('interfaceName', e.target.value)} />
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-dns">DNS Name</label>
              <input id="mk-dns" className={inputCls} placeholder="hotspot.spawifi.local" type="text" value={form.dnsName} onChange={(e) => set('dnsName', e.target.value)} />
            </div>
          </div>
        </section>

        {/* API security */}
        <section className="bg-surface-container-lowest rounded-lg p-4 ">
          <div className="flex items-center gap-3 mb-4">
            <Icon name="security" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
            <h3 className="text-lg font-semibold text-on-surface">API Security</h3>
          </div>
          <div className="flex flex-col md:flex-row gap-6 items-start md:items-center">
            <div className="flex items-center justify-between w-full md:w-auto gap-4 p-4 border border-outline-variant rounded-lg bg-surface-container-low">
              <div>
                <p className="text-lg font-semibold text-on-surface">Use SSL/TLS</p>
                <p className="text-sm text-outline">Encrypt API communication</p>
              </div>
              <Toggle checked={form.useSsl} onChange={() => set('useSsl', !form.useSsl)} />
            </div>
            <div className={`flex-1 w-full ${form.useSsl ? '' : 'opacity-50 pointer-events-none'}`}>
              <label className={labelCls} htmlFor="mk-cert">Certificate Name (Optional)</label>
              <input id="mk-cert" className={inputCls} placeholder="api-cert" type="text" value={form.certificate} onChange={(e) => set('certificate', e.target.value)} />
            </div>
          </div>
          <div className="mt-4 flex items-center justify-between gap-4 p-4 border border-outline-variant rounded-lg bg-surface-container-low">
            <div>
              <p className="text-lg font-semibold text-on-surface">Lock vouchers to first device (MAC binding)</p>
              <p className="text-sm text-outline">
                Each voucher is bound to the hardware address of the first device that logs in, so the code can't be
                passed around afterwards. Use the Unbind action on a voucher when a customer changes phones.
              </p>
            </div>
            <Toggle checked={form.macBinding} onChange={() => set('macBinding', !form.macBinding)} />
          </div>
        </section>

        <CustomPlanCard auth={auth} />
        <PromotionCard auth={auth} />
        <SmsCard auth={auth} />
      </div>

      {/* Sticky action bar */}
      <div className="fixed bottom-0 left-0 md:left-64 right-0 p-4 bg-surface-container-lowest border-t border-outline-variant shadow-[0_-4px_12px_rgba(0,0,0,0.05)] z-30 flex items-center justify-end gap-4">
        {saveMsg && (
          <span className={`text-sm font-semibold mr-auto ${saveMsg.ok ? 'text-surface-tint' : 'text-error'}`}>{saveMsg.text}</span>
        )}
        <button
          type="button"
          onClick={() => { setForm(original); setTest(null); setSaveMsg(null) }}
          disabled={!dirty}
          className="px-6 py-3 rounded-xl text-lg font-semibold text-on-surface-variant hover:bg-surface-container-high transition-colors disabled:opacity-40 cursor-pointer"
        >
          Discard Changes
        </button>
        <button
          type="button"
          onClick={save}
          disabled={!dirty || saving}
          className="px-8 py-3 bg-primary text-on-primary rounded-xl text-lg font-semibold  hover:bg-surface-tint transition-all active:scale-95 flex items-center gap-2 disabled:opacity-50 cursor-pointer"
        >
          <Icon name="save" className="text-[20px]!" />
          {saving ? 'Saving…' : 'Save Changes'}
        </button>
      </div>
    </div>
  )
}
