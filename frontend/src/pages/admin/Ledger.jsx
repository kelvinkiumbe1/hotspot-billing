import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard,
  fmtKES, fmtDate, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const ADJUSTMENTS = [
  { key: 'CREDIT_NOTE', label: 'Credit note', hint: 'Reduces what they owe — a correction or refund.' },
  { key: 'DISCOUNT', label: 'Goodwill discount', hint: 'A reduction you have chosen to give.' },
  { key: 'WRITE_OFF', label: 'Write off', hint: 'Give up on a debt. Cannot exceed what is owed.' },
  { key: 'PENALTY', label: 'Penalty', hint: 'Increases what they owe — a late fee.' },
]

const TYPE_STYLES = {
  INVOICE: 'bg-[#f59e0b]/10 text-[#b45309]',
  PAYMENT: 'bg-secondary-container text-on-secondary-container',
  CREDIT_NOTE: 'bg-primary-container/25 text-primary',
  DISCOUNT: 'bg-primary-container/25 text-primary',
  WRITE_OFF: 'bg-surface-container-high text-on-surface-variant',
  PENALTY: 'bg-error-container text-on-error-container',
}

const pretty = (s) => (s || '').replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())

/**
 * Balance in words as well as sign — "owes" and "in credit" are opposite
 * meanings for the same number, so the sign alone is not enough.
 */
function BalanceLabel({ balance, className = '' }) {
  const n = Number(balance)
  if (n === 0) return <span className={className}>Settled</span>
  return (
    <span className={className}>
      {fmtKES(Math.abs(n))} <span className="font-normal">{n > 0 ? 'owed' : 'in credit'}</span>
    </span>
  )
}

function AdjustForm({ auth, subscriberId, onCancel, onDone }) {
  const [form, setForm] = useState({ kind: 'CREDIT_NOTE', amount: '', reason: '', appliedOn: '' })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api(`/admin/ledger/${subscriberId}/adjustments`, {
        method: 'POST',
        auth,
        body: {
          kind: form.kind,
          amount: Number(form.amount),
          reason: form.reason,
          appliedOn: form.appliedOn || null,
        },
      })
      onDone()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mt-4 p-4 rounded-lg bg-surface-container-low/60 border border-outline-variant space-y-3">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
        <div>
          <label className={LABEL_CLS}>Kind</label>
          <select className={INPUT_CLS} value={form.kind} onChange={(e) => set({ kind: e.target.value })}>
            {ADJUSTMENTS.map((a) => <option key={a.key} value={a.key}>{a.label}</option>)}
          </select>
        </div>
        <div>
          <label className={LABEL_CLS}>Amount (KES)</label>
          <input className={INPUT_CLS} type="number" min="0.01" step="0.01" required value={form.amount}
            onChange={(e) => set({ amount: e.target.value })} />
        </div>
        <div className="md:col-span-2">
          <label className={LABEL_CLS}>Reason</label>
          <input className={INPUT_CLS} required placeholder="Why is this being applied?" value={form.reason}
            onChange={(e) => set({ reason: e.target.value })} />
        </div>
      </div>
      <p className="text-xs text-on-surface-variant">
        {ADJUSTMENTS.find((a) => a.key === form.kind)?.hint}
      </p>
      {error && <p className="text-sm text-error">{error}</p>}
      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Applying…' : 'Apply'}</PrimaryButton>
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
    </form>
  )
}

