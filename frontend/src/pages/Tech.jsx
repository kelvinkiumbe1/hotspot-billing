import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api.js'
import TaskNotes from '../components/TaskNotes.jsx'
import ChatThread from '../components/ChatThread.jsx'
import loginValley from '../assets/login-valley.jpg'


/* ------------------------------------------------------------------ */
/* Helpers (Field Connect — technician app)                            */
/* ------------------------------------------------------------------ */

import { Icon } from '../components/icons.jsx'
import { money } from '../money.js'

function fmtDate(d) {
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function fmtTime(d) {
  return new Date(d).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
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

function taskChip(task) {
  if (task.status === 'COMPLETED') return { label: 'Completed', cls: 'bg-secondary-container text-on-secondary-container' }
  const days = (new Date(task.scheduledStart) - Date.now()) / 86400000
  if (days < 0) return { label: 'Overdue', cls: 'bg-error-container text-on-error-container' }
  if (days <= 7) return { label: 'Upcoming', cls: 'bg-secondary-container text-on-secondary-container' }
  return { label: 'Planned', cls: 'bg-primary-container/20 text-primary' }
}

const VOUCHER_PILL = {
  UNUSED: 'bg-surface-container-high text-on-surface-variant',
  ACTIVE: 'bg-primary-fixed/40 text-primary',
  USED: 'bg-secondary-container text-on-secondary-container',
  EXPIRED: 'bg-error-container text-on-error-container',
}

function printVoucherCards(vouchers, planName) {
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
    .card { width: 85mm; height: 54mm; border: 1px dashed #6e7977; border-top: 3px solid #1a1c1c; border-radius: 4mm;
            padding: 5mm; box-sizing: border-box; display: flex; flex-direction: column; justify-content: space-between;
            page-break-inside: avoid; }
    .head { display: flex; justify-content: space-between; color: #1a1c1c; font-size: 12px; }
    .code-box { text-align: center; border: 1px solid #bdc9c6; border-radius: 2mm; padding: 3mm; }
    .code-box small { color: #6e7977; letter-spacing: 1px; font-size: 9px; }
    .code { font-family: 'Courier New', monospace; font-size: 22px; font-weight: bold; letter-spacing: 3px; }
    .foot { display: flex; justify-content: space-between; font-size: 10px; color: #3e4947; }
  </style></head><body><div class="grid">${cards}</div><script>window.onload = () => window.print()<\/script></body></html>`)
  w.document.close()
}

/* ------------------------------------------------------------------ */
/* Root: login gate                                                    */
/* ------------------------------------------------------------------ */

export default function Tech() {
  const [auth, setAuth] = useState(sessionStorage.getItem('techAuth'))
  return auth
    ? <TechShell auth={auth} onLogout={() => { sessionStorage.removeItem('techAuth'); setAuth(null) }} />
    : <TechLogin onLogin={(a) => { sessionStorage.setItem('techAuth', a); setAuth(a) }} />
}

function TechLogin({ onLogin }) {
  const [username, setUsername] = useState('technician')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    // Technicians authenticate with Basic for now — their accounts live in a
    // separate table that the token/passkey flow does not cover yet. Store the
    // full header so api() sends it verbatim, same as the office side.
    const candidate = 'Basic ' + btoa(`${username}:${password}`)
    try {
      await api('/tech/tasks', { auth: candidate })
      onLogin(candidate)
    } catch (err) {
      setError(err.status === 401 ? 'Wrong username or password' : err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="tech-theme relative text-on-background min-h-screen flex flex-col items-center justify-center px-5 py-10 overflow-hidden">
      {/* The photo shows through untinted; all branding and copy live on
          the card so nothing has to fight the image for legibility. */}
      <img src={loginValley} alt="" className="absolute inset-0 w-full h-full object-cover" />
      <div className="relative z-10 w-full max-w-sm">
        <form onSubmit={submit} className="bg-surface-container-lowest rounded-2xl shadow-[0_16px_40px_rgba(77,17,17,0.35)] border-t-4 border-primary p-7 flex flex-col gap-4">
          <div className="flex flex-col items-center mb-2">
            <div className="w-16 h-16 rounded-full bg-primary flex items-center justify-center shadow-[0_8px_16px_rgba(77,17,17,0.25)] mb-3">
              <Icon name="engineering" filled className="text-on-primary text-[32px]!" />
            </div>
            <h1 className="text-2xl font-bold text-on-surface">Field Connect</h1>
            <p className="text-sm text-on-surface-variant">SPA WiFi Technician App</p>
          </div>
          <h2 className="text-base font-semibold text-on-surface border-t border-outline-variant pt-4">Technician sign in</h2>
          <div>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="tech-user">Username</label>
            <input
              id="tech-user"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              autoComplete="username"
              className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
          </div>
          <div>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="tech-pass">Password</label>
            <input
              id="tech-pass"
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
        </form>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Shell: light drawer (desktop) + bottom nav (mobile)                 */
/* ------------------------------------------------------------------ */

function TechShell({ auth, onLogout }) {
  // Same reasoning as the admin: the open section belongs in the URL.
  const navigate = useNavigate()
  const location = useLocation()
  const tab = location.pathname.replace(/^\/tech\/?/, '').split('/')[0] || 'tasks'
  const setTab = (key) => navigate(key === 'tasks' ? '/tech' : `/tech/${key}`)
  const [unread, setUnread] = useState(0)
  const [perms, setPerms] = useState({ vouchersAllowed: true, pppoeAllowed: false })

  useEffect(() => {
    api('/tech/me', { auth }).then(setPerms).catch(() => {})
  }, [auth])

  useEffect(() => {
    const load = () => api('/tech/messages/unread', { auth }).then((r) => setUnread(r.unread)).catch(() => {})
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [auth, tab])

  const TECH_NAV = [
    { key: 'tasks', label: 'My Tasks', icon: 'task_alt' },
    { key: 'jobs', label: 'My Jobs', icon: 'engineering' },
    ...(perms.vouchersAllowed ? [{ key: 'vouchers', label: 'Vouchers', icon: 'confirmation_number' }] : []),
    ...(perms.pppoeAllowed ? [{ key: 'subscribers', label: 'Subscribers', icon: 'lan' }] : []),
    { key: 'messages', label: 'Messages', icon: 'chat' },
    { key: 'profile', label: 'Profile', icon: 'person' },
  ]

  return (
    <div className="tech-theme bg-background text-on-background min-h-screen">
      {/* Desktop drawer */}
      <nav className="tech-nav hidden md:flex h-screen w-72 bg-surface-container-lowest shadow-xl fixed inset-y-0 left-0 z-40 flex-col p-4">
        <div className="flex items-center gap-4 mb-8 p-2">
          <div className="w-12 h-12 rounded-full bg-secondary-container flex items-center justify-center shadow-sm">
            <Icon name="engineering" filled className="text-on-secondary-container" />
          </div>
          <div className="flex flex-col">
            <span className="text-lg font-semibold text-primary">Field Connect</span>
            <span className="text-sm text-on-surface-variant">SPA WiFi Technician</span>
            <span className="text-xs font-semibold tracking-wider text-secondary flex items-center gap-1 mt-1">
              <span className="w-2 h-2 rounded-full bg-secondary inline-block"></span> Online
            </span>
          </div>
        </div>
        <ul className="flex flex-col gap-2">
          {TECH_NAV.map((item) => (
            <li key={item.key}>
              <button
                onClick={() => setTab(item.key)}
                className={`w-full flex items-center gap-3 px-4 py-3 rounded-lg transition-all cursor-pointer ${
                  tab === item.key
                    ? 'bg-secondary-container text-on-secondary-container font-bold shadow-[0_4px_12px_rgba(15,23,42,0.05)]'
                    : 'text-on-surface-variant hover:bg-surface-container-high'
                }`}
              >
                <Icon name={item.icon} filled={tab === item.key} />
                <span className="text-base">{item.label}</span>
                {item.key === 'messages' && unread > 0 && (
                  <span className="ml-auto min-w-[20px] h-5 px-1.5 bg-error text-on-error text-xs font-bold rounded-full flex items-center justify-center">
                    {unread}
                  </span>
                )}
              </button>
            </li>
          ))}
        </ul>
        <div className="mt-auto">
          <button onClick={onLogout} className="w-full flex items-center gap-3 px-4 py-3 rounded-lg text-on-surface-variant hover:bg-surface-container-high transition-all cursor-pointer">
            <Icon name="logout" />
            <span className="text-base">Logout</span>
          </button>
        </div>
      </nav>

      {/* Mobile top bar */}
      <header className="tech-nav fixed top-0 w-full bg-surface shadow-sm z-40 md:hidden flex items-center justify-between px-5 h-14">
        <h1 className="text-xl font-bold text-primary tracking-tight">Field Connect</h1>
        <button onClick={onLogout} aria-label="Logout" className="p-2 text-on-surface-variant cursor-pointer">
          <Icon name="logout" />
        </button>
      </header>

      {/* The cap only bites past 2560px. Below that the content fills the
          screen, so the office opening this on a monitor does not get a
          narrow column with a wide empty band beside it. Individual screens
          set their own narrower cap where reading width matters. */}
      <main className="md:ml-72 pt-20 md:pt-8 px-5 md:px-8 pb-28 md:pb-8 max-w-[2400px]">
        {tab === 'tasks' && <Tasks auth={auth} />}
        {tab === 'jobs' && <Jobs auth={auth} />}
        {tab === 'vouchers' && perms.vouchersAllowed && <FieldVouchers auth={auth} />}
        {tab === 'subscribers' && perms.pppoeAllowed && <TechSubscribers auth={auth} />}
        {tab === 'messages' && <TechMessages auth={auth} onRead={() => setUnread(0)} />}
        {tab === 'profile' && <Profile auth={auth} onLogout={onLogout} />}
      </main>

      {/* Mobile bottom nav */}
      <nav className="tech-nav fixed bottom-0 left-0 w-full z-40 flex justify-around items-center h-16 px-4 bg-surface shadow-[0_-4px_12px_rgba(77,17,17,0.15)] rounded-t-xl md:hidden">
        {TECH_NAV.map((item) => (
          <button
            key={item.key}
            onClick={() => setTab(item.key)}
            className={`flex flex-col items-center justify-center px-4 py-1 rounded-xl active:scale-95 transition-transform duration-150 cursor-pointer ${
              tab === item.key ? 'bg-primary-container text-on-primary-container' : 'text-on-surface-variant'
            }`}
          >
            <span className="relative">
              <Icon name={item.icon} filled={tab === item.key} />
              {item.key === 'messages' && unread > 0 && (
                <span className="absolute -top-1 -right-2 min-w-[16px] h-4 px-1 bg-error text-on-error text-[10px] font-bold rounded-full flex items-center justify-center">
                  {unread}
                </span>
              )}
            </span>
            <span className="text-xs font-semibold tracking-wider mt-1">{item.label === 'My Tasks' ? 'Tasks' : item.label}</span>
          </button>
        ))}
      </nav>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Tasks (maintenance events)                                          */
/* ------------------------------------------------------------------ */

/** How long a job has been running, in words a person would use. */
function elapsed(minutes) {
  if (minutes == null) return null
  if (minutes < 60) return `${minutes}m`
  if (minutes < 1440) {
    const h = Math.floor(minutes / 60)
    const m = minutes % 60
    return m ? `${h}h ${m}m` : `${h}h`
  }
  const d = Math.floor(minutes / 1440)
  const h = Math.floor((minutes % 1440) / 60)
  return h ? `${d}d ${h}h` : `${d}d`
}

const JOB_CHIP = {
  OPEN: 'bg-primary/10 text-primary',
  IN_PROGRESS: 'bg-primary/10 text-primary',
  RESOLVED: 'bg-secondary/10 text-secondary',
}

/**
 * Jobs the office has put this technician on. Closing one here is what
 * tells the office the work is finished — until then it shows as still
 * running, with the clock visible on both sides.
 */
function Jobs({ auth }) {
  const [jobs, setJobs] = useState(null)
  const [closing, setClosing] = useState(null)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/tech/tickets', { auth }).then(setJobs).catch(() => setJobs([]))
  useEffect(() => {
    load()
    const t = setInterval(load, 60000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function close(job) {
    setBusy(true)
    setMsg(null)
    try {
      await api(`/tech/tickets/${job.id}/status`, {
        method: 'PATCH', auth, body: { status: 'RESOLVED', note: note.trim() || null },
      })
      setClosing(null)
      setNote('')
      setMsg({ ok: true, text: 'Job closed. The office can see it is done.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  if (jobs === null) return <div className="h-40 rounded bg-surface-container animate-pulse" />

  const open = jobs.filter((j) => j.status !== 'RESOLVED')
  const done = jobs.filter((j) => j.status === 'RESOLVED')

  return (
    <div>
      <header className="border-b border-outline-variant pb-4 mb-4">
        <h2 className="text-2xl font-semibold">My Jobs</h2>
        <p className="text-[13px] text-on-surface-variant mt-1">
          Work the office has put you on. Close a job when it is finished.
        </p>
      </header>

      {msg && (
        <p className={`mb-4 text-[13px] ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>
      )}

      {open.length === 0 && done.length === 0 ? (
        <div className="border border-outline-variant rounded p-8 text-center">
          <Icon name="assignment_turned_in" className="text-[40px]! text-on-surface-variant/40" />
          <p className="mt-2 text-[13px] text-on-surface-variant">
            Nothing assigned to you. The office will text you when a job comes in.
          </p>
        </div>
      ) : (
        <>
          {open.length > 0 && (
            <p className="text-[11px] font-bold tracking-[0.05em] uppercase text-on-surface-variant mb-2">
              Working on ({open.length})
            </p>
          )}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
            {open.map((job) => (
              <article key={job.id}
                className="bg-surface-container border border-outline-variant rounded p-4 flex flex-col gap-3">
                <div className="flex justify-between items-start gap-3">
                  <div className="min-w-0">
                    <h3 className="text-[14px] font-semibold truncate">{job.subject}</h3>
                    <p className="text-[12px] text-on-surface-variant mt-0.5">
                      {job.customerName} · {job.phoneNumber}
                    </p>
                  </div>
                  <span className={`chip ${JOB_CHIP[job.status]}`}>
                    {job.status === 'OPEN' ? 'Assigned' : 'In progress'}
                  </span>
                </div>

                {job.messages?.[0] && (
                  <p className="text-[13px] text-on-surface-variant border-t border-outline-variant pt-3 line-clamp-3">
                    {job.messages[0].body}
                  </p>
                )}

                <div className="flex items-center justify-between gap-3 mt-auto pt-1">
                  <span className="text-[12px] text-on-surface-variant flex items-center gap-1.5 tabular-nums">
                    <Icon name="schedule" className="text-[14px]!" />
                    {job.workingMinutes != null
                      ? `running ${elapsed(job.workingMinutes)}`
                      : 'just assigned'}
                  </span>
                  <a href={`tel:${job.phoneNumber}`}
                    className="text-[12px] text-primary flex items-center gap-1 hover:underline">
                    <Icon name="call" className="text-[14px]!" /> Call
                  </a>
                </div>

                {closing === job.id ? (
                  <div className="border-t border-outline-variant pt-3 flex flex-col gap-2">
                    <textarea
                      className="w-full bg-surface-container-lowest border border-outline-variant rounded p-2.5 text-[13px] resize-none h-20 focus:outline-none focus:border-primary"
                      placeholder="What did you do? The office sees this on the ticket."
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                    />
                    <div className="flex gap-2">
                      <button onClick={() => close(job)} disabled={busy}
                        className="flex-1 bg-primary text-on-primary text-[14px] font-semibold rounded py-2.5 disabled:opacity-50 cursor-pointer active:scale-[0.98] transition-transform">
                        {busy ? 'Closing…' : 'Close this job'}
                      </button>
                      <button onClick={() => { setClosing(null); setNote('') }}
                        className="px-4 border border-outline-variant rounded text-[13px] cursor-pointer">
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <button onClick={() => setClosing(job.id)}
                    className="w-full border border-primary text-primary text-[14px] font-semibold rounded py-2.5 cursor-pointer hover:bg-primary/5 transition-colors active:scale-[0.98]">
                    Mark as done
                  </button>
                )}
              </article>
            ))}
          </div>

          {done.length > 0 && (
            <>
              <p className="text-[11px] font-bold tracking-[0.05em] uppercase text-on-surface-variant mb-2">
                Finished ({done.length})
              </p>
              <div className="border border-outline-variant rounded divide-y divide-[color:var(--color-outline-variant)]">
                {done.map((job) => (
                  <div key={job.id} className="p-3 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-[13px] truncate">{job.subject}</p>
                      <p className="text-[12px] text-on-surface-variant">{job.customerName}</p>
                    </div>
                    <div className="text-right shrink-0">
                      <span className="chip bg-secondary/10 text-secondary">Done</span>
                      {job.workingMinutes != null && (
                        <p className="text-[11px] text-on-surface-variant mt-1 tabular-nums">
                          took {elapsed(job.workingMinutes)}
                        </p>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </>
      )}
    </div>
  )
}

function Tasks({ auth }) {
  const [tasks, setTasks] = useState(null)
  const [selectedId, setSelectedId] = useState(null)
  const [ping, setPing] = useState(null)
  const [pinging, setPinging] = useState(false)

  const load = () => api('/tech/tasks', { auth }).then(setTasks).catch(() => setTasks([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const selected = (tasks || []).find((t) => t.id === selectedId)

  async function resolve() {
    await api(`/tech/tasks/${selected.id}/complete`, { method: 'PATCH', auth }).catch(() => {})
    load()
  }

  async function checkConnection() {
    setPinging(true)
    setPing(null)
    try {
      const r = await api('/tech/ping', { method: 'POST', auth })
      setPing({ ok: true, message: r.message })
    } catch (err) {
      setPing({ ok: false, message: err.message })
    } finally {
      setPinging(false)
    }
  }

  if (tasks === null) {
    return <div className="animate-pulse bg-surface-container-high rounded-xl h-64"></div>
  }

  /* Detail view */
  if (selected) {
    const chip = taskChip(selected)
    const done = selected.status === 'COMPLETED'
    return (
      <div>
        <div className="flex items-center justify-between mb-6 flex-wrap gap-3">
          <button onClick={() => { setSelectedId(null); setPing(null) }} className="flex items-center gap-2 text-on-surface-variant hover:text-primary transition-colors text-sm cursor-pointer">
            <Icon name="arrow_back" className="text-[18px]!" />
            My Tasks
            <Icon name="chevron_right" className="text-[16px]!" />
            <span className="text-on-surface font-medium">{selected.title}</span>
          </button>
          <span className="text-xs font-semibold tracking-wider text-on-surface-variant bg-surface-container px-3 py-1.5 rounded-full">
            ID: TK-{selected.id}
          </span>
        </div>

        <div className="flex flex-col gap-6 max-w-3xl">
          <section className={`bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-6 border-t-4 ${done ? 'border-secondary' : 'border-error'}`}>
            <div className="flex justify-between items-start mb-6 gap-4 flex-wrap">
              <div>
                <h2 className="text-2xl md:text-3xl font-bold text-on-surface mb-2">{selected.title}</h2>
                <p className="text-base text-on-surface-variant">Scheduled maintenance task</p>
              </div>
              <span className={`text-xs font-semibold tracking-wider px-3 py-1.5 rounded-full ${chip.cls}`}>{chip.label}</span>
            </div>

            <div className="grid grid-cols-2 gap-4 mb-6">
              <div className="bg-surface-container-low rounded-lg p-4 flex flex-col gap-1">
                <span className="flex items-center gap-2 text-xs font-semibold tracking-wider uppercase text-on-surface-variant">
                  <Icon name="event" className="text-[16px]!" /> Scheduled
                </span>
                <span className="text-lg font-bold text-on-surface">{fmtDate(selected.scheduledStart)}</span>
                <span className="text-sm text-on-surface-variant">{fmtTime(selected.scheduledStart)} – {fmtTime(selected.scheduledEnd)}</span>
              </div>
              <div className="bg-surface-container-low rounded-lg p-4 flex flex-col gap-1">
                <span className="flex items-center gap-2 text-xs font-semibold tracking-wider uppercase text-on-surface-variant">
                  <Icon name="timer" className="text-[16px]!" /> Downtime
                </span>
                <span className={`text-[28px] font-bold ${done ? 'text-on-surface' : 'text-error'}`}>
                  {selected.estimatedDowntimeMinutes != null ? `${selected.estimatedDowntimeMinutes}m` : '—'}
                </span>
              </div>
            </div>

            {selected.description && (
              <div>
                <h3 className="text-lg font-semibold text-on-surface mb-3">Issue Description</h3>
                <p className="text-base text-on-surface-variant leading-relaxed bg-surface-container-low p-4 rounded-lg border border-surface-variant">
                  {selected.description}
                </p>
              </div>
            )}
          </section>

          <section className="bg-gradient-to-br from-surface to-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-6 flex flex-col sm:flex-row gap-4 border border-surface-variant">
            <button
              onClick={checkConnection}
              disabled={pinging}
              className="flex-1 bg-surface-container hover:bg-surface-container-high text-primary border border-outline-variant text-lg font-semibold h-12 rounded-lg flex items-center justify-center gap-2 transition-colors active:scale-[0.98] disabled:opacity-60 cursor-pointer"
            >
              <Icon name="network_check" /> {pinging ? 'Checking…' : 'Check Connection'}
            </button>
            {!done && (
              <button
                onClick={resolve}
                className="flex-1 bg-primary text-on-primary text-lg font-semibold h-12 rounded-lg flex items-center justify-center gap-2 shadow-[0_8px_16px_rgba(15,23,42,0.08)] active:scale-[0.98] transition-transform cursor-pointer"
              >
                <Icon name="check_circle" /> Mark Resolved
              </button>
            )}
          </section>

          {ping && (
            <div className={`flex items-center gap-2 text-sm font-semibold p-4 rounded-lg ${ping.ok ? 'bg-secondary-container/30 text-on-secondary-container' : 'bg-error-container text-on-error-container'}`}>
              <Icon name={ping.ok ? 'check_circle' : 'error'} className="text-[18px]!" />
              {ping.message}
            </div>
          )}

          <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-6 border border-surface-variant">
            <h3 className="text-lg font-semibold text-on-surface mb-4 flex items-center gap-2">
              <Icon name="forum" filled className="text-primary" />
              Site Notes &amp; Photos
            </h3>
            <TaskNotes auth={auth} taskId={selected.id} />
          </section>
        </div>
      </div>
    )
  }

  /* List view */
  const open = tasks.filter((t) => t.status !== 'COMPLETED')
  const completed = tasks.filter((t) => t.status === 'COMPLETED')

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-3xl md:text-4xl font-bold tracking-tight text-on-surface mb-2">My Tasks</h2>
        <p className="text-base text-on-surface-variant">Maintenance work assigned to the field team.</p>
      </div>

      {tasks.length === 0 && (
        <div className="bg-surface-container-lowest rounded-xl p-8 shadow-[0_4px_12px_rgba(15,23,42,0.05)] flex flex-col items-center text-center gap-3">
          <Icon name="task_alt" className="text-[48px]! text-outline" />
          <p className="text-on-surface-variant">No tasks yet — new maintenance scheduled by the admin will appear here.</p>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {[...open, ...completed].map((t) => {
          const chip = taskChip(t)
          return (
            <button
              key={t.id}
              onClick={() => setSelectedId(t.id)}
              className={`text-left bg-surface-container-lowest rounded-xl p-5 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-l-4 transition-all hover:-translate-y-0.5 hover:shadow-[0_8px_16px_rgba(15,23,42,0.08)] cursor-pointer ${
                t.status === 'COMPLETED' ? 'border-secondary opacity-75' : 'border-error'
              }`}
            >
              <div className="flex justify-between items-start mb-2 gap-2">
                <h3 className="text-lg font-semibold text-on-surface">{t.title}</h3>
                <span className={`text-xs font-semibold tracking-wider px-2 py-1 rounded-full whitespace-nowrap ${chip.cls}`}>{chip.label}</span>
              </div>
              <p className="text-sm text-on-surface-variant flex items-center gap-2">
                <Icon name="event" className="text-[16px]!" />
                {fmtDate(t.scheduledStart)}, {fmtTime(t.scheduledStart)}
                {t.estimatedDowntimeMinutes != null && <span>· ~{t.estimatedDowntimeMinutes}m downtime</span>}
              </p>
              {t.description && <p className="text-sm text-on-surface-variant mt-2 line-clamp-2">{t.description}</p>}
            </button>
          )
        })}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Field voucher generator                                             */
/* ------------------------------------------------------------------ */

function FieldVouchers({ auth }) {
  const [plans, setPlans] = useState([])
  const [planId, setPlanId] = useState('')
  const [customMin, setCustomMin] = useState(60)
  const [customRate, setCustomRate] = useState(null)
  const [qty, setQty] = useState(1)
  const [prefix, setPrefix] = useState('')
  const [codeLen, setCodeLen] = useState(8)
  const [generated, setGenerated] = useState(null) // last batch
  const [history, setHistory] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const loadHistory = () => api('/tech/vouchers', { auth }).then(setHistory).catch(() => {})
  useEffect(() => {
    api('/plans').then((ps) => { setPlans(ps); if (ps[0]) setPlanId(String(ps[0].id)) }).catch(() => {})
    api('/custom-plan').then(setCustomRate).catch(() => {})
    loadHistory()
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const isCustom = planId === 'custom'
  const plan = plans.find((p) => String(p.id) === String(planId))
  const unitPrice = isCustom
    ? (customRate ? Math.max(1, Math.ceil((customRate.pricePerHour * (Number(customMin) || 0)) / 60)) : 0)
    : (plan ? plan.price : 0)
  const total = unitPrice * qty
  const batchName = isCustom ? `Custom — ${formatDuration(Number(customMin) || 0)}` : plan?.name || ''

  async function generate() {
    setBusy(true)
    setError(null)
    try {
      const batch = await api('/tech/vouchers/generate', {
        method: 'POST',
        auth,
        body: {
          planId: isCustom ? null : Number(planId),
          customMinutes: isCustom ? Number(customMin) : null,
          count: qty,
          prefix: prefix.trim() || null,
          codeLength: Number(codeLen) || 8,
        },
      })
      setGenerated(batch)
      loadHistory()
      printVoucherCards(batch, batchName)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const recent = useMemo(
    () => [...history].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)).slice(0, 10),
    [history]
  )

  return (
    <div className="max-w-md md:max-w-2xl">
      <div className="mb-6">
        <h2 className="text-3xl md:text-4xl font-bold tracking-tight text-on-surface mb-2">Generate Vouchers</h2>
        <p className="text-base text-on-surface-variant">Issue connectivity passes instantly to customers on-site.</p>
      </div>

      <div className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant flex flex-col gap-4 mb-6">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-semibold tracking-wider uppercase text-outline" htmlFor="field-plan">Select Plan</label>
          <div className="relative">
            <select
              id="field-plan"
              value={planId}
              onChange={(e) => setPlanId(e.target.value)}
              className="w-full h-12 appearance-none bg-surface-container-low border border-surface-variant rounded-lg px-4 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            >
              {['Hourly', 'Daily', 'Weekly', 'Monthly'].map((group) => {
                const groupPlans = plans.filter((p) => {
                  const m = p.durationMinutes
                  const g = m < 1440 ? 'Hourly' : m < 7 * 1440 ? 'Daily' : m < 28 * 1440 ? 'Weekly' : 'Monthly'
                  return g === group
                })
                if (!groupPlans.length) return null
                return (
                  <optgroup key={group} label={group}>
                    {groupPlans.map((p) => (
                      <option key={p.id} value={p.id}>{p.name} — {money(p.price)}</option>
                    ))}
                  </optgroup>
                )
              })}
              <optgroup label="Custom">
                <option value="custom">Custom time…</option>
              </optgroup>
            </select>
            <Icon name="expand_more" className="absolute right-3 top-1/2 -translate-y-1/2 text-outline pointer-events-none" />
          </div>
        </div>

        {isCustom && (
          <div className="flex flex-col gap-1">
            <label className="text-xs font-semibold tracking-wider uppercase text-outline" htmlFor="f-custom-min">Minutes</label>
            <input
              id="f-custom-min"
              type="number"
              min="1"
              max="44640"
              value={customMin}
              onChange={(e) => setCustomMin(e.target.value)}
              className="w-full h-12 bg-surface-container-low border border-surface-variant rounded-lg px-4 text-base text-on-surface tabular-nums focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
            <p className="text-xs text-on-surface-variant">
              {customRate ? `Charged at ${money(customRate.pricePerHour)}/hour — collect ${money(unitPrice)} per voucher` : ''}
            </p>
          </div>
        )}

        <div className="flex flex-col gap-1">
          <label className="text-xs font-semibold tracking-wider uppercase text-outline">Quantity</label>
          <div className="flex items-center justify-between bg-surface-container-low border border-surface-variant rounded-lg h-12 px-2">
            <button onClick={() => setQty(Math.max(1, qty - 1))} className="w-10 h-10 flex items-center justify-center text-primary hover:bg-surface-variant rounded-md transition-colors active:scale-95 cursor-pointer" aria-label="Fewer">
              <Icon name="remove" />
            </button>
            <span className="text-lg font-semibold text-on-background w-12 text-center tabular-nums">{qty}</span>
            <button onClick={() => setQty(Math.min(20, qty + 1))} className="w-10 h-10 flex items-center justify-center text-primary hover:bg-surface-variant rounded-md transition-colors active:scale-95 cursor-pointer" aria-label="More">
              <Icon name="add" />
            </button>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-xs font-semibold tracking-wider uppercase text-outline" htmlFor="f-prefix">Code Prefix (optional)</label>
            <input
              id="f-prefix"
              type="text"
              maxLength={12}
              value={prefix}
              onChange={(e) => setPrefix(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))}
              placeholder="e.g. SPA"
              className="w-full h-12 bg-surface-container-low border border-surface-variant rounded-lg px-4 text-base font-mono uppercase text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-xs font-semibold tracking-wider uppercase text-outline" htmlFor="f-len">Code Length</label>
            <input
              id="f-len"
              type="number"
              min={Math.max(6, prefix.length + 4)}
              max="16"
              value={codeLen}
              onChange={(e) => setCodeLen(e.target.value)}
              className="w-full h-12 bg-surface-container-low border border-surface-variant rounded-lg px-4 text-base text-on-surface tabular-nums focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
          </div>
        </div>

        <div className="h-px bg-surface-variant my-1"></div>

        <div className="flex justify-between items-center px-1">
          <span className="text-base text-on-surface-variant">Total Value</span>
          <span className="font-mono text-lg font-semibold text-primary tabular-nums">{money(total)}</span>
        </div>

        {error && <p className="text-sm text-error">{error}</p>}

        <button
          onClick={generate}
          disabled={busy || !planId}
          className="w-full h-12 bg-primary text-on-primary rounded-lg text-lg font-semibold flex items-center justify-center gap-2 hover:bg-surface-tint transition-colors shadow-[0_8px_16px_rgba(15,23,42,0.08)] active:scale-95 disabled:opacity-60 cursor-pointer"
        >
          <Icon name="print" className="text-[20px]!" />
          {busy ? 'Generating…' : 'Generate & Print'}
        </button>
      </div>

      {generated && (
        <div className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant border-t-4 border-t-secondary relative overflow-hidden mb-6">
          <div className="absolute -right-4 -top-4 opacity-10">
            <Icon name="wifi" className="text-[100px]!" />
          </div>
          <div className="relative z-10 flex flex-col gap-3 text-center">
            <span className="text-xs font-semibold tracking-wider uppercase text-outline">
              Generated: {generated.length} × {plan?.name}
            </span>
            <div className="bg-surface-container-low p-3 rounded-lg font-mono text-[24px] tracking-widest font-bold text-on-background border border-surface-variant border-dashed">
              {generated[0]?.code}
            </div>
            <p className="text-sm text-on-surface-variant">
              {generated.length > 1 ? `+ ${generated.length - 1} more sent to the printer.` : 'Sent to the printer.'} Codes are also in the history below.
            </p>
          </div>
        </div>
      )}

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant overflow-hidden">
        <div className="p-4 border-b border-surface-variant flex justify-between items-center">
          <h3 className="text-lg font-semibold text-on-surface">Recent Vouchers</h3>
          <span className="px-3 py-1 rounded-full bg-surface-container text-on-surface-variant text-xs font-semibold tracking-wider">Last {recent.length}</span>
        </div>
        <ul className="divide-y divide-surface-container-high">
          {recent.map((v) => (
            <li key={v.id} className="p-4 flex items-center justify-between gap-3">
              <div className="min-w-0">
                <span className={`font-mono tracking-[2px] text-base ${v.status === 'EXPIRED' ? 'line-through text-on-surface-variant' : 'text-on-surface'}`}>{v.code}</span>
                <p className="text-xs text-on-surface-variant mt-0.5">
                  {v.customDurationMinutes != null ? `Custom · ${formatDuration(v.customDurationMinutes)}` : v.plan?.name} · {fmtDate(v.createdAt)}, {fmtTime(v.createdAt)}
                </p>
              </div>
              <span className={`text-xs font-semibold tracking-wider px-2.5 py-1 rounded-full ${VOUCHER_PILL[v.status] || VOUCHER_PILL.UNUSED}`}>
                {v.status?.charAt(0) + v.status?.slice(1).toLowerCase()}
              </span>
            </li>
          ))}
          {recent.length === 0 && <li className="p-4 text-sm text-on-surface-variant">No vouchers yet.</li>}
        </ul>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Subscribers: sign up monthly PPPoE customers in the field           */
/* ------------------------------------------------------------------ */

function TechSubscribers({ auth }) {
  const [subs, setSubs] = useState(null)
  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ fullName: '', phoneNumber: '', pppoeUsername: '', pppoePassword: '', mbps: '', monthlyFee: '', initialMonths: 1, initialMethod: 'CASH' })
  const [payingId, setPayingId] = useState(null)
  const [months, setMonths] = useState(1)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/tech/subscribers', { auth }).then(setSubs).catch(() => setSubs([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function create(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/tech/subscribers', {
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
      setForm({ fullName: '', phoneNumber: '', pppoeUsername: '', pppoePassword: '', mbps: '', monthlyFee: '', initialMonths: 1, initialMethod: 'CASH' })
      setShowForm(false)
      setMsg({ ok: true, text: 'Subscriber created — set the PPPoE username and password in their router.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function recordCash(id) {
    setMsg(null)
    try {
      await api(`/tech/subscribers/${id}/payments`, { method: 'POST', auth, body: { months: Number(months) } })
      setPayingId(null)
      setMsg({ ok: true, text: 'Payment recorded — subscription extended.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  const inputCls =
    'w-full h-12 bg-surface-container-low border border-surface-variant rounded-lg px-4 text-base text-on-surface focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all'
  const labelCls = 'text-xs font-semibold tracking-wider uppercase text-outline'

  if (subs === null) return <div className="animate-pulse bg-surface-container-high rounded-xl h-64"></div>

  return (
    // Wider than the other tech screens because this one is a list rather
    // than a form — the office reads it on a monitor.
    <div className="max-w-6xl">
      <div className="flex justify-between items-start gap-4 mb-6 flex-wrap">
        <div>
          <h2 className="text-3xl md:text-4xl font-bold tracking-tight text-on-surface mb-2">Subscribers</h2>
          <p className="text-base text-on-surface-variant">Sign up monthly home customers on-site.</p>
        </div>
        <button
          onClick={() => setShowForm(!showForm)}
          className="bg-primary text-on-primary text-base font-semibold px-4 py-3 rounded-lg flex items-center gap-2 shadow-[0_4px_12px_rgba(15,23,42,0.08)] hover:bg-surface-tint transition-all active:scale-95 cursor-pointer h-12"
        >
          <Icon name="person_add" /> New Subscriber
        </button>
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {showForm && (
        <form onSubmit={create} className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant flex flex-col gap-3 mb-6">
          <div className="grid grid-cols-2 gap-3">
            <div><label className={labelCls}>Full Name</label><input className={inputCls} required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} /></div>
            <div><label className={labelCls}>Phone</label><input className={inputCls} required placeholder="2547XXXXXXXX" value={form.phoneNumber} onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })} /></div>
            <div><label className={labelCls}>PPPoE Username</label><input className={inputCls} required value={form.pppoeUsername} onChange={(e) => setForm({ ...form, pppoeUsername: e.target.value })} /></div>
            <div><label className={labelCls}>PPPoE Password</label><input className={inputCls} required minLength={6} value={form.pppoePassword} onChange={(e) => setForm({ ...form, pppoePassword: e.target.value })} /></div>
            <div><label className={labelCls}>Speed (Mbps)</label><input className={inputCls} type="number" min="1" value={form.mbps} onChange={(e) => setForm({ ...form, mbps: e.target.value })} /></div>
            <div><label className={labelCls}>Monthly Fee (KES)</label><input className={inputCls} type="number" min="1" required value={form.monthlyFee} onChange={(e) => setForm({ ...form, monthlyFee: e.target.value })} /></div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div><label className={labelCls}>Months Paid</label><input className={inputCls} type="number" min="0" max="12" required value={form.initialMonths} onChange={(e) => setForm({ ...form, initialMonths: e.target.value })} /></div>
            <div>
              <label className={labelCls}>Payment Method</label>
              <select className={inputCls} value={form.initialMethod} onChange={(e) => setForm({ ...form, initialMethod: e.target.value })}>
                <option value="CASH">Cash received</option>
                <option value="MPESA">Send a payment request</option>
              </select>
            </div>
          </div>
          <button type="submit" disabled={busy} className="w-full h-12 bg-secondary text-on-secondary rounded-lg text-base font-semibold shadow-[0_4px_12px_rgba(15,23,42,0.08)] active:scale-95 transition-transform disabled:opacity-60 cursor-pointer">
            {busy ? 'Creating…' : 'Create Subscriber'}
          </button>
        </form>
      )}

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant overflow-hidden">
        <ul className="divide-y divide-surface-container-high">
          {subs.map((s) => {
            const days = Math.floor((new Date(s.paidUntil) - Date.now()) / 86400000)
            return (
              <li key={s.id} className="p-4">
                <div className="flex items-center justify-between gap-3 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-base font-semibold text-on-surface">{s.fullName}</p>
                    <p className="text-xs text-on-surface-variant mt-0.5">
                      <span className="font-mono">{s.pppoeUsername}</span> · {money(s.monthlyFee)}/mo
                      {s.bandwidth ? ` · ${parseInt(s.bandwidth)} Mbps` : ''}
                    </p>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    <span className={`text-xs font-semibold px-2.5 py-1 rounded-full whitespace-nowrap ${
                      s.status === 'SUSPENDED' ? 'bg-error-container text-on-error-container'
                        : days < 0 ? 'bg-error-container text-on-error-container'
                        : days <= 3 ? 'bg-secondary-container text-on-secondary-container'
                        : 'bg-secondary-container text-on-secondary-container'
                    }`}>
                      {s.status === 'SUSPENDED' ? 'Suspended' : days < 0 ? 'Overdue' : `${days}d left`}
                    </span>
                    <button
                      onClick={() => { setPayingId(payingId === s.id ? null : s.id); setMonths(1) }}
                      className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer"
                    >
                      Take Payment
                    </button>
                  </div>
                </div>
                {payingId === s.id && (
                  <div className="flex items-center gap-2 mt-3 justify-end flex-wrap">
                    <label className="text-xs text-on-surface-variant">Months:</label>
                    <input type="number" min="1" max="12" value={months} onChange={(e) => setMonths(e.target.value)}
                      className="h-9 w-16 bg-surface border border-outline-variant rounded-lg px-2 text-sm text-center tabular-nums focus:outline-none focus:border-primary" />
                    <span className="font-mono text-xs font-semibold text-primary tabular-nums">= {money((Number(s.monthlyFee) * (Number(months) || 0)))}</span>
                    <button onClick={() => recordCash(s.id)} className="h-9 px-3 rounded-lg bg-secondary text-on-secondary text-xs font-semibold cursor-pointer">
                      Record Cash
                    </button>
                  </div>
                )}
              </li>
            )
          })}
          {subs.length === 0 && <li className="p-4 text-sm text-on-surface-variant">No subscribers yet — sign up your first home customer.</li>}
        </ul>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Messages: direct chat with the admin                                */
/* ------------------------------------------------------------------ */

function TechMessages({ auth, onRead }) {
  const [thread, setThread] = useState(null)

  useEffect(() => {
    const load = () => api('/tech/messages', { auth }).then((m) => { setThread(m); onRead() }).catch(() => setThread([]))
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function send(text, file) {
    const form = new FormData()
    if (text) form.append('message', text)
    if (file) form.append('photo', file)
    await api('/tech/messages', { method: 'POST', auth, body: form })
    const fresh = await api('/tech/messages', { auth })
    setThread(fresh)
  }

  return (
    <div className="max-w-3xl">
      <div className="mb-6">
        <h2 className="text-3xl md:text-4xl font-bold tracking-tight text-on-surface mb-2">Messages</h2>
        <p className="text-base text-on-surface-variant">Direct line to the SPA WiFi office — text and photos.</p>
      </div>
      <div className="bg-surface-container-lowest rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] border border-outline-variant/30 overflow-hidden flex flex-col h-[65vh]">
        <div className="p-4 border-b border-outline-variant/30 flex items-center gap-3 bg-surface-bright">
          <span className="w-9 h-9 rounded-full bg-primary text-on-primary flex items-center justify-center text-sm font-bold">A</span>
          <div>
            <p className="text-base font-semibold text-on-surface">SPA WiFi Admin</p>
            <p className="text-xs text-on-surface-variant">Usually replies within the day</p>
          </div>
        </div>
        <ChatThread
          messages={thread}
          viewerIsAdmin={false}
          onSend={send}
          emptyHint="No messages yet — write to the office about anything: schedules, supplies, sick days."
        />
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Profile: real stats + payout requests                               */
/* ------------------------------------------------------------------ */

const PAYOUT_PILL = {
  REQUESTED: { label: 'Requested', cls: 'bg-secondary-container text-on-secondary-container' },
  PAID: { label: 'Paid', cls: 'bg-secondary-container text-on-secondary-container' },
  REJECTED: { label: 'Rejected', cls: 'bg-error-container text-on-error-container' },
}

function Profile({ auth, onLogout }) {
  const username = (() => { try { return atob(auth).split(':')[0] } catch { return 'technician' } })()
  const [tasks, setTasks] = useState([])
  const [payouts, setPayouts] = useState(null)
  const [requesting, setRequesting] = useState(false)
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const loadPayouts = () => api('/tech/payouts', { auth }).then(setPayouts).catch(() => setPayouts([]))
  useEffect(() => {
    api('/tech/tasks', { auth }).then(setTasks).catch(() => {})
    loadPayouts()
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const completed = tasks.filter((t) => t.status === 'COMPLETED').length
  const rate = tasks.length ? Math.round((completed / tasks.length) * 100) : null

  async function requestPayout(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/tech/payouts', { method: 'POST', auth, body: { amount: Number(amount), note: note || null } })
      setRequesting(false)
      setAmount('')
      setNote('')
      loadPayouts()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="max-w-3xl flex flex-col gap-6">
      {/* Hero */}
      <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-6 flex flex-col md:flex-row items-center gap-4 text-center md:text-left border-t-4 border-primary">
        <div className="h-24 w-24 rounded-full bg-secondary-container border-4 border-surface shadow-sm shrink-0 relative flex items-center justify-center">
          <span className="text-3xl font-bold text-on-secondary-container uppercase">{username.slice(0, 2)}</span>
          <div className="absolute bottom-1 right-1 w-4 h-4 bg-primary rounded-full border-2 border-surface"></div>
        </div>
        <div className="flex flex-col gap-1">
          <h2 className="text-2xl font-bold text-on-surface capitalize">{username}</h2>
          <div className="flex items-center justify-center md:justify-start gap-2 text-on-surface-variant text-base">
            <Icon name="badge" filled className="text-primary text-[20px]!" />
            Field Technician
          </div>
          <div className="mt-1 inline-flex items-center gap-1.5 px-3 py-1 bg-surface-container-low rounded-full w-max mx-auto md:mx-0">
            <span className="w-2 h-2 rounded-full bg-primary animate-pulse"></span>
            <span className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase">Online &amp; Available</span>
          </div>
        </div>
      </section>

      {/* Performance stats (computed from real tasks) */}
      <section className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-4 flex flex-col gap-3 col-span-2 md:col-span-1 relative overflow-hidden">
          <div className="absolute top-0 right-0 w-24 h-24 bg-primary-container opacity-10 rounded-bl-full -mr-4 -mt-4"></div>
          <div className="flex items-center gap-2 text-on-surface-variant">
            <Icon name="check_circle" filled className="text-primary" />
            <span className="text-xs font-semibold tracking-wider uppercase">Resolution Rate</span>
          </div>
          <div className="flex items-end gap-2">
            <span className="font-mono text-4xl font-bold tracking-tight text-on-surface">{rate != null ? `${rate}%` : '—'}</span>
            <span className="text-sm text-on-surface-variant pb-1">all tasks</span>
          </div>
          <div className="w-full h-2 bg-surface-container-high rounded-full mt-auto overflow-hidden">
            <div className="h-full bg-primary rounded-full" style={{ width: `${rate || 0}%` }}></div>
          </div>
        </div>
        <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-4 flex flex-col gap-3 justify-between">
          <div className="flex items-center gap-2 text-on-surface-variant">
            <Icon name="task" filled className="text-primary" />
            <span className="text-xs font-semibold tracking-wider uppercase">Tasks Done</span>
          </div>
          <span className="font-mono text-4xl font-bold tracking-tight text-on-surface">{completed}</span>
          <span className="text-sm text-on-surface-variant">All-time total</span>
        </div>
        <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-4 flex flex-col gap-3 justify-between">
          <div className="flex items-center gap-2 text-on-surface-variant">
            <Icon name="pending_actions" className="text-warning" />
            <span className="text-xs font-semibold tracking-wider uppercase">Open Tasks</span>
          </div>
          <span className="font-mono text-4xl font-bold tracking-tight text-on-surface">{tasks.length - completed}</span>
          <span className="text-sm text-on-surface-variant">Awaiting resolution</span>
        </div>
      </section>

      {/* Payouts */}
      <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] overflow-hidden">
        <div className="p-4 border-b border-surface-container-high flex items-center justify-between">
          <h3 className="text-lg font-semibold text-on-surface flex items-center gap-2">
            <Icon name="account_balance_wallet" filled className="text-primary" />
            Payouts
          </h3>
          <button
            onClick={() => { setRequesting(!requesting); setError(null) }}
            className="px-4 py-2 rounded-lg bg-primary text-on-primary text-sm font-semibold hover:bg-surface-tint transition-colors flex items-center gap-1.5 cursor-pointer"
          >
            <Icon name="add" className="text-[18px]!" /> Request Payout
          </button>
        </div>

        {requesting && (
          <form onSubmit={requestPayout} className="p-4 border-b border-surface-container-high bg-surface-container-low/50 flex flex-col sm:flex-row gap-3 items-start sm:items-end flex-wrap">
            <div className="flex-1 w-full sm:w-auto min-w-[140px]">
              <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="payout-amount">Amount (KES)</label>
              <input
                id="payout-amount"
                type="number"
                min="1"
                required
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="e.g. 12500"
                className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
              />
            </div>
            <div className="flex-[2] w-full sm:w-auto min-w-[180px]">
              <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="payout-note">Note (optional)</label>
              <input
                id="payout-note"
                type="text"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="e.g. Field work, week 32"
                className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
              />
            </div>
            <button type="submit" disabled={busy} className="h-12 px-6 rounded-lg bg-secondary text-on-secondary text-base font-semibold shadow-[0_4px_12px_rgba(15,23,42,0.08)] active:scale-95 transition-transform disabled:opacity-60 cursor-pointer whitespace-nowrap">
              {busy ? 'Sending…' : 'Submit'}
            </button>
            {error && <p className="text-sm text-error w-full">{error}</p>}
          </form>
        )}

        <div className="flex flex-col">
          {(payouts || []).map((p) => {
            const pill = PAYOUT_PILL[p.status] || PAYOUT_PILL.REQUESTED
            return (
              <div key={p.id} className="p-4 flex items-center justify-between border-b border-surface-container-high hover:bg-surface-container-low transition-colors gap-3">
                <div className="flex flex-col gap-0.5 min-w-0">
                  <span className="text-base font-semibold text-on-surface">Payout request</span>
                  <span className="text-sm text-on-surface-variant truncate">
                    {fmtDate(p.createdAt)}{p.note ? ` · ${p.note}` : ''}
                  </span>
                </div>
                <div className="flex flex-col items-end gap-1 shrink-0">
                  <span className="font-mono text-base font-semibold text-primary tabular-nums">{money(p.amount)}</span>
                  <span className={`text-xs font-semibold tracking-wider px-2 py-0.5 rounded-full ${pill.cls}`}>{pill.label}</span>
                </div>
              </div>
            )
          })}
          {payouts !== null && payouts.length === 0 && (
            <p className="p-4 text-sm text-on-surface-variant">No payout requests yet — tap "Request Payout" to submit one. The admin is notified instantly.</p>
          )}
        </div>
      </section>

      {/* Account actions */}
      <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] flex flex-col overflow-hidden mb-6">
        <button onClick={onLogout} className="p-4 flex items-center justify-between hover:bg-error-container transition-colors group text-error cursor-pointer">
          <div className="flex items-center gap-4">
            <div className="w-10 h-10 rounded-full bg-surface-container-highest flex items-center justify-center text-error group-hover:bg-error group-hover:text-on-error transition-colors">
              <Icon name="logout" />
            </div>
            <span className="text-lg font-semibold">Log Out</span>
          </div>
        </button>
      </section>
    </div>
  )
}
