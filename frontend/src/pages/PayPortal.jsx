import { useEffect, useRef, useState } from 'react'
import { normalizePhone } from '../phone.js'
import { api } from '../api.js'
import loginFiber from '../assets/login-fiber.jpg'

import { Icon } from '../components/icons.jsx'
import { payPinPhrase } from '../payBrand.js'
import { money } from '../money.js'

function fmtDate(d) {
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}


/**
 * Public self-service page for monthly PPPoE subscribers: enter your
 * phone number, pick your account, pay via M-Pesa STK, get reconnected.
 * Reachable at /pay — works on mobile data even when home internet is off.
 */
export default function PayPortal() {
  const [step, setStep] = useState('phone') // phone | accounts | waiting | done | error
  const [phone, setPhone] = useState('')
  const [accounts, setAccounts] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [months, setMonths] = useState(1)
  const [error, setError] = useState(null)
  const [paidUntil, setPaidUntil] = useState(null)
  const [busy, setBusy] = useState(false)
  const pollRef = useRef(null)

  useEffect(() => () => clearInterval(pollRef.current), [])

  const normalized = normalizePhone(phone)
  const selected = accounts.find((a) => a.id === selectedId)

  async function lookup(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const found = await api('/pppoe/lookup', { method: 'POST', body: { phoneNumber: normalized } })
      if (!found.length) {
        setError('No home internet account is registered under this number. Call support if you think this is wrong.')
      } else {
        setAccounts(found)
        setSelectedId(found[0].id)
        setStep('accounts')
      }
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  async function pay() {
    setBusy(true)
    setError(null)
    try {
      const { paymentId } = await api('/pppoe/pay', {
        method: 'POST',
        body: { subscriberId: selectedId, phoneNumber: normalized, months: Number(months) },
      })
      setStep('waiting')
      let tries = 0
      pollRef.current = setInterval(async () => {
        tries++
        try {
          const p = await api(`/pppoe/payments/${paymentId}`)
          if (p.status === 'SUCCESS') {
            clearInterval(pollRef.current)
            setPaidUntil(p.paidUntil)
            setStep('done')
          } else if (p.status === 'FAILED' || tries > 40) {
            clearInterval(pollRef.current)
            setError(p.status === 'FAILED' ? "The payment didn't go through. Please try again." : 'Timed out waiting for payment. If you were charged, call support.')
            setStep('error')
          }
        } catch { /* keep polling */ }
      }, 3000)
    } catch (err) {
      setError(err.message)
      setBusy(false)
    }
  }

  const total = selected ? selected.monthlyFee * Number(months || 0) : 0

  return (
    <div className="portal-theme relative bg-background text-on-background min-h-screen flex flex-col items-center justify-center px-5 py-10 overflow-hidden">
      <img src={loginFiber} alt="" className="absolute inset-0 w-full h-full object-cover" />
      <div className="absolute inset-0 bg-black/75"></div>

      <div className="relative z-10 w-full max-w-md">
        <div className="flex flex-col items-center mb-8">
          <div className="w-16 h-16 rounded-full bg-white/10 backdrop-blur border border-white/20 flex items-center justify-center mb-4">
            <Icon name="wifi" filled className="text-primary-fixed text-[32px]!" />
          </div>
          <h1 className="text-2xl font-bold text-white">SPA WiFi — Pay My Bill</h1>
          <p className="text-sm text-white/70">Home internet subscription payments</p>
        </div>

        <div className="bg-surface-container-lowest rounded-xl shadow-[0_8px_24px_rgba(0,0,0,0.3)] border-t-4 border-primary p-6">
          {step === 'phone' && (
            <form onSubmit={lookup} className="flex flex-col gap-4">
              <h2 className="text-lg font-semibold text-on-surface">Find your account</h2>
              <div>
                <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="pay-phone">
                  Registered Phone Number
                </label>
                <div className="relative flex items-center">
                  <span className="absolute left-4 text-lg font-semibold text-on-surface-variant">+254</span>
                  <input
                    id="pay-phone"
                    type="tel"
                    required
                    pattern="0?[17]\d{8}"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value.replace(/[^\d ]/g, ''))}
                    placeholder="712 345 678"
                    className="w-full bg-surface text-on-background text-lg font-semibold rounded-lg border border-outline-variant pl-[72px] pr-4 h-12 focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
                  />
                </div>
              </div>
              {error && <p className="text-sm text-error">{error}</p>}
              <button type="submit" disabled={busy} className="w-full h-12 bg-primary text-on-primary rounded-lg text-lg font-semibold shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-surface-tint active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer">
                {busy ? 'Searching…' : 'Find My Account'}
              </button>
            </form>
          )}

          {step === 'accounts' && (
            <div className="flex flex-col gap-4">
              <h2 className="text-lg font-semibold text-on-surface">Your account{accounts.length > 1 ? 's' : ''}</h2>
              {accounts.map((a) => {
                const overdue = new Date(a.paidUntil) < Date.now() || a.status === 'SUSPENDED'
                return (
                  <button
                    key={a.id}
                    onClick={() => setSelectedId(a.id)}
                    className={`text-left p-4 rounded-xl border-2 transition-all cursor-pointer ${
                      selectedId === a.id ? 'border-primary bg-primary/5' : 'border-outline-variant hover:border-primary/50'
                    }`}
                  >
                    <div className="flex justify-between items-start gap-2">
                      <div>
                        <p className="text-base font-semibold text-on-surface">{a.fullName}</p>
                        <p className="text-xs text-on-surface-variant font-mono mt-0.5">{a.pppoeUsername}</p>
                      </div>
                      <span className={`text-xs font-semibold px-2.5 py-1 rounded-full whitespace-nowrap ${
                        overdue ? 'bg-error-container text-on-error-container' : 'bg-secondary-container text-on-secondary-container'
                      }`}>
                        {overdue ? 'Payment due' : `Active until ${fmtDate(a.paidUntil)}`}
                      </span>
                    </div>
                  </button>
                )
              })}
              <div className="flex items-end gap-4">
                <div className="w-28">
                  <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="pay-months">Months</label>
                  <input
                    id="pay-months"
                    type="number"
                    min="1"
                    max="12"
                    value={months}
                    onChange={(e) => setMonths(e.target.value)}
                    className="w-full h-12 bg-surface border border-outline-variant rounded-lg px-4 text-lg font-semibold tabular-nums text-center focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
                  />
                </div>
                <div className="flex-1 text-right pb-1">
                  <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">You pay</p>
                  <p className="font-mono text-2xl font-bold text-primary tabular-nums">{money(total)}</p>
                </div>
              </div>
              {error && <p className="text-sm text-error">{error}</p>}
              <button onClick={pay} disabled={busy || !selected} className="w-full h-12 bg-gradient-to-r from-primary to-[#e0aa22] text-on-primary rounded-lg text-lg font-semibold flex items-center justify-center gap-2 shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-[0.98] transition-all disabled:opacity-60 cursor-pointer">
                <Icon name="send_money" /> Pay with M-Pesa
              </button>
              <button onClick={() => { setStep('phone'); setError(null) }} className="text-sm text-primary hover:underline cursor-pointer">
                Use a different number
              </button>
            </div>
          )}

          {step === 'waiting' && (
            <div className="flex flex-col items-center gap-4 py-6 text-center">
              <div className="relative w-24 h-24 flex items-center justify-center">
                <div className="absolute inset-0 rounded-full bg-primary/20 radar-ping"></div>
                <div className="relative z-10 w-14 h-14 bg-surface-container-lowest rounded-full shadow-lg flex items-center justify-center border-4 border-surface">
                  <Icon name="smartphone" filled className="text-primary text-[28px]!" />
                </div>
              </div>
              <h2 className="text-xl font-bold text-on-surface">Check your phone</h2>
              <p className="text-sm text-on-surface-variant">Enter {payPinPhrase()} to complete the payment of <span className="font-mono font-semibold text-on-surface">{money(total)}</span>.</p>
            </div>
          )}

          {step === 'done' && (
            <div className="flex flex-col items-center gap-4 py-6 text-center">
              <Icon name="check_circle" filled className="text-[64px]! text-primary" />
              <h2 className="text-xl font-bold text-primary">You're reconnected!</h2>
              <p className="text-sm text-on-surface-variant">
                Payment received. Your home internet is active until{' '}
                <strong className="text-on-surface">{paidUntil ? fmtDate(paidUntil) : ''}</strong>.
                If it doesn't come back within a minute, restart your router.
              </p>
            </div>
          )}

          {step === 'error' && (
            <div className="flex flex-col items-center gap-4 py-6 text-center">
              <Icon name="error" filled className="text-[48px]! text-error" />
              <h2 className="text-xl font-bold text-on-surface">Payment failed</h2>
              <p className="text-sm text-on-surface-variant">{error}</p>
              <button onClick={() => { setStep('accounts'); setError(null); setBusy(false) }} className="h-12 px-6 bg-primary text-on-primary rounded-lg text-base font-semibold cursor-pointer">
                Try Again
              </button>
            </div>
          )}
        </div>

        <p className="text-center text-xs text-white/60 mt-6">© {new Date().getFullYear()} SPA Limited · Need help? Call +254 700 000 000</p>
      </div>
    </div>
  )
}
