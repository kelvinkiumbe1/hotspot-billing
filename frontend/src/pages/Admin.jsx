import { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'
import TaskNotes from '../components/TaskNotes.jsx'
import ChatThread from '../components/ChatThread.jsx'
import RoutersPage from './admin/Routers.jsx'
import FinancePage from './admin/Finance.jsx'
import BranchesPage from './admin/Branches.jsx'
import PayBillPage from './admin/PayBill.jsx'
import AuditLogPage from './admin/AuditLog.jsx'
import loginFiber from '../assets/login-fiber.jpg'

/* ------------------------------------------------------------------ */
/* Shared helpers                                                      */
/* ------------------------------------------------------------------ */

function Icon({ name, filled = false, className = '' }) {
  return (
    <span className={`material-symbols-outlined select-none ${filled ? 'filled' : ''} ${className}`} aria-hidden="true">
      {name}
    </span>
  )
}

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
function PlanOptions({ plans }) {
  return PLAN_GROUPS.map((group) => {
    const groupPlans = plans.filter((p) => planGroup(p.durationMinutes) === group)
    if (!groupPlans.length) return null
    return (
      <optgroup key={group} label={group}>
        {groupPlans.map((p) => (
          <option key={p.id} value={p.id}>{p.name} — KES {p.price}</option>
        ))}
      </optgroup>
    )
  })
}

function speedLabel(bandwidth) {
  if (!bandwidth) return null
  const down = bandwidth.split('/')[0].trim().replace(/M$/i, '')
  return `${down} Mbps`
}

function fmtKES(n) {
  return `KES ${Number(n || 0).toLocaleString()}`
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

export default function Admin() {
  const [auth, setAuth] = useState(sessionStorage.getItem('adminAuth'))
  return auth
    ? <Shell auth={auth} onLogout={() => { sessionStorage.removeItem('adminAuth'); setAuth(null) }} />
    : <Login onLogin={(a) => { sessionStorage.setItem('adminAuth', a); setAuth(a) }} />
}

/* ------------------------------------------------------------------ */
/* Login                                                               */
/* ------------------------------------------------------------------ */

function Login({ onLogin }) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    const candidate = btoa(`${username}:${password}`)
    try {
      await api('/admin/stats', { auth: candidate })
      onLogin(candidate)
    } catch (err) {
      setError(err.status === 401 ? 'Wrong username or password' : err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="relative bg-inverse-surface text-on-background min-h-screen flex flex-col items-center justify-center px-5 overflow-hidden">
      <img src={loginFiber} alt="" className="absolute inset-0 w-full h-full object-cover" />
      <div className="absolute inset-0 bg-[#00201d]/70"></div>
      <div className="relative z-10 w-full max-w-sm">
        <div className="flex flex-col items-center mb-8">
          <div className="w-16 h-16 rounded-full bg-white/10 backdrop-blur border border-white/20 flex items-center justify-center shadow-[0_8px_16px_rgba(15,23,42,0.3)] mb-4">
            <Icon name="wifi_tethering" filled className="text-primary-fixed text-[32px]!" />
          </div>
          <h1 className="text-2xl font-bold text-white">HotspotPro</h1>
          <p className="text-sm text-white/70">Network Manager</p>
        </div>

        <form
          onSubmit={submit}
          className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-primary p-6 flex flex-col gap-4"
        >
          <h2 className="text-lg font-semibold text-on-surface">Sign in to Hotspot Manager</h2>
          <div>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="admin-user">
              Username
            </label>
            <input
              id="admin-user"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              autoComplete="username"
              className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
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
          {error && <p className="text-sm text-error">{error}</p>}
          <button
            type="submit"
            disabled={busy}
            className="w-full h-12 bg-primary text-on-primary rounded-lg text-lg font-semibold shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-surface-tint active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer"
          >
            {busy ? 'Signing in…' : 'Sign In'}
          </button>
          <p className="text-xs text-on-surface-variant flex items-center gap-1.5 justify-center">
            <Icon name="lock" className="text-[14px]!" /> Restricted area — authorized staff only.
          </p>
        </form>
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
    items: [{ key: 'overview', label: 'Overview', icon: 'dashboard' }],
  },
  {
    label: 'Selling',
    items: [
      { key: 'plans', label: 'Plans', icon: 'wifi_tethering' },
      { key: 'vouchers', label: 'Vouchers', icon: 'confirmation_number' },
      { key: 'subscribers', label: 'Subscribers', icon: 'lan' },
    ],
  },
  {
    label: 'Money',
    items: [
      { key: 'payments', label: 'Payments', icon: 'payments' },
      { key: 'paybill', label: 'PayBill', icon: 'account_balance' },
      { key: 'finance', label: 'Finance', icon: 'assessment' },
    ],
  },
  {
    label: 'Network',
    items: [
      { key: 'routers', label: 'Routers', icon: 'router' },
      { key: 'maintenance', label: 'Maintenance', icon: 'calendar_month' },
      { key: 'branches', label: 'Branches', icon: 'add_business' },
    ],
  },
  {
    label: 'People',
    items: [
      { key: 'support', label: 'Support', icon: 'support_agent' },
      { key: 'messages', label: 'Messages', icon: 'chat' },
      { key: 'team', label: 'Team', icon: 'group' },
    ],
  },
  {
    label: 'System',
    items: [
      { key: 'audit', label: 'Audit Log', icon: 'history' },
      { key: 'settings', label: 'Settings', icon: 'settings' },
    ],
  },
]

const NAV = NAV_GROUPS.flatMap((g) => g.items)

function SidebarContent({ tab, onNav, onLogout, badges = {} }) {
  return (
    <div className="flex flex-col h-full py-5 px-3">
      <div className="mb-5 px-4 flex items-center gap-3 shrink-0">
        <Icon name="wifi_tethering" filled className="text-primary-fixed text-[32px]!" />
        <div>
          <p className="text-xl font-bold text-primary-fixed leading-tight">HotspotPro</p>
          <p className="text-[10px] font-semibold tracking-wider text-surface-variant/70">NETWORK MANAGER</p>
        </div>
      </div>
      <nav className="flex flex-col gap-4 flex-1 overflow-y-auto pr-1">
        {NAV_GROUPS.map((group, gi) => (
          <div key={group.label || `g${gi}`}>
            {group.label && (
              <p className="px-4 mb-1 text-[10px] font-bold tracking-[0.12em] uppercase text-surface-variant/50">
                {group.label}
              </p>
            )}
            <ul className="flex flex-col gap-0.5">
              {group.items.map((item) => (
                <li key={item.key}>
                  <button
                    onClick={() => onNav(item.key)}
                    aria-current={tab === item.key ? 'page' : undefined}
                    className={`w-full flex items-center gap-3 px-4 py-2.5 rounded-lg cursor-pointer transition-colors ${
                      tab === item.key
                        ? 'bg-primary-container text-on-primary-container font-semibold'
                        : 'text-surface-variant hover:text-surface-bright hover:bg-surface-container-highest/10'
                    }`}
                  >
                    <Icon name={item.icon} filled={tab === item.key} className="text-[20px]!" />
                    <span className="text-base">{item.label}</span>
                    {badges[item.key] > 0 && (
                      <span className="ml-auto min-w-[20px] h-5 px-1.5 bg-error text-on-error text-xs font-bold rounded-full flex items-center justify-center">
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
      <div className="mt-4 pt-3 border-t border-surface-variant/20 shrink-0">
        <button
          onClick={onLogout}
          className="w-full flex items-center gap-3 px-4 py-2.5 text-surface-variant hover:text-surface-bright hover:bg-surface-container-highest/10 rounded-lg cursor-pointer transition-colors"
        >
          <Icon name="logout" className="text-[20px]!" />
          <span className="text-base">Logout</span>
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
                  <button onClick={() => act(p.id, 'PAID')} className="flex-1 px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold hover:bg-surface-tint transition-colors cursor-pointer">
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
  plans: 'Plans',
  vouchers: 'Vouchers',
  subscribers: 'Subscribers',
  payments: 'Payments',
  paybill: 'PayBill',
  finance: 'Finance',
  routers: 'Routers',
  branches: 'Branches',
  support: 'Support',
  maintenance: 'Maintenance',
  messages: 'Messages',
  team: 'Team',
  audit: 'Audit Log',
  settings: 'Settings',
}

function Shell({ auth, onLogout }) {
  const [tab, setTab] = useState('overview')
  const [drawer, setDrawer] = useState(false)
  const [unreadMessages, setUnreadMessages] = useState(0)

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

  const badges = { messages: unreadMessages }

  return (
    <div className="bg-background text-on-background min-h-screen">
      {/* Desktop sidebar */}
      <nav className="h-screen w-64 fixed left-0 top-0 bg-inverse-surface shadow-md hidden md:flex flex-col z-40">
        <SidebarContent tab={tab} onNav={nav} onLogout={onLogout} badges={badges} />
      </nav>

      {/* Mobile drawer */}
      {drawer && (
        <div className="md:hidden fixed inset-0 z-50 flex">
          <div className="w-64 bg-inverse-surface h-full shadow-xl">
            <SidebarContent tab={tab} onNav={nav} onLogout={onLogout} badges={badges} />
          </div>
          <div className="flex-1 bg-on-background/50" onClick={() => setDrawer(false)}></div>
        </div>
      )}

      {/* Top bar */}
      <header className="fixed top-0 right-0 w-full md:w-[calc(100%-16rem)] h-16 bg-surface shadow-sm z-30 flex justify-between items-center px-5 md:px-6">
        <div className="flex items-center gap-3">
          <button className="md:hidden p-2 -ml-2 text-on-surface cursor-pointer" onClick={() => setDrawer(true)} aria-label="Open menu">
            <Icon name="menu" />
          </button>
          <span className="text-lg font-semibold text-on-surface hidden sm:inline">Hotspot Manager</span>
          <span className="text-on-surface-variant hidden sm:inline">/</span>
          <span className="text-lg font-bold text-primary">{TAB_TITLES[tab]}</span>
        </div>
        <div className="flex items-center gap-2">
          <PayoutBell auth={auth} />
          <div className="w-9 h-9 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center font-semibold border border-outline-variant">
            A
          </div>
        </div>
      </header>

      {/* Content */}
      <main className="md:ml-64 pt-24 px-5 md:px-8 pb-8 max-w-[1600px]">
        {tab === 'overview' && <Overview auth={auth} onNav={nav} />}
        {tab === 'plans' && <Plans auth={auth} />}
        {tab === 'vouchers' && <Vouchers auth={auth} />}
        {tab === 'subscribers' && <Subscribers auth={auth} />}
        {tab === 'payments' && <Payments auth={auth} />}
        {tab === 'paybill' && <PayBillPage auth={auth} />}
        {tab === 'finance' && <FinancePage auth={auth} />}
        {tab === 'routers' && <RoutersPage auth={auth} />}
        {tab === 'branches' && <BranchesPage auth={auth} />}
        {tab === 'audit' && <AuditLogPage auth={auth} />}
        {tab === 'support' && <Support auth={auth} />}
        {tab === 'maintenance' && <Maintenance auth={auth} />}
        {tab === 'messages' && <Messages auth={auth} />}
        {tab === 'team' && <Team auth={auth} />}
        {tab === 'settings' && <Settings auth={auth} />}
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
  const shown = useCountUp(value)
  return (
    <div
      className={`bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] fade-up ${
        accent ? `border-t-4 ${accent}` : ''
      } ${wide ? 'xl:col-span-2' : 'xl:col-span-1'}`}
      style={{ animationDelay: `${index * 70}ms` }}
    >
      <div className="flex justify-between items-start mb-2">
        <CardLabel>{label}</CardLabel>
        <Icon name={icon} className={iconClass} />
      </div>
      <div className={`${wide ? 'text-4xl' : 'text-3xl'} font-bold tracking-tight text-on-surface tabular-nums`}>{format(shown)}</div>
      {trend != null && (
        <div className={`flex items-center gap-1 text-sm mt-2 ${trend >= 0 ? 'text-secondary' : 'text-error'}`}>
          <Icon name={trend >= 0 ? 'trending_up' : 'trending_down'} className="text-[16px]!" />
          <span>{trend >= 0 ? '+' : ''}{trend}% vs previous 30 days</span>
        </div>
      )}
    </div>
  )
}

function Overview({ auth, onNav }) {
  const [stats, setStats] = useState(null)
  const [payments, setPayments] = useState([])
  const [range, setRange] = useState(30)

  useEffect(() => {
    api('/admin/stats', { auth }).then(setStats).catch(() => {})
    api('/admin/payments', { auth }).then(setPayments).catch(() => {})
  }, [auth])

  const recent = useMemo(
    () => [...payments].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)).slice(0, 5),
    [payments]
  )

  if (!stats) {
    return (
      <div>
        <div className="mb-8">
          <Skeleton className="h-10 w-64 mb-3" />
          <Skeleton className="h-5 w-96" />
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4 mb-8">
          <Skeleton className="h-32 xl:col-span-2" />
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
          <Skeleton className="h-32" />
        </div>
        <Skeleton className="h-64" />
      </div>
    )
  }

  const totalVouchers = (stats.activeVouchers || 0) + (stats.unusedVouchers || 0)
  const utilization = totalVouchers ? Math.round((stats.activeVouchers / totalVouchers) * 100) : 0

  // Revenue trend: successful payments in the last 30 days vs the 30 days before that
  const now = Date.now()
  const revenueIn = (fromDays, toDays) =>
    payments
      .filter((p) => p.status === 'SUCCESS')
      .filter((p) => {
        const age = (now - new Date(p.createdAt)) / 86400000
        return age >= toDays && age < fromDays
      })
      .reduce((a, p) => a + Number(p.amount), 0)
  const rev30 = revenueIn(30, 0)
  const prev30 = revenueIn(60, 30)
  const trend = prev30 > 0 ? Math.round(((rev30 - prev30) / prev30) * 100) : null

  return (
    <div>
      <div className="mb-8">
        <h2 className="text-4xl font-bold tracking-tight text-on-surface">Overview</h2>
        <p className="text-base text-on-surface-variant mt-2">Monitor your hotspot network performance.</p>
      </div>

      {/* KPI bento grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 gap-4 mb-8">
        <KpiCard label="Total Revenue" icon="payments" iconClass="text-primary" value={stats.totalRevenue} format={fmtKES} accent="border-primary" wide index={0} trend={trend} />
        <KpiCard label="Successful" icon="check_circle" iconClass="text-secondary" value={stats.successfulPayments} index={1} />
        <KpiCard label="Pending" icon="pending" iconClass="text-tertiary" value={stats.pendingPayments} index={2} />
        <KpiCard label="Failed" icon="error" iconClass="text-error" value={stats.failedPayments} accent="border-error" index={3} />
      </div>

      {/* Vouchers + chart */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mb-8">
        <div className="lg:col-span-1 flex flex-col gap-4">
          <div className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] flex-1">
            <div className="flex justify-between items-start mb-4">
              <CardLabel>Active Vouchers</CardLabel>
              <Icon name="wifi" className="text-primary" />
            </div>
            <div className="text-4xl font-bold tracking-tight text-on-surface mb-4">{stats.activeVouchers}</div>
            <div className="w-full bg-surface-variant rounded-full h-2 mb-2">
              <div className="bg-primary h-2 rounded-full" style={{ width: `${utilization}%` }}></div>
            </div>
            <p className="text-sm text-on-surface-variant text-right">{utilization}% utilization</p>
          </div>
          <div className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] flex-1">
            <div className="flex justify-between items-start mb-4">
              <CardLabel>Unused Vouchers</CardLabel>
              <Icon name="inbox" className="text-outline" />
            </div>
            <div className="text-4xl font-bold tracking-tight text-on-surface mb-2">{stats.unusedVouchers}</div>
            <button
              onClick={() => onNav('vouchers')}
              className="mt-4 text-xs font-semibold tracking-wider text-primary border border-primary px-4 py-2 rounded-lg hover:bg-primary/5 transition-colors w-full h-12 flex items-center justify-center gap-2 cursor-pointer"
            >
              <Icon name="add" className="text-[18px]!" />
              GENERATE NEW
            </button>
          </div>
        </div>

        <div className="lg:col-span-2 bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] relative overflow-hidden flex flex-col">
          <div className="absolute inset-0 bg-gradient-to-br from-primary/5 to-transparent z-0"></div>
          <div className="relative z-10 mb-6 flex justify-between items-end">
            <div>
              <h3 className="text-lg font-semibold text-on-surface mb-1">Revenue Trend</h3>
              <p className="text-sm text-on-surface-variant">Last {range} days performance</p>
            </div>
            <div className="flex gap-2">
              {[7, 30].map((d) => (
                <button
                  key={d}
                  onClick={() => setRange(d)}
                  className={`px-3 py-1 text-xs font-semibold tracking-wider rounded-full cursor-pointer transition-colors ${
                    range === d ? 'bg-primary text-on-primary' : 'bg-surface-variant text-on-surface-variant hover:bg-surface-dim'
                  }`}
                >
                  {d}D
                </button>
              ))}
            </div>
          </div>
          <div className="relative z-10 flex-1 flex flex-col">
            <RevenueChart payments={payments} days={range} />
          </div>
        </div>
      </div>

      {/* Recent payments */}
      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] overflow-hidden">
        <div className="p-6 border-b border-outline-variant/30 flex justify-between items-center">
          <h3 className="text-lg font-semibold text-on-surface">Recent Payments</h3>
          <button
            onClick={() => onNav('payments')}
            className="text-xs font-semibold tracking-wider text-primary hover:underline flex items-center gap-1 cursor-pointer"
          >
            VIEW ALL <Icon name="arrow_forward" className="text-[16px]!" />
          </button>
        </div>
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse">
            <thead>
              <tr className="bg-surface-container-low text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="font-semibold">Phone</th>
                <th className="font-semibold">Plan</th>
                <th className="font-semibold">Amount</th>
                <th className="font-semibold">Status</th>
                <th className="font-semibold">Time</th>
              </tr>
            </thead>
            <tbody className="text-sm text-on-surface divide-y divide-outline-variant/20">
              {recent.map((p) => (
                <tr key={p.id} className="hover:bg-surface-container-low/50 transition-colors">
                  <td className="font-medium">{p.phoneNumber}</td>
                  <td className="">{p.plan?.name}</td>
                  <td className="font-medium tabular-nums">{fmtKES(p.amount)}</td>
                  <td className=""><StatusPill status={p.status} /></td>
                  <td className="text-on-surface-variant">{relativeTime(p.createdAt)}</td>
                </tr>
              ))}
              {recent.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={5}>No payments yet.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Plans                                                               */
/* ------------------------------------------------------------------ */

const DURATION_UNITS = { minutes: 1, hours: 60, days: 1440 }

function PlanModal({ auth, onClose, onSaved }) {
  const [form, setForm] = useState({ name: '', price: '', durationValue: '', durationUnit: 'hours', mbps: '', maxDevices: 1 })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const totalMinutes = Math.round(Number(form.durationValue || 0) * DURATION_UNITS[form.durationUnit])

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/admin/plans', {
        method: 'POST',
        auth,
        body: {
          name: form.name,
          price: Number(form.price),
          durationMinutes: totalMinutes,
          bandwidth: form.mbps ? `${form.mbps}M/${form.mbps}M` : null,
          maxDevices: Number(form.maxDevices) || 1,
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
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[48px]'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col">
        <div className="p-6 border-b border-surface-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">Create New Plan</h3>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4">
            <div>
              <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Plan Name</label>
              <input className={inputCls} placeholder="e.g. 12 Hours Pass" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Price (KES)</label>
                <input className={inputCls} placeholder="e.g. 80" type="number" min="1" required value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} />
              </div>
              <div>
                <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Duration</label>
                <div className="flex gap-2">
                  <input
                    className={`${inputCls} flex-1 min-w-0`}
                    placeholder="e.g. 8"
                    type="number"
                    min="1"
                    step="1"
                    required
                    value={form.durationValue}
                    onChange={(e) => setForm({ ...form, durationValue: e.target.value })}
                  />
                  <select
                    className={`${inputCls} w-auto`}
                    value={form.durationUnit}
                    onChange={(e) => setForm({ ...form, durationUnit: e.target.value })}
                  >
                    <option value="minutes">Minutes</option>
                    <option value="hours">Hours</option>
                    <option value="days">Days</option>
                  </select>
                </div>
                {totalMinutes > 0 && (
                  <p className="text-xs font-semibold tracking-wider text-tertiary mt-1">
                    = {formatDuration(totalMinutes)} · shown under "{planGroup(totalMinutes)}" on the portal
                  </p>
                )}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Bandwidth Limit</label>
                <div className="relative">
                  <input className={`${inputCls} pr-16`} placeholder="e.g. 5" type="number" min="1" value={form.mbps} onChange={(e) => setForm({ ...form, mbps: e.target.value })} />
                  <span className="absolute right-4 top-1/2 -translate-y-1/2 text-xs font-semibold tracking-wider text-on-surface-variant">Mbps</span>
                </div>
                <p className="text-xs font-semibold tracking-wider text-tertiary mt-1">Applied as the MikroTik rate limit (e.g. 5M/5M).</p>
              </div>
              <div>
                <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Devices Allowed</label>
                <input className={inputCls} type="number" min="1" max="10" required value={form.maxDevices} onChange={(e) => setForm({ ...form, maxDevices: e.target.value })} />
                <p className="text-xs font-semibold tracking-wider text-tertiary mt-1">Simultaneous devices per voucher — the router enforces this.</p>
              </div>
            </div>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-surface-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-6 py-3 rounded-lg text-lg font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[48px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-6 py-3 rounded-lg text-lg font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[48px] disabled:opacity-60 cursor-pointer">
              {busy ? 'Saving…' : 'Save Plan'}
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

function Plans({ auth }) {
  const [plans, setPlans] = useState([])
  const [search, setSearch] = useState('')
  const [modal, setModal] = useState(false)

  const load = () => api('/admin/plans', { auth }).then(setPlans).catch(() => {})
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const filtered = plans.filter((p) => p.name.toLowerCase().includes(search.toLowerCase()))
  const active = plans.filter((p) => p.active)
  const avgPrice = plans.length ? Math.round(plans.reduce((a, p) => a + Number(p.price), 0) / plans.length) : 0

  return (
    <div>
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-6">
        <div>
          <h2 className="text-4xl font-bold tracking-tight text-on-background">Data Packages</h2>
          <p className="text-base text-on-surface-variant mt-1">Manage bandwidth profiles and pricing for your network.</p>
        </div>
        <button
          onClick={() => setModal(true)}
          className="bg-primary text-on-primary text-lg font-semibold px-6 py-3 rounded-lg flex items-center gap-2 shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 whitespace-nowrap min-h-[48px] cursor-pointer"
        >
          <Icon name="add" />
          New Plan
        </button>
      </div>

      {/* Stat mini-cards */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-8">
        <div className="bg-surface-container-lowest p-4 rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 border-t-4 border-t-primary">
          <CardLabel>Active Plans</CardLabel>
          <div className="text-4xl font-bold tracking-tight mt-2 text-on-background">{active.length}</div>
        </div>
        <div className="bg-surface-container-lowest p-4 rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30">
          <CardLabel>Total Plans</CardLabel>
          <div className="text-4xl font-bold tracking-tight mt-2 text-on-background">{plans.length}</div>
        </div>
        <div className="bg-surface-container-lowest p-4 rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30">
          <CardLabel>Avg. Price</CardLabel>
          <div className="text-4xl font-bold tracking-tight mt-2 text-on-background">{fmtKES(avgPrice)}</div>
        </div>
      </div>

      {/* Table card */}
      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 overflow-hidden">
        <div className="p-4 border-b border-surface-variant/50 bg-surface-container-low/30">
          <div className="relative w-full sm:w-64">
            <Icon name="search" className="absolute left-3 top-1/2 -translate-y-1/2 text-outline" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full bg-surface border border-surface-variant rounded-lg pl-10 pr-4 py-2 text-sm focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[48px]"
              placeholder="Search plans..."
              type="text"
            />
          </div>
        </div>
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[700px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="border-b border-surface-variant/50">Plan Name</th>
                <th className="border-b border-surface-variant/50">Price</th>
                <th className="border-b border-surface-variant/50">Duration</th>
                <th className="border-b border-surface-variant/50">Bandwidth</th>
                <th className="border-b border-surface-variant/50">Status</th>
                <th className="border-b border-surface-variant/50 text-right">Enabled</th>
              </tr>
            </thead>
            <tbody className="text-sm">
              {filtered.map((p) => (
                <tr key={p.id} className="border-b border-surface-variant/30 hover:bg-surface-container-low/20 transition-colors">
                  <td className="">
                    <div className="text-lg font-semibold text-on-background">{p.name}</div>
                    {p.bandwidth && <div className="text-xs font-semibold tracking-wider text-on-surface-variant mt-1">Rate limit: {p.bandwidth}</div>}
                  </td>
                  <td className="text-lg font-semibold tabular-nums">{fmtKES(p.price)}</td>
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
                  </td>
                  <td className=""><StatusPill status={p.active ? 'ACTIVE' : 'INACTIVE'} /></td>
                  <td className="text-right">
                    <Toggle checked={p.active} onChange={() => api(`/admin/plans/${p.id}/toggle`, { method: 'PATCH', auth }).then(load)} />
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={6}>No plans found.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {modal && <PlanModal auth={auth} onClose={() => setModal(false)} onSaved={() => { setModal(false); load() }} />}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Vouchers                                                            */
/* ------------------------------------------------------------------ */

function printVouchers(vouchers, planName) {
  const cards = vouchers.map((v) => `
    <div class="card">
      <div class="head"><strong>SPA WiFi</strong><span>INTERNET ACCESS</span></div>
      <div class="code-box"><small>ACCESS CODE</small><div class="code">${v.code}</div></div>
      <div class="foot"><span>${planName}</span><span>Use as WiFi username &amp; password</span></div>
    </div>`).join('')
  const w = window.open('', '_blank')
  w.document.write(`<!doctype html><html><head><title>Voucher batch — ${planName}</title><style>
    body { font-family: Arial, sans-serif; margin: 10mm; }
    .grid { display: flex; flex-wrap: wrap; gap: 6mm; }
    .card { width: 85mm; height: 54mm; border: 1px dashed #6e7977; border-top: 3px solid #005c55; border-radius: 4mm;
            padding: 5mm; box-sizing: border-box; display: flex; flex-direction: column; justify-content: space-between;
            page-break-inside: avoid; }
    .head { display: flex; justify-content: space-between; color: #005c55; font-size: 12px; }
    .code-box { text-align: center; border: 1px solid #bdc9c6; border-radius: 2mm; padding: 3mm; }
    .code-box small { color: #6e7977; letter-spacing: 1px; font-size: 9px; }
    .code { font-family: 'Courier New', monospace; font-size: 22px; font-weight: bold; letter-spacing: 3px; }
    .foot { display: flex; justify-content: space-between; font-size: 10px; color: #3e4947; }
  </style></head><body><div class="grid">${cards}</div><script>window.onload = () => window.print()<\/script></body></html>`)
  w.document.close()
}

function Vouchers({ auth }) {
  const [vouchers, setVouchers] = useState([])
  const [plans, setPlans] = useState([])
  const [planId, setPlanId] = useState('')
  const [customMin, setCustomMin] = useState(60)
  const [count, setCount] = useState(10)
  const [prefix, setPrefix] = useState('')
  const [codeLen, setCodeLen] = useState(8)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(null)
  const [issuerFilter, setIssuerFilter] = useState('all')

  const load = () => api('/admin/vouchers', { auth }).then(setVouchers).catch(() => {})
  useEffect(() => {
    load()
    api('/admin/plans', { auth }).then((ps) => { setPlans(ps); if (ps[0]) setPlanId(String(ps[0].id)) }).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function generate() {
    setBusy(true)
    setError(null)
    try {
      const isCustom = planId === 'custom'
      await api('/admin/vouchers/generate', {
        method: 'POST',
        auth,
        body: {
          planId: isCustom ? null : Number(planId),
          customMinutes: isCustom ? Number(customMin) : null,
          count: Number(count),
          prefix: prefix.trim() || null,
          codeLength: Number(codeLen) || 8,
        },
      })
      load()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const isCustom = planId === 'custom'
  const selectedPlan = plans.find((p) => String(p.id) === String(planId))
  const unusedForPlan = vouchers.filter((v) =>
    v.status === 'UNUSED' &&
    (isCustom ? v.customDurationMinutes != null : String(v.plan?.id) === String(planId) && !v.customDurationMinutes)
  )
  const previewCode = unusedForPlan[0]?.code || 'MCLRRC8H'
  const previewName = isCustom ? `Custom — ${formatDuration(Number(customMin) || 0)}` : selectedPlan?.name

  function copy(code) {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(code)
      setTimeout(() => setCopied(null), 1500)
    })
  }

  return (
    <div>
      {/* Toolbar */}
      <div className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] mb-6 flex flex-col lg:flex-row gap-4 items-start lg:items-end justify-between border border-surface-container-highest/50">
        <div className="flex flex-col sm:flex-row gap-4 items-start sm:items-end w-full lg:w-auto">
          <div className="flex flex-col gap-1 w-full sm:w-auto">
            <label className="text-xs font-semibold tracking-wider text-tertiary">SELECT PLAN</label>
            <select
              value={planId}
              onChange={(e) => setPlanId(e.target.value)}
              className="bg-background border border-surface-variant rounded-lg px-4 py-2 text-on-surface text-base focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none h-12 min-w-[200px]"
            >
              <PlanOptions plans={plans.filter((p) => p.name !== 'Custom Time')} />
              <optgroup label="Custom">
                <option value="custom">Custom time…</option>
              </optgroup>
            </select>
          </div>
          {planId === 'custom' && (
            <div className="flex flex-col gap-1 w-full sm:w-auto">
              <label className="text-xs font-semibold tracking-wider text-tertiary" htmlFor="v-custom-min">MINUTES</label>
              <input
                id="v-custom-min"
                type="number"
                min="1"
                max="44640"
                value={customMin}
                onChange={(e) => setCustomMin(e.target.value)}
                className="bg-background border border-surface-variant rounded-lg px-4 py-2 text-on-surface text-base focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none h-12 w-full sm:w-28 tabular-nums"
              />
            </div>
          )}
          <div className="flex flex-col gap-1 w-full sm:w-auto">
            <label className="text-xs font-semibold tracking-wider text-tertiary">QUANTITY</label>
            <div className="flex items-center border border-surface-variant rounded-lg bg-background h-12 overflow-hidden">
              <button onClick={() => setCount(Math.max(1, Number(count) - 1))} className="px-4 text-on-surface-variant hover:bg-surface-container-low hover:text-primary transition-colors h-full flex items-center cursor-pointer">
                <Icon name="remove" className="text-[20px]!" />
              </button>
              <input
                type="number"
                min="1"
                max="500"
                value={count}
                onChange={(e) => setCount(e.target.value)}
                className="w-16 text-center text-lg font-semibold text-on-surface border-none bg-transparent focus:ring-0 focus:outline-none px-0 h-full [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              />
              <button onClick={() => setCount(Math.min(500, Number(count) + 1))} className="px-4 text-on-surface-variant hover:bg-surface-container-low hover:text-primary transition-colors h-full flex items-center cursor-pointer">
                <Icon name="add" className="text-[20px]!" />
              </button>
            </div>
          </div>
          <div className="flex flex-col gap-1 w-full sm:w-auto">
            <label className="text-xs font-semibold tracking-wider text-tertiary" htmlFor="v-prefix">CODE PREFIX (OPTIONAL)</label>
            <input
              id="v-prefix"
              type="text"
              maxLength={12}
              value={prefix}
              onChange={(e) => setPrefix(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))}
              placeholder="e.g. SPA"
              className="bg-background border border-surface-variant rounded-lg px-4 py-2 text-on-surface text-base font-mono uppercase focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none h-12 w-full sm:w-32"
            />
          </div>
          <div className="flex flex-col gap-1 w-full sm:w-auto">
            <label className="text-xs font-semibold tracking-wider text-tertiary" htmlFor="v-len">CODE LENGTH</label>
            <input
              id="v-len"
              type="number"
              min={Math.max(6, prefix.length + 4)}
              max="16"
              value={codeLen}
              onChange={(e) => setCodeLen(e.target.value)}
              className="bg-background border border-surface-variant rounded-lg px-4 py-2 text-on-surface text-base focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none h-12 w-full sm:w-24 tabular-nums"
            />
          </div>
        </div>
        <div className="flex flex-col sm:flex-row gap-3 w-full lg:w-auto">
          <button
            onClick={() => unusedForPlan.length && printVouchers(unusedForPlan, previewName || '')}
            disabled={!unusedForPlan.length}
            className="h-12 px-6 rounded-lg border-2 border-primary text-primary text-lg font-semibold hover:bg-primary/5 transition-colors flex items-center justify-center gap-2 disabled:opacity-50 cursor-pointer"
          >
            <Icon name="print" className="text-[20px]!" />
            Print Batch
          </button>
          <button
            onClick={generate}
            disabled={busy || !planId || (isCustom && !(Number(customMin) > 0))}
            className="h-12 px-6 rounded-lg bg-primary text-on-primary text-lg font-semibold shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-colors flex items-center justify-center gap-2 disabled:opacity-60 cursor-pointer"
          >
            <Icon name="add_circle" className="text-[20px]!" />
            {busy ? 'Generating…' : 'Generate Vouchers'}
          </button>
        </div>
      </div>
      {error && <p className="text-sm text-error mb-4">{error}</p>}

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
        {/* Table */}
        <div className="lg:col-span-3 bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-container-highest/50 overflow-hidden">
          <div className="p-4 border-b border-surface-container-high flex justify-between items-center gap-3 flex-wrap bg-surface-bright">
            <h2 className="text-lg font-semibold text-on-surface">Voucher Inventory</h2>
            <div className="flex items-center gap-3">
              <select
                value={issuerFilter}
                onChange={(e) => setIssuerFilter(e.target.value)}
                className="bg-background border border-surface-variant rounded-lg px-3 py-1.5 text-sm text-on-surface focus:border-primary outline-none h-9"
                aria-label="Filter by issuer"
              >
                <option value="all">Issued by: everyone</option>
                <option value="customer">Customers (paid online)</option>
                {[...new Set(vouchers.map((v) => v.createdBy).filter(Boolean))].map((u) => (
                  <option key={u} value={u}>{u}</option>
                ))}
              </select>
              <span className="px-3 py-1 rounded-full bg-surface-container text-on-surface-variant text-xs font-semibold tracking-wider">
                Total: {vouchers.length}
              </span>
            </div>
          </div>
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full text-left border-collapse min-w-[700px]">
              <thead>
                <tr className="bg-surface text-tertiary border-b border-surface-container-high text-xs font-semibold tracking-wider">
                  <th className="">VOUCHER CODE</th>
                  <th className="">PLAN</th>
                  <th className="">STATUS</th>
                  <th className="">BUYER</th>
                  <th className="">ISSUED BY</th>
                  <th className="">CREATED</th>
                  <th className="">EXPIRES</th>
                  <th className="text-right">ACTIONS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-container-high text-sm text-on-surface">
                {vouchers.filter((v) =>
                  issuerFilter === 'all' ? true : issuerFilter === 'customer' ? !v.createdBy : v.createdBy === issuerFilter
                ).map((v) => (
                  <tr key={v.id} className={`hover:bg-surface-container-low/50 transition-colors ${v.status === 'EXPIRED' ? 'opacity-75' : ''}`}>
                    <td className="">
                      <span className={`text-lg font-mono tracking-[2px] ${v.status === 'UNUSED' ? 'text-primary' : v.status === 'EXPIRED' ? 'text-on-surface-variant line-through' : 'text-on-surface'}`}>
                        {v.code}
                      </span>
                      {v.boundMac && (
                        <div className="flex items-center gap-1 text-xs text-on-surface-variant mt-1">
                          <Icon name="lock" className="text-[14px]!" />
                          <span className="font-mono">{v.boundMac}</span>
                        </div>
                      )}
                    </td>
                    <td className="">
                      {v.customDurationMinutes != null
                        ? <>Custom · {formatDuration(v.customDurationMinutes)}</>
                        : v.plan?.name}
                    </td>
                    <td className=""><StatusPill status={v.status} /></td>
                    <td className="">{v.phoneNumber || <span className="text-on-surface-variant">—</span>}</td>
                    <td className="">
                      {v.createdBy
                        ? <span className="capitalize">{v.createdBy}</span>
                        : <span className="text-on-surface-variant">{v.phoneNumber ? 'Customer' : '—'}</span>}
                    </td>
                    <td className="text-on-surface-variant whitespace-nowrap">{fmtDate(v.createdAt)}, {fmtTime(v.createdAt)}</td>
                    <td className="text-on-surface-variant whitespace-nowrap">{v.expiresAt ? `${fmtDate(v.expiresAt)}, ${fmtTime(v.expiresAt)}` : '—'}</td>
                    <td className="text-right whitespace-nowrap">
                      <button onClick={() => copy(v.code)} className="text-tertiary hover:text-primary transition-colors p-1 cursor-pointer" aria-label={`Copy ${v.code}`}>
                        <Icon name={copied === v.code ? 'check' : 'content_copy'} className="text-[20px]!" />
                      </button>
                      {v.status === 'ACTIVE' && (
                        <button
                          onClick={() => api(`/admin/vouchers/${v.id}/revoke`, { method: 'PATCH', auth }).then(load).catch(() => {})}
                          className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer"
                          aria-label={`Disable ${v.code}`}
                          title="Disable — kicks the device off and expires the voucher"
                        >
                          <Icon name="block" className="text-[20px]!" />
                        </button>
                      )}
                      {v.boundMac && (
                        <button
                          onClick={() => api(`/admin/vouchers/${v.id}/unbind`, { method: 'PATCH', auth }).then(load).catch(() => {})}
                          className="text-tertiary hover:text-primary transition-colors p-1 cursor-pointer"
                          aria-label={`Unbind ${v.code} from its device`}
                          title="Unbind from device"
                        >
                          <Icon name="link_off" className="text-[20px]!" />
                        </button>
                      )}
                      {v.status === 'UNUSED' && (
                        <button
                          onClick={() => api(`/admin/vouchers/${v.id}`, { method: 'DELETE', auth }).then(load).catch(() => {})}
                          className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer"
                          aria-label={`Delete ${v.code}`}
                        >
                          <Icon name="delete" className="text-[20px]!" />
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
                {vouchers.length === 0 && (
                  <tr><td className="text-on-surface-variant" colSpan={8}>No vouchers yet — generate a batch above.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Print preview */}
        <div className="lg:col-span-1">
          <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-container-highest/50 p-4 lg:sticky lg:top-24">
            <h3 className="text-lg font-semibold text-on-surface mb-4">Print Preview</h3>
            <div className="border-2 border-dashed border-outline-variant p-4 rounded-lg bg-surface flex flex-col gap-4 relative overflow-hidden">
              <div className="absolute top-0 left-0 w-full h-1 bg-primary"></div>
              <div className="flex justify-between items-start">
                <div>
                  <p className="text-2xl font-bold text-primary leading-none">SPA WiFi</p>
                  <p className="text-xs font-semibold tracking-wider text-tertiary mt-1">INTERNET ACCESS</p>
                </div>
                <Icon name="wifi" filled className="text-primary-container text-[32px]!" />
              </div>
              <div className="text-center py-4 bg-background rounded border border-surface-container-high">
                <p className="text-xs font-semibold tracking-wider text-tertiary mb-1">ACCESS CODE</p>
                <p className="text-3xl font-bold font-mono tracking-[3px] text-on-surface">{previewCode}</p>
              </div>
              <div className="flex justify-between items-end border-t border-surface-container-high pt-2">
                <div>
                  <p className="text-base font-semibold text-on-surface">{previewName || 'Plan'}</p>
                  <p className="text-sm text-on-surface-variant">
                    {isCustom
                      ? `Valid for ${formatDuration(Number(customMin) || 0)}`
                      : selectedPlan ? `Valid for ${formatDuration(selectedPlan.durationMinutes)}` : ''}
                  </p>
                </div>
                <p className="text-lg font-semibold text-primary">{!isCustom && selectedPlan ? `KES ${selectedPlan.price}` : ''}</p>
              </div>
            </div>
            <button
              onClick={() => unusedForPlan.length && printVouchers(unusedForPlan, selectedPlan?.name || '')}
              disabled={!unusedForPlan.length}
              className="mt-4 w-full h-12 rounded-lg bg-primary text-on-primary text-base font-semibold flex items-center justify-center gap-2 hover:bg-surface-tint transition-colors shadow-[0_4px_12px_rgba(15,23,42,0.08)] active:scale-95 disabled:opacity-50 cursor-pointer"
            >
              <Icon name="print" className="text-[20px]!" />
              Print {unusedForPlan.length} Unused Voucher{unusedForPlan.length === 1 ? '' : 's'}
            </button>
            <p className="text-sm text-on-surface-variant mt-3 text-center">
              Standard 85×54mm format.<br />Optimized for thermal printers.
            </p>
          </div>
        </div>
      </div>
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
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[48px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-surface-variant/50 flex justify-between items-center">
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
                <label className={labelCls}>Phone (M-Pesa)</label>
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
                <option value="MPESA">Send M-Pesa STK — months credited after they pay</option>
              </select>
            </div>
            <p className="text-xs font-semibold tracking-wider text-tertiary">
              The PPPoE username and password go into the customer's router (PPPoE client). The account is created on the MikroTik automatically.
            </p>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-surface-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-6 py-3 rounded-lg text-lg font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[48px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-6 py-3 rounded-lg text-lg font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[48px] disabled:opacity-60 cursor-pointer">
              {busy ? 'Creating…' : 'Create Subscriber'}
            </button>
          </div>
        </form>
      </div>
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
    <div className="flex justify-between items-center py-2 border-b border-surface-variant border-dashed gap-4">
      <span className="text-sm text-on-surface-variant shrink-0">{label}</span>
      <span className="text-sm font-medium text-on-surface text-right">{children}</span>
    </div>
  )

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="flex-1 bg-on-background/30 backdrop-blur-[2px]" onClick={onClose}></div>
      <div className="w-full max-w-md bg-surface-container-lowest h-full shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col overflow-hidden">
        <div className="p-6 border-b border-surface-variant bg-surface-bright flex justify-between items-start">
          <div>
            <h3 className="text-lg font-semibold text-on-surface">{s.fullName}</h3>
            <p className="text-sm text-on-surface-variant mt-1 font-mono">{s.pppoeUsername}</p>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-full hover:bg-surface-container flex items-center justify-center text-on-surface-variant transition-colors cursor-pointer" aria-label="Close details">
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1 space-y-6">
          <div className={`flex flex-col items-center justify-center py-4 bg-surface-container-low rounded-xl border-t-4 ${
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
              {s.lastPaymentMethod
                ? `${s.lastPaymentMethod === 'MPESA' ? 'M-Pesa' : 'Cash'}${s.lastPaymentAt ? ` · ${fmtDate(s.lastPaymentAt)}` : ''}`
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
              <button disabled={busy} onClick={() => run(`/admin/subscribers/${s.id}/stk`, { months: Number(months) }, 'STK prompt sent.')}
                className="h-10 px-3 rounded-lg bg-primary text-on-primary text-xs font-semibold disabled:opacity-60 cursor-pointer">
                Send M-Pesa STK
              </button>
            </div>
          </div>

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
              <ul className="divide-y divide-surface-variant">
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

        <div className="p-4 border-t border-surface-variant bg-surface-bright flex gap-3">
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

function Subscribers({ auth }) {
  const [subs, setSubs] = useState(null)
  const [modal, setModal] = useState(false)
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
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-6">
        <div>
          <h2 className="text-4xl font-bold tracking-tight text-on-background">Subscribers</h2>
          <p className="text-base text-on-surface-variant mt-1">Monthly PPPoE home &amp; office customers.</p>
        </div>
        <button
          onClick={() => setModal(true)}
          className="bg-primary text-on-primary text-lg font-semibold px-6 py-3 rounded-lg flex items-center gap-2 shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 whitespace-nowrap min-h-[48px] cursor-pointer"
        >
          <Icon name="add" />
          Add Subscriber
        </button>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
        {[
          ['Active', active.length, 'border-t-primary'],
          ['Suspended', subs.length - active.length, ''],
          ['Expiring ≤3 days', expiring, 'border-t-[#f59e0b]'],
          ['Monthly Revenue', fmtKES(mrr), 'border-t-secondary'],
        ].map(([label, value, accent]) => (
          <div key={label} className={`bg-surface-container-lowest p-4 rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 ${accent ? `border-t-4 ${accent}` : ''}`}>
            <CardLabel>{label}</CardLabel>
            <div className="text-3xl font-bold tracking-tight mt-2 text-on-background tabular-nums">{value}</div>
          </div>
        ))}
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[900px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="border-b border-surface-variant/50">Customer</th>
                <th className="border-b border-surface-variant/50">PPPoE Login</th>
                <th className="border-b border-surface-variant/50">Package</th>
                <th className="border-b border-surface-variant/50">Paid Until</th>
                <th className="border-b border-surface-variant/50">Last Payment</th>
                <th className="border-b border-surface-variant/50">Status</th>
                <th className="border-b border-surface-variant/50 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm">
              {subs.map((s) => {
                const st = subscriberState(s)
                const days = Math.floor((new Date(s.paidUntil) - Date.now()) / 86400000)
                return (
                  <tr key={s.id} className="border-b border-surface-variant/30 hover:bg-surface-container-low/20 transition-colors align-top">
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
                          className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold hover:bg-surface-tint transition-colors cursor-pointer"
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
    <div className="flex justify-between items-center py-2 border-b border-surface-variant border-dashed gap-4">
      <span className="text-sm text-on-surface-variant shrink-0">{label}</span>
      {children}
    </div>
  )

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="flex-1 bg-on-background/30 backdrop-blur-[2px]" onClick={onClose}></div>
      <div className="w-full max-w-md bg-surface-container-lowest h-full shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col overflow-hidden">
        <div className="p-6 border-b border-surface-variant bg-surface-bright flex justify-between items-start">
          <div>
            <h3 className="text-lg font-semibold text-on-surface">Transaction Details</h3>
            <p className="text-sm text-on-surface-variant mt-1 font-mono">{payment.mpesaReceiptNumber || `Payment #${payment.id}`}</p>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-full hover:bg-surface-container flex items-center justify-center text-on-surface-variant transition-colors cursor-pointer" aria-label="Close details">
            <Icon name="close" />
          </button>
        </div>

        <div className="p-6 overflow-y-auto flex-1 space-y-6">
          <div className={`flex flex-col items-center justify-center py-4 bg-surface-container-low rounded-xl border-t-4 ${
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
            <h4 className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase mb-2">M-Pesa Information</h4>
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
  const [payments, setPayments] = useState([])
  const [filter, setFilter] = useState('All')
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState(null)

  useEffect(() => { api('/admin/payments', { auth }).then(setPayments).catch(() => {}) }, [auth])

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return [...payments]
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

  return (
    <div>
      <div className="mb-6 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-4xl font-bold tracking-tight text-on-surface mb-2">Payments Ledger</h2>
          <p className="text-base text-on-surface-variant">Track and reconcile M-Pesa transactions</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {PAYMENT_FILTERS.map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
                filter === f
                  ? 'bg-primary-container text-on-primary-container font-semibold'
                  : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
              }`}
            >
              {f}
            </button>
          ))}
        </div>
      </div>

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] overflow-hidden border border-surface-variant">
        <div className="p-4 border-b border-surface-variant flex flex-col sm:flex-row gap-4 justify-between items-center bg-surface-bright">
          <div className="relative w-full sm:w-72">
            <Icon name="search" className="absolute left-3 top-1/2 -translate-y-1/2 text-outline" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full bg-background border border-outline-variant rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-primary focus:ring-1 focus:ring-primary/20 transition-all"
              placeholder="Search receipt, phone, code..."
              type="text"
            />
          </div>
          <button
            onClick={() => exportCsv(filtered)}
            className="flex items-center gap-2 px-4 py-2 border border-outline-variant rounded-lg text-on-surface hover:bg-surface-container text-sm transition-colors w-full sm:w-auto justify-center cursor-pointer"
          >
            <Icon name="download" className="text-[18px]!" /> Export CSV
          </button>
        </div>

        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[800px]">
            <thead>
              <tr className="border-b border-surface-variant bg-surface-container-low text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="font-semibold">Date / Time</th>
                <th className="font-semibold">Phone Number</th>
                <th className="font-semibold">Plan</th>
                <th className="font-semibold">Amount</th>
                <th className="font-semibold">M-Pesa Receipt</th>
                <th className="font-semibold">Voucher</th>
                <th className="font-semibold">Status</th>
              </tr>
            </thead>
            <tbody className="text-sm text-on-surface divide-y divide-surface-variant">
              {filtered.map((p) => (
                <tr
                  key={p.id}
                  onClick={() => setSelected(p)}
                  className="hover:bg-surface-container-low transition-colors cursor-pointer"
                >
                  <td className="whitespace-nowrap">
                    <div className="font-semibold text-on-surface">{fmtDate(p.createdAt)}</div>
                    <div className="text-on-surface-variant text-xs mt-0.5">{fmtTime(p.createdAt)}</div>
                  </td>
                  <td className="font-medium">{p.phoneNumber}</td>
                  <td className="">{p.plan?.name}</td>
                  <td className="font-semibold tabular-nums">{fmtKES(p.amount)}</td>
                  <td className="font-mono text-xs">{p.mpesaReceiptNumber || <span className="text-on-surface-variant/50">—</span>}</td>
                  <td className="font-mono text-xs">{p.voucher?.code || <span className="text-on-surface-variant/50">—</span>}</td>
                  <td className=""><StatusPill status={p.status} /></td>
                </tr>
              ))}
              {filtered.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={7}>No payments match.</td></tr>
              )}
            </tbody>
          </table>
        </div>

        <div className="p-4 border-t border-surface-variant flex items-center justify-between bg-surface-bright">
          <span className="text-sm text-on-surface-variant">Showing {filtered.length} of {payments.length} payments</span>
        </div>
      </div>

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

function Support({ auth }) {
  const [tickets, setTickets] = useState(null)
  const [filter, setFilter] = useState('All')
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState(null)
  const [reply, setReply] = useState('')
  const [sending, setSending] = useState(false)
  const [showTemplates, setShowTemplates] = useState(false)

  const load = () => api('/admin/tickets', { auth }).then(setTickets).catch(() => setTickets([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

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
          <h2 className="text-4xl font-bold tracking-tight text-on-surface mb-2">Support Tickets</h2>
          <p className="text-base text-on-surface-variant">Help customers with connection and payment issues.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {Object.keys(TICKET_FILTERS).map((f) => (
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
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6 items-start">
        {/* Ticket list */}
        <div className="lg:col-span-2 bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-outline-variant/30 overflow-hidden">
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
        <div className="lg:col-span-3 bg-surface-container-lowest rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] border border-outline-variant/30 overflow-hidden flex flex-col min-h-[400px]">
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
                  </div>
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
                        <div className="absolute bottom-full left-0 mb-2 w-64 bg-surface-container-lowest border border-outline-variant rounded-lg shadow-[0_8px_16px_rgba(15,23,42,0.08)] z-50 overflow-hidden">
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
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[48px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-surface-variant/50 flex justify-between items-center">
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
          <div className="p-6 border-t border-surface-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-6 py-3 rounded-lg text-lg font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[48px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-6 py-3 rounded-lg text-lg font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[48px] disabled:opacity-60 cursor-pointer">
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

  return (
    <div>
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center mb-6 gap-4">
        <div>
          <h2 className="text-4xl font-bold tracking-tight text-on-background">Maintenance Schedule</h2>
          <p className="text-base text-on-surface-variant mt-1">Plan and track network upgrades and downtime.</p>
        </div>
        <button
          onClick={() => setModal(true)}
          className="bg-primary hover:bg-surface-tint text-on-primary text-lg font-semibold px-4 py-3 rounded-lg flex items-center gap-2 transition-colors shadow-sm cursor-pointer h-12"
        >
          <Icon name="add" />
          Schedule New
        </button>
      </div>

      <div className="flex flex-col xl:flex-row gap-6 items-start">
        {/* Calendar */}
        <div className="flex-1 w-full bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-container-highest overflow-hidden">
          <div className="p-4 border-b border-surface-container-high flex justify-between items-center">
            <div className="flex items-center gap-2">
              <button onClick={() => shift(-1)} className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-surface-container transition-colors text-on-surface-variant cursor-pointer" aria-label="Previous month">
                <Icon name="chevron_left" />
              </button>
              <h3 className="text-lg font-semibold text-on-background w-44 text-center">{MONTH_NAMES[cursor.m]} {cursor.y}</h3>
              <button onClick={() => shift(1)} className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-surface-container transition-colors text-on-surface-variant cursor-pointer" aria-label="Next month">
                <Icon name="chevron_right" />
              </button>
            </div>
            <div className="hidden sm:flex items-center gap-4">
              {[['bg-primary', 'Planned'], ['bg-[#f59e0b]', 'Upcoming'], ['bg-secondary', 'Completed']].map(([dot, label]) => (
                <div key={label} className="flex items-center gap-1.5">
                  <span className={`w-3 h-3 rounded-full ${dot}`}></span>
                  <span className="text-xs font-semibold tracking-wider text-on-surface-variant">{label}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-7 gap-[1px] bg-surface-container-high">
            {DAY_NAMES.map((d) => (
              <div key={d} className="bg-surface p-2 text-center text-xs font-semibold tracking-wider text-on-surface-variant">{d}</div>
            ))}
            {Array.from({ length: cellCount }, (_, i) => {
              const day = i - firstDay + 1
              const inMonth = day >= 1 && day <= daysInMonth
              const dayEvents = inMonth ? byDay[`${cursor.y}-${cursor.m}-${day}`] || [] : []
              return (
                <div key={i} className={`bg-surface-container-lowest p-2 min-h-[90px] ${isToday(day) ? 'ring-2 ring-primary ring-inset' : ''}`}>
                  {inMonth && (
                    <>
                      <span className={`text-sm ${isToday(day) ? 'text-primary font-bold' : 'text-on-surface-variant'}`}>{day}</span>
                      {dayEvents.map((ev) => (
                        <button
                          key={ev.id}
                          onClick={() => setSelectedId(ev.id)}
                          className={`mt-1 w-full text-left border rounded p-1 cursor-pointer transition-colors ${eventTone(ev)} ${selectedId === ev.id ? 'ring-1 ring-primary' : ''}`}
                        >
                          <p className="text-[10px] font-semibold tracking-wider leading-tight truncate">{ev.title}</p>
                        </button>
                      ))}
                    </>
                  )}
                </div>
              )
            })}
          </div>
        </div>

        {/* Detail panel */}
        <aside className="w-full xl:w-96 shrink-0">
          <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-container-high">
            {!selected ? (
              <div className="p-8 flex flex-col items-center text-center gap-3">
                <Icon name="calendar_month" className="text-[48px]! text-outline" />
                <p className="text-on-surface-variant text-sm">Select an event on the calendar to see its details, or schedule new maintenance.</p>
              </div>
            ) : (
              <>
                <div className="p-6 border-b border-surface-container-high">
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
                  <div className="pt-4 border-t border-surface-container-high flex flex-col gap-3">
                    {selected.status !== 'COMPLETED' && (
                      <button onClick={complete} className="w-full h-12 bg-primary hover:bg-surface-tint text-on-primary rounded-lg text-lg font-semibold shadow-[0_8px_16px_rgba(15,23,42,0.08)] transition-all cursor-pointer flex justify-center items-center gap-2">
                        <Icon name="check_circle" />
                        Mark Completed
                      </button>
                    )}
                    <button onClick={remove} className="w-full h-12 border border-error text-error hover:bg-error/5 rounded-lg text-lg font-semibold transition-colors cursor-pointer">
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
        <h2 className="text-4xl font-bold tracking-tight text-on-surface mb-2">Messages</h2>
        <p className="text-base text-on-surface-variant">Direct chat with your field technicians.</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 items-start">
        {/* Channel list */}
        <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-outline-variant/30 overflow-hidden">
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
        <div className="lg:col-span-2 bg-surface-container-lowest rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] border border-outline-variant/30 overflow-hidden flex flex-col h-[70vh]">
          {!selected ? (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 p-8 text-center">
              <Icon name="chat" className="text-[48px]! text-outline" />
              <p className="text-on-surface-variant">Select a technician to open the conversation.</p>
            </div>
          ) : (
            <>
              <div className="p-4 border-b border-outline-variant/30 flex items-center gap-3 bg-surface-bright">
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
    'w-full bg-surface border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all min-h-[48px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2'

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-surface-variant/50 flex justify-between items-center">
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
                  <input type="checkbox" checked={form.canVouchers} onChange={(e) => setForm({ ...form, canVouchers: e.target.checked })} className="w-4 h-4 accent-[#005c55]" />
                  <span className="text-sm text-on-surface"><strong>Issue vouchers</strong> — generate and print WiFi passes in the field</span>
                </label>
                <label className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors">
                  <input type="checkbox" checked={form.canPppoe} onChange={(e) => setForm({ ...form, canPppoe: e.target.checked })} className="w-4 h-4 accent-[#005c55]" />
                  <span className="text-sm text-on-surface"><strong>Manage PPPoE subscribers</strong> — sign up monthly home customers and take payments</span>
                </label>
              </div>
            </div>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-surface-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-6 py-3 rounded-lg text-lg font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[48px] cursor-pointer">
              Cancel
            </button>
            <button type="submit" disabled={busy} className="px-6 py-3 rounded-lg text-lg font-semibold bg-primary text-on-primary hover:bg-surface-tint shadow-[0_4px_12px_rgba(15,23,42,0.08)] transition-all active:scale-95 min-h-[48px] disabled:opacity-60 cursor-pointer">
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
      <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4 mb-6">
        <div>
          <h2 className="text-4xl font-bold tracking-tight text-on-background">Team</h2>
          <p className="text-base text-on-surface-variant mt-1">Field technician accounts for the Field Connect app.</p>
        </div>
        <button
          onClick={() => setModal(true)}
          className="bg-primary text-on-primary text-lg font-semibold px-6 py-3 rounded-lg flex items-center gap-2 shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 whitespace-nowrap min-h-[48px] cursor-pointer"
        >
          <Icon name="person_add" />
          Add Technician
        </button>
      </div>

      {resetMsg && <p className={`text-sm mb-4 ${resetMsg.ok ? 'text-surface-tint' : 'text-error'}`}>{resetMsg.text}</p>}

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[700px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="border-b border-surface-variant/50">Technician</th>
                <th className="border-b border-surface-variant/50">Username</th>
                <th className="border-b border-surface-variant/50">Phone</th>
                <th className="border-b border-surface-variant/50">Since</th>
                <th className="border-b border-surface-variant/50">Permissions</th>
                <th className="border-b border-surface-variant/50">Status</th>
                <th className="border-b border-surface-variant/50 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm">
              {techs.map((t) => (
                <tr key={t.id} className="border-b border-surface-variant/30 hover:bg-surface-container-low/20 transition-colors">
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
    'w-full bg-surface-bright border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all min-h-[48px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-outline mb-2'

  return (
    <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-[#f59e0b]">
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
    <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)]">
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
        className="w-full bg-surface-bright border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all resize-none"
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
    'w-full bg-surface-bright border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all min-h-[48px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-outline mb-2'

  return (
    <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-secondary">
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
          Example: {previewMinutes} minutes would cost <strong className="text-primary">KES {previewPrice}</strong> at this rate.
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
    'w-full bg-surface-bright border border-outline-variant rounded-lg px-4 py-3 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all min-h-[48px]'
  const labelCls = 'block text-xs font-semibold tracking-wider uppercase text-outline mb-2'

  return (
    <div className="max-w-4xl">
      <div className="mb-6">
        <h2 className="text-4xl font-bold tracking-tight text-on-surface mb-2">MikroTik Integration</h2>
        <p className="text-base text-on-surface-variant">Configure your router API credentials and network parameters.</p>
      </div>

      <div className="space-y-6 pb-24">
        {/* Master toggle */}
        <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] flex items-center justify-between gap-4">
          <div>
            <p className="text-lg font-semibold text-on-surface">Enable MikroTik integration</p>
            <p className="text-sm text-on-surface-variant">When off, vouchers are issued without provisioning hotspot users on the router.</p>
          </div>
          <Toggle checked={form.enabled} onChange={() => set('enabled', !form.enabled)} />
        </section>

        {/* API credentials */}
        <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-primary">
          <div className="flex items-center gap-3 mb-4">
            <Icon name="router" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
            <h3 className="text-lg font-semibold text-on-surface">MikroTik API Credentials</h3>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={labelCls} htmlFor="mk-host">IP Address / Host</label>
              <input id="mk-host" className={inputCls} placeholder="192.168.88.1" type="text" value={form.host} onChange={(e) => set('host', e.target.value)} />
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-port">API Port</label>
              <input id="mk-port" className={inputCls} placeholder="8728" type="number" value={form.port} onChange={(e) => set('port', e.target.value)} />
              <p className="text-sm text-outline mt-1">Default is 8728 (or 8729 for SSL)</p>
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-user">Username</label>
              <input id="mk-user" className={inputCls} placeholder="admin" type="text" value={form.username} onChange={(e) => set('username', e.target.value)} />
            </div>
            <div>
              <label className={labelCls} htmlFor="mk-pass">Password</label>
              <div className="relative">
                <input id="mk-pass" className={`${inputCls} pr-12`} placeholder="••••••••" type={showPass ? 'text' : 'password'} value={form.password} onChange={(e) => set('password', e.target.value)} />
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
        <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)]">
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
        <section className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)]">
          <div className="flex items-center gap-3 mb-4">
            <Icon name="security" className="text-primary bg-primary/10 p-2 rounded-lg text-[40px]!" />
            <h3 className="text-lg font-semibold text-on-surface">API Security</h3>
          </div>
          <div className="flex flex-col md:flex-row gap-6 items-start md:items-center">
            <div className="flex items-center justify-between w-full md:w-auto gap-4 p-4 border border-outline-variant rounded-lg bg-surface-bright">
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
          <div className="mt-4 flex items-center justify-between gap-4 p-4 border border-outline-variant rounded-lg bg-surface-bright">
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
          className="px-8 py-3 bg-primary text-on-primary rounded-xl text-lg font-semibold shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 flex items-center gap-2 disabled:opacity-50 cursor-pointer"
        >
          <Icon name="save" className="text-[20px]!" />
          {saving ? 'Saving…' : 'Save Changes'}
        </button>
      </div>
    </div>
  )
}