function Statement({ auth, subscriber, onBack }) {
  const [data, setData] = useState(null)
  const [range, setRange] = useState({ from: '', to: '' })
  const [adjusting, setAdjusting] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => {
    const q = new URLSearchParams()
    if (range.from) q.set('from', range.from)
    if (range.to) q.set('to', range.to)
    const suffix = q.toString() ? `?${q}` : ''
    api(`/admin/ledger/${subscriber.subscriberId}${suffix}`, { auth }).then(setData).catch(() => setData(null))
  }
  useEffect(() => { load() }, [auth, subscriber, range]) // eslint-disable-line react-hooks/exhaustive-deps

  function print() {
    const w = window.open('', '_blank')
    if (!w || !data) return
    const rows = data.entries.map((e) => `<tr>
      <td>${e.date}</td><td>${pretty(e.type)}</td><td>${e.reference}</td><td>${e.description}</td>
      <td class="n">${Number(e.debit) ? Number(e.debit).toFixed(2) : ''}</td>
      <td class="n">${Number(e.credit) ? Number(e.credit).toFixed(2) : ''}</td>
      <td class="n">${Number(e.balance).toFixed(2)}</td></tr>`).join('')
    const closing = Number(data.summary.balance)
    w.document.write(`<!doctype html><html><head><title>Statement — ${subscriber.fullName}</title><style>
      body { font-family: Arial, sans-serif; margin: 14mm; font-size: 10.5pt; }
      h1 { font-size: 15pt; margin: 0 0 2mm; }
      .sub { color: #555; margin-bottom: 6mm; }
      table { width: 100%; border-collapse: collapse; }
      th, td { border-bottom: 1px solid #ccc; padding: 4px 6px; text-align: left; }
      th { background: #eee; }
      .n { text-align: right; font-variant-numeric: tabular-nums; }
      tfoot td { font-weight: bold; border-top: 2px solid #333; border-bottom: none; }
      .toolbar { margin-bottom: 5mm; }
      @media print { .toolbar { display: none; } }
    </style></head><body>
      <div class="toolbar"><button onclick="window.print()">Print</button></div>
      <h1>Statement of account</h1>
      <div class="sub">
        ${subscriber.fullName} · ${subscriber.account} · ${subscriber.phoneNumber || ''}<br>
        ${range.from || 'from the beginning'} to ${range.to || 'today'}
      </div>
      <table>
        <thead><tr><th>Date</th><th>Type</th><th>Reference</th><th>Description</th>
          <th class="n">Charged</th><th class="n">Paid</th><th class="n">Balance</th></tr></thead>
        <tbody>
          <tr><td colspan="6"><em>Opening balance</em></td><td class="n">${Number(data.openingBalance).toFixed(2)}</td></tr>
          ${rows}
        </tbody>
        <tfoot><tr><td colspan="6">${closing > 0 ? 'Amount due' : closing < 0 ? 'In credit' : 'Settled'}</td>
          <td class="n">${Math.abs(closing).toFixed(2)}</td></tr></tfoot>
      </table>
    </body></html>`)
    w.document.close()
  }

  if (!data) return <Skeleton className="h-64" />

  const s = data.summary

  return (
    <div>
      <button onClick={onBack} className="mb-4 text-sm text-primary cursor-pointer flex items-center gap-1">
        <Icon name="arrow_back" className="text-[16px]!" /> All accounts
      </button>

      <PageHeader
        title={subscriber.fullName}
        subtitle={`${subscriber.account}${subscriber.phoneNumber ? ` · ${subscriber.phoneNumber}` : ''}`}
      >
        <div className="flex gap-2">
          <button onClick={print}
            className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high flex items-center gap-1.5">
            <Icon name="print" className="text-[18px]!" /> Statement
          </button>
          <PrimaryButton onClick={() => setAdjusting(!adjusting)}>
            <Icon name="tune" /> Adjust
          </PrimaryButton>
        </div>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <StatCard label="Charged" value={fmtKES(s.invoiced)} hint={`${s.entries} entries`} />
        <StatCard label="Paid" value={fmtKES(s.paid)} accent="border-t-primary" />
        <StatCard
          label={Number(s.balance) > 0 ? 'Owes' : Number(s.balance) < 0 ? 'In credit' : 'Balance'}
          value={fmtKES(Math.abs(Number(s.balance)))}
          hint={Number(s.balance) > 0 ? 'in arrears' : Number(s.balance) < 0 ? 'applies to the next invoice' : 'settled'}
          accent={Number(s.balance) > 0 ? 'border-t-error' : ''}
        />
        <StatCard label="Last movement" value={s.lastEntry ? fmtDate(s.lastEntry) : '—'} hint="" />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {adjusting && (
        <AdjustForm
          auth={auth}
          subscriberId={subscriber.subscriberId}
          onCancel={() => setAdjusting(false)}
          onDone={() => { setAdjusting(false); setMsg({ ok: true, text: 'Adjustment applied.' }); load() }}
        />
      )}

      <div className="flex flex-wrap gap-3 items-end my-4">
        <div>
          <label className={LABEL_CLS}>From</label>
          <input className={INPUT_CLS} type="date" value={range.from}
            onChange={(e) => setRange({ ...range, from: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>To</label>
          <input className={INPUT_CLS} type="date" value={range.to}
            onChange={(e) => setRange({ ...range, to: e.target.value })} />
        </div>
        {(range.from || range.to) && (
          <button onClick={() => setRange({ from: '', to: '' })}
            className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
            Clear
          </button>
        )}
      </div>

      <div className="bg-surface-container-lowest rounded-xl border border-outline-variant/40 overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full">
            <thead>
              <tr>
                <th>Date</th>
                <th>Type</th>
                <th>Reference</th>
                <th>Description</th>
                <th className="text-right">Charged</th>
                <th className="text-right">Paid</th>
                <th className="text-right">Balance</th>
              </tr>
            </thead>
            <tbody>
              <tr className="bg-surface-container-low/40">
                <td colSpan={6} className="italic text-on-surface-variant">Opening balance</td>
                <td className="text-right tabular-nums font-semibold">{fmtKES(data.openingBalance)}</td>
              </tr>
              {data.entries.map((e, i) => (
                <tr key={i}>
                  <td className="text-xs whitespace-nowrap">{fmtDate(e.date)}</td>
                  <td>
                    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${TYPE_STYLES[e.type] || ''}`}>
                      {pretty(e.type)}
                    </span>
                  </td>
                  <td className="font-mono text-xs">{e.reference}</td>
                  <td className="text-sm">{e.description}</td>
                  <td className="text-right tabular-nums">{Number(e.debit) ? fmtKES(e.debit) : ''}</td>
                  <td className="text-right tabular-nums">{Number(e.credit) ? fmtKES(e.credit) : ''}</td>
                  <td className={`text-right tabular-nums font-semibold ${Number(e.balance) > 0 ? 'text-error' : ''}`}>
                    {fmtKES(e.balance)}
                  </td>
                </tr>
              ))}
              {data.entries.length === 0 && (
                <tr><td colSpan={7} className="text-on-surface-variant">Nothing in this period.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <p className="mt-3 text-xs text-on-surface-variant">
        The balance always accumulates from the very first entry, so a statement for one month still opens at
        the right figure. A positive balance is money owed to you; a negative one is credit that settles their
        next invoice automatically.
      </p>
    </div>
  )
}

export default function LedgerPage({ auth }) {
  const [rows, setRows] = useState(null)
  const [selected, setSelected] = useState(null)
  const [search, setSearch] = useState('')

  useEffect(() => {
    api('/admin/ledger/outstanding', { auth }).then(setRows).catch(() => setRows([]))
  }, [auth])

  const totals = useMemo(() => {
    const owed = (rows || []).filter((r) => r.owes).reduce((a, r) => a + Number(r.balance), 0)
    const credit = (rows || []).filter((r) => !r.owes).reduce((a, r) => a - Number(r.balance), 0)
    return { owed, credit }
  }, [rows])

  const shown = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return (rows || []).filter((r) => !needle
      || r.fullName.toLowerCase().includes(needle)
      || (r.account || '').toLowerCase().includes(needle)
      || (r.phoneNumber || '').includes(needle))
  }, [rows, search])

  if (rows === null) return <Skeleton className="h-64" />
  if (selected) return <Statement auth={auth} subscriber={selected} onBack={() => setSelected(null)} />

  return (
    <div>
      <PageHeader title="Customer ledger" subtitle="Who owes what, and who is paid ahead." />

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <StatCard
          label="Total Owed"
          value={fmtKES(totals.owed)}
          hint={`${rows.filter((r) => r.owes).length} in arrears`}
          accent={totals.owed > 0 ? 'border-t-error' : ''}
        />
        <StatCard
          label="Held In Credit"
          value={fmtKES(totals.credit)}
          hint={`${rows.filter((r) => !r.owes).length} paid ahead`}
          accent="border-t-primary"
        />
        <StatCard label="Accounts With A Balance" value={rows.length} hint="settled ones are hidden" />
        <StatCard
          label="Net Position"
          value={fmtKES(totals.owed - totals.credit)}
          hint={totals.owed - totals.credit >= 0 ? 'owed to you overall' : 'prepaid overall'}
        />
      </div>

      <input
        className="mb-4 w-full max-w-sm bg-surface border border-outline-variant rounded-lg px-3 py-2 text-sm focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search by name, account or phone…"
        aria-label="Search accounts"
      />

      {shown.length === 0 ? (
        <div className="p-10 text-center rounded-xl bg-surface-container-lowest border border-outline-variant">
          <Icon name="check_circle" className="text-[40px]! text-secondary/60" />
          <p className="mt-2 text-on-surface-variant">
            {rows.length === 0 ? 'Every account is settled — nothing owed, nothing in credit.' : 'Nothing matches that search.'}
          </p>
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-xl border border-outline-variant/40 overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr>
                  <th>Customer</th>
                  <th>Account</th>
                  <th>Status</th>
                  <th className="text-right">Balance</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((r) => (
                  <tr key={r.subscriberId}>
                    <td>
                      <p className="font-semibold">{r.fullName}</p>
                      <p className="text-xs text-on-surface-variant">{r.phoneNumber}</p>
                    </td>
                    <td className="font-mono text-xs">{r.account}</td>
                    <td>
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${
                        r.status === 'ACTIVE'
                          ? 'bg-secondary-container text-on-secondary-container'
                          : 'bg-error-container text-on-error-container'
                      }`}>
                        {r.status === 'ACTIVE' ? 'Active' : 'Suspended'}
                      </span>
                    </td>
                    <td className="text-right">
                      <BalanceLabel
                        balance={r.balance}
                        className={`font-semibold tabular-nums ${r.owes ? 'text-error' : 'text-primary'}`}
                      />
                    </td>
                    <td className="text-right">
                      <button onClick={() => setSelected(r)}
                        className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high">
                        Statement
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
