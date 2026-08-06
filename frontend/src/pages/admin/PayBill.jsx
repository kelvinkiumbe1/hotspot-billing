import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, StatCard, fmtKES, fmtDate, fmtTime, relativeTime } from '../../components/ui.jsx'

const STATUS_STYLES = {
  MATCHED: { label: 'Auto-matched', cls: 'bg-secondary-container text-on-secondary-container' },
  APPLIED_MANUALLY: { label: 'Applied by hand', cls: 'bg-primary-container/30 text-primary' },
  UNMATCHED: { label: 'Needs attention', cls: 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20' },
}

export default function PayBill({ auth }) {
  const [rows, setRows] = useState(null)
  const [subs, setSubs] = useState([])
  const [applyFor, setApplyFor] = useState(null)
  const [subscriberId, setSubscriberId] = useState('')
  const [months, setMonths] = useState(1)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/c2b', { auth }).then(setRows).catch(() => setRows([]))
  useEffect(() => {
    load()
    api('/admin/subscribers', { auth }).then((s) => { setSubs(s); if (s[0]) setSubscriberId(String(s[0].id)) }).catch(() => {})
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function apply(id) {
    setMsg(null)
    try {
      await api(`/admin/c2b/${id}/apply`, { method: 'POST', auth, body: { subscriberId: Number(subscriberId), months: Number(months) } })
      setApplyFor(null)
      setMsg({ ok: true, text: 'Payment applied and the subscription extended.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (rows === null) return <Skeleton className="h-64" />

  const unmatched = rows.filter((r) => r.status === 'UNMATCHED')
  const total = rows.reduce((a, r) => a + Number(r.amount), 0)

  return (
    <div>
      <PageHeader title="PayBill Payments" subtitle="Money deposited straight to your PayBill, matched by account number." />

      <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-6">
        <StatCard label="Payments Received" value={rows.length} accent="border-t-primary" />
        <StatCard label="Total Value" value={fmtKES(total)} />
        <StatCard label="Needs Attention" value={unmatched.length} accent={unmatched.length ? 'border-t-[#f59e0b]' : ''} />
      </div>

      <div className="bg-surface-container-lowest rounded-xl p-4 mb-6 border border-outline-variant/30 flex items-start gap-3">
        <Icon name="info" className="text-primary text-[20px]! mt-0.5" />
        <p className="text-sm text-on-surface-variant">
          Tell customers to pay to your PayBill using their <strong className="text-on-surface">PPPoE username as the account number</strong> — the
          system credits the right subscription automatically. Anything it can't match lands here for you to apply by hand.
          Register your callback URLs with Daraja: <code className="text-xs">/api/payments/mpesa/c2b/validation</code> and <code className="text-xs">/api/payments/mpesa/c2b/confirmation</code>.
        </p>
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse min-w-[900px]">
            <thead>
              <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                <th className="p-4">Receipt</th>
                <th className="p-4">Payer</th>
                <th className="p-4">Account Ref</th>
                <th className="p-4">Amount</th>
                <th className="p-4">When</th>
                <th className="p-4">Status</th>
                <th className="p-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-sm divide-y divide-surface-variant/30">
              {rows.map((r) => {
                const st = STATUS_STYLES[r.status] || STATUS_STYLES.UNMATCHED
                return (
                  <tr key={r.id} className="hover:bg-surface-container-low/20 transition-colors align-top">
                    <td className="p-4 font-mono text-xs">{r.transactionId}</td>
                    <td className="p-4">
                      <div className="font-medium">{r.payerName || '—'}</div>
                      <div className="text-xs text-on-surface-variant">{r.phoneNumber}</div>
                    </td>
                    <td className="p-4 font-mono">{r.billRefNumber || '—'}</td>
                    <td className="p-4 font-semibold tabular-nums">{fmtKES(r.amount)}</td>
                    <td className="p-4 text-on-surface-variant whitespace-nowrap">
                      {fmtDate(r.createdAt)}, {fmtTime(r.createdAt)}
                      <div className="text-xs">{relativeTime(r.createdAt)}</div>
                    </td>
                    <td className="p-4">
                      <span className={`text-xs font-semibold px-2.5 py-1 rounded-full whitespace-nowrap ${st.cls}`}>{st.label}</span>
                      {r.note && <p className="text-xs text-on-surface-variant mt-1 max-w-xs">{r.note}</p>}
                    </td>
                    <td className="p-4 text-right">
                      {r.status === 'UNMATCHED' && (
                        <button onClick={() => setApplyFor(applyFor === r.id ? null : r.id)}
                          className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer">
                          Apply
                        </button>
                      )}
                      {applyFor === r.id && (
                        <div className="flex items-center gap-2 mt-3 justify-end flex-wrap">
                          <select value={subscriberId} onChange={(e) => setSubscriberId(e.target.value)}
                            className="h-9 bg-surface border border-outline-variant rounded-lg px-2 text-xs focus:outline-none focus:border-primary max-w-[160px]">
                            {subs.map((s) => <option key={s.id} value={s.id}>{s.fullName} ({s.pppoeUsername})</option>)}
                          </select>
                          <input type="number" min="1" max="24" value={months} onChange={(e) => setMonths(e.target.value)}
                            className="h-9 w-14 bg-surface border border-outline-variant rounded-lg px-2 text-xs text-center tabular-nums focus:outline-none focus:border-primary" />
                          <button onClick={() => apply(r.id)} className="h-9 px-3 rounded-lg bg-secondary text-on-secondary text-xs font-semibold cursor-pointer">Credit</button>
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
              {rows.length === 0 && (
                <tr><td className="p-4 text-on-surface-variant" colSpan={7}>No PayBill payments yet. They appear here the moment Safaricom posts a confirmation.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
