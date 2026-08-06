import { useEffect, useState } from 'react'
import { api } from '../api.js'
import loginFiber from '../assets/login-fiber.jpg'
import { Icon, fmtKES, fmtDate, fmtTime, relativeTime } from '../components/ui.jsx'

/**
 * Customer self-service portal for monthly subscribers at /my-account.
 * They sign in with the PPPoE username and password already set in their
 * router and see status, usage, invoices and payment history.
 */
export default function MyAccount() {
  const [creds, setCreds] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem('portalCreds')) } catch { return null }
  })
  const [data, setData] = useState(null)
  const [form, setForm] = useState({ pppoeUsername: '', pppoePassword: '' })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!creds) return
    api('/portal/account', { method: 'POST', body: creds })
      .then(setData)
      .catch(() => { sessionStorage.removeItem('portalCreds'); setCreds(null) })
  }, [creds])

  async function login(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const result = await api('/portal/account', { method: 'POST', body: form })
      sessionStorage.setItem('portalCreds', JSON.stringify(form))
      setCreds(form)
      setData(result)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  function logout() {
    sessionStorage.removeItem('portalCreds')
    setCreds(null)
    setData(null)
    setForm({ pppoeUsername: '', pppoePassword: '' })
  }

  if (!creds || !data) {
    return (
      <div className="relative bg-inverse-surface min-h-screen flex flex-col items-center justify-center px-5 py-10 overflow-hidden">
        <img src={loginFiber} alt="" className="absolute inset-0 w-full h-full object-cover" />
        <div className="absolute inset-0 bg-[#00201d]/75"></div>
        <div className="relative z-10 w-full max-w-sm">
          <div className="flex flex-col items-center mb-8">
            <div className="w-16 h-16 rounded-full bg-white/10 backdrop-blur border border-white/20 flex items-center justify-center mb-4">
              <Icon name="account_circle" filled className="text-primary-fixed text-[32px]!" />
            </div>
            <h1 className="text-2xl font-bold text-white">My SPA WiFi Account</h1>
            <p className="text-sm text-white/70">Home internet customers</p>
          </div>
          <form onSubmit={login} className="bg-surface-container-lowest rounded-xl shadow-[0_8px_24px_rgba(0,0,0,0.3)] border-t-4 border-primary p-6 flex flex-col gap-4">
            <h2 className="text-lg font-semibold text-on-surface">Sign in</h2>
            <p className="text-sm text-on-surface-variant -mt-2">Use the same username and password that are set in your router.</p>
            <div>
              <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="pu">Username</label>
              <input id="pu" required value={form.pppoeUsername} onChange={(e) => setForm({ ...form, pppoeUsername: e.target.value })}
                className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all" />
            </div>
            <div>
              <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="pp">Password</label>
              <input id="pp" type="password" required value={form.pppoePassword} onChange={(e) => setForm({ ...form, pppoePassword: e.target.value })}
                className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-base focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all" />
            </div>
            {error && <p className="text-sm text-error">{error}</p>}
            <button type="submit" disabled={busy}
              className="w-full h-12 bg-primary text-on-primary rounded-lg text-lg font-semibold shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-surface-tint active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer">
              {busy ? 'Signing in…' : 'Sign In'}
            </button>
            <a href="/pay" className="text-sm text-primary hover:underline text-center">Just want to pay? Use the quick pay page</a>
          </form>
        </div>
      </div>
    )
  }

  const a = data.account
  const days = Math.floor((new Date(a.paidUntil) - Date.now()) / 86400000)
  const overdue = days < 0 || a.status === 'SUSPENDED'

  return (
    <div className="bg-background text-on-background min-h-screen flex flex-col">
      <header className="bg-surface border-b border-outline-variant flex items-center justify-between px-5 h-16 sticky top-0 z-40">
        <div className="flex items-center gap-2">
          <Icon name="wifi" className="text-primary" />
          <span className="text-lg font-semibold text-primary tracking-tight uppercase">SPA WiFi</span>
        </div>
        <button onClick={logout} className="text-sm text-on-surface-variant hover:text-primary transition-colors cursor-pointer flex items-center gap-1.5">
          <Icon name="logout" className="text-[18px]!" /> Sign out
        </button>
      </header>

      <main className="flex-1 w-full max-w-3xl mx-auto px-5 py-6 flex flex-col gap-6">
        <section className={`bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 ${overdue ? 'border-error' : 'border-secondary'}`}>
          <div className="flex justify-between items-start gap-4 flex-wrap">
            <div>
              <h1 className="text-2xl font-bold text-on-surface">{a.fullName}</h1>
              <p className="text-sm text-on-surface-variant font-mono mt-0.5">{a.pppoeUsername}</p>
            </div>
            <span className={`text-xs font-semibold px-3 py-1.5 rounded-full ${overdue ? 'bg-error-container text-on-error-container' : 'bg-secondary-container text-on-secondary-container'}`}>
              {a.status === 'SUSPENDED' ? 'Suspended' : overdue ? 'Payment due' : 'Active'}
            </span>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-6">
            <div>
              <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">Active until</p>
              <p className="text-lg font-bold text-on-surface">{fmtDate(a.paidUntil)}</p>
              <p className={`text-xs ${overdue ? 'text-error font-semibold' : 'text-on-surface-variant'}`}>
                {days < 0 ? `${-days} day${days === -1 ? '' : 's'} overdue` : `${days} day${days === 1 ? '' : 's'} left`}
              </p>
            </div>
            <div>
              <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">Monthly fee</p>
              <p className="text-lg font-bold text-on-surface tabular-nums">{fmtKES(a.monthlyFee)}</p>
            </div>
            <div>
              <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">Speed</p>
              <p className="text-lg font-bold text-on-surface">{a.bandwidth ? `${parseInt(a.bandwidth)} Mbps` : '—'}</p>
            </div>
            <div>
              <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">Last online</p>
              <p className="text-lg font-bold text-on-surface">{a.lastSeenOnlineAt ? relativeTime(a.lastSeenOnlineAt) : '—'}</p>
            </div>
          </div>
          <a href="/pay" className="mt-6 w-full h-12 bg-gradient-to-r from-secondary to-[#578200] text-on-secondary rounded-xl text-lg font-semibold flex items-center justify-center gap-2 shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 transition-all">
            <Icon name="payments" /> Pay with M-Pesa
          </a>
        </section>

        <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] overflow-hidden">
          <h2 className="text-lg font-semibold text-on-surface p-4 border-b border-surface-variant">Invoices</h2>
          <ul className="divide-y divide-surface-variant">
            {data.invoices.map((i) => (
              <li key={i.number} className="p-4 flex justify-between items-center gap-3">
                <div>
                  <p className="text-sm font-semibold text-on-surface font-mono">{i.number}</p>
                  <p className="text-xs text-on-surface-variant mt-0.5">Issued {i.issuedOn} · due {i.dueOn}</p>
                </div>
                <div className="text-right">
                  <p className="text-base font-semibold tabular-nums">{fmtKES(i.amount)}</p>
                  <span className={`text-xs font-semibold ${i.status === 'PAID' ? 'text-secondary' : i.status === 'CANCELLED' ? 'text-on-surface-variant' : 'text-[#b45309]'}`}>
                    {i.status === 'PAID' ? 'Paid' : i.status === 'CANCELLED' ? 'Cancelled' : 'Unpaid'}
                  </span>
                </div>
              </li>
            ))}
            {data.invoices.length === 0 && <li className="p-4 text-sm text-on-surface-variant">No invoices yet.</li>}
          </ul>
        </section>

        <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] overflow-hidden mb-6">
          <h2 className="text-lg font-semibold text-on-surface p-4 border-b border-surface-variant">Payment History</h2>
          <ul className="divide-y divide-surface-variant">
            {data.payments.map((p, i) => (
              <li key={i} className="p-4 flex justify-between items-center gap-3">
                <div>
                  <p className="text-sm font-semibold text-on-surface">{fmtKES(p.amount)} · {p.months} month{p.months > 1 ? 's' : ''}</p>
                  <p className="text-xs text-on-surface-variant mt-0.5">
                    {p.method === 'MPESA' ? 'M-Pesa' : 'Cash'}{p.receipt ? ` · ${p.receipt}` : ''} · {fmtDate(p.date)}, {fmtTime(p.date)}
                  </p>
                </div>
                <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${
                  p.status === 'SUCCESS' ? 'bg-secondary-container text-on-secondary-container'
                    : p.status === 'FAILED' ? 'bg-error-container text-on-error-container'
                    : 'bg-surface-container-high text-on-surface-variant'
                }`}>
                  {p.status.charAt(0) + p.status.slice(1).toLowerCase()}
                </span>
              </li>
            ))}
            {data.payments.length === 0 && <li className="p-4 text-sm text-on-surface-variant">No payments yet.</li>}
          </ul>
        </section>
      </main>
    </div>
  )
}
