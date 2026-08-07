import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, PrimaryButton, StatCard, fmtKES, fmtDate, INPUT_CLS, LABEL_CLS } from '../../components/ui.jsx'

const EXPENSE_CATEGORIES = ['BANDWIDTH', 'EQUIPMENT', 'RENT', 'SALARIES', 'TRANSPORT', 'POWER', 'LICENCES', 'MARKETING', 'OTHER']

function RevenueChart({ series }) {
  const max = Math.max(...series.map((d) => Number(d.amount)), 1)
  const n = series.length
  const points = series.map((d, i) => [(i / Math.max(n - 1, 1)) * 100, 46 - (Number(d.amount) / max) * 40])
  const line = points.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ')
  const area = `M0,50 L${line.replace(/ /g, ' L')} L100,50 Z`

  return (
    <div className="relative flex-1 min-h-[180px] flex items-end border-b border-l border-outline-variant/30">
      <svg className="w-full h-full text-primary opacity-20 absolute inset-0" preserveAspectRatio="none" viewBox="0 0 100 50">
        <path d={area} fill="currentColor" />
      </svg>
      <svg className="w-full h-full absolute inset-0 text-primary" preserveAspectRatio="none" viewBox="0 0 100 50">
        <polyline points={line} fill="none" stroke="currentColor" strokeLinejoin="round" strokeWidth="0.8" />
      </svg>
    </div>
  )
}

function csvDownload(rows, filename) {
  const blob = new Blob([rows.join('\n')], { type: 'text/csv' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.click()
  URL.revokeObjectURL(a.href)
}

export default function Finance({ auth }) {
  const [tab, setTab] = useState('reports')
  const [days, setDays] = useState(30)
  const [summary, setSummary] = useState(null)
  const [series, setSeries] = useState([])
  const [invoices, setInvoices] = useState(null)
  const [expenses, setExpenses] = useState(null)
  const [subs, setSubs] = useState([])
  const [expenseForm, setExpenseForm] = useState({ description: '', category: 'BANDWIDTH', amount: '', incurredOn: '' })
  const [msg, setMsg] = useState(null)
  const [busy, setBusy] = useState(false)

  const loadReports = () => {
    api(`/admin/reports/summary?days=${days}`, { auth }).then(setSummary).catch(() => {})
    api(`/admin/reports/daily?days=${days}`, { auth }).then(setSeries).catch(() => {})
  }
  const loadInvoices = () => api('/admin/invoices', { auth }).then(setInvoices).catch(() => setInvoices([]))
  const loadExpenses = () => api('/admin/expenses', { auth }).then(setExpenses).catch(() => setExpenses([]))

  useEffect(() => { loadReports() }, [auth, days]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    loadInvoices()
    loadExpenses()
    api('/admin/subscribers', { auth }).then(setSubs).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const unpaidInvoices = useMemo(() => (invoices || []).filter((i) => i.status === 'UNPAID'), [invoices])

  async function addExpense(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/expenses', {
        method: 'POST',
        auth,
        body: {
          description: expenseForm.description,
          category: expenseForm.category,
          amount: Number(expenseForm.amount),
          incurredOn: expenseForm.incurredOn || null,
        },
      })
      setExpenseForm({ description: '', category: 'BANDWIDTH', amount: '', incurredOn: '' })
      setMsg({ ok: true, text: 'Expense recorded.' })
      loadExpenses()
      loadReports()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function runInvoicing() {
    setMsg(null)
    try {
      const r = await api('/admin/invoices/run', { method: 'POST', auth })
      setMsg({ ok: true, text: r.message })
      loadInvoices()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  const TABS = [['reports', 'Reports'], ['invoices', 'Invoices'], ['expenses', 'Expenses']]

  return (
    <div>
      <PageHeader title="Finance" subtitle="Revenue, receivables, costs and profit.">
        <div className="flex gap-2">
          {[7, 30, 90].map((d) => (
            <button key={d} onClick={() => setDays(d)}
              className={`px-4 py-2 rounded-full text-sm font-semibold transition-colors cursor-pointer ${
                days === d ? 'bg-primary text-on-primary' : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
              }`}>
              {d}D
            </button>
          ))}
        </div>
      </PageHeader>

      <nav className="flex gap-2 mb-6 flex-wrap">
        {TABS.map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
            className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
              tab === key ? 'bg-primary-container text-on-primary-container font-semibold' : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {label}{key === 'invoices' && unpaidInvoices.length > 0 ? ` (${unpaidInvoices.length})` : ''}
          </button>
        ))}
      </nav>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      {tab === 'reports' && (!summary ? <Skeleton className="h-64" /> : (
        <div className="flex flex-col gap-6">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard label={`Revenue (${days}d)`} value={fmtKES(summary.totalRevenue)} accent="border-l-primary"
              hint={`Vouchers ${fmtKES(summary.voucherRevenue)} · Subs ${fmtKES(summary.subscriptionRevenue)}`} />
            <StatCard label={`Expenses (${days}d)`} value={fmtKES(summary.totalExpenses)} accent="border-l-error" />
            <StatCard label="Profit" value={fmtKES(summary.profit)}
              accent={Number(summary.profit) >= 0 ? 'border-l-secondary' : 'border-l-error'} />
            <StatCard label="Expected Monthly" value={fmtKES(summary.expectedMonthlyRevenue)}
              hint={`${summary.activeSubscribers} active subscriber(s)`} />
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div className="lg:col-span-2 bg-surface-container-lowest rounded-lg p-4  flex flex-col">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <h3 className="text-lg font-semibold text-on-surface">Revenue Trend</h3>
                  <p className="text-sm text-on-surface-variant">Vouchers + subscriptions, last {days} days</p>
                </div>
                <button onClick={() => csvDownload(['Date,Amount', ...series.map((d) => `${d.date},${d.amount}`)], `revenue-${days}d.csv`)}
                  className="flex items-center gap-1.5 px-3 py-1.5 border border-outline-variant rounded-lg text-xs font-semibold hover:bg-surface-container transition-colors cursor-pointer">
                  <Icon name="download" className="text-[16px]!" /> CSV
                </button>
              </div>
              <RevenueChart series={series} />
            </div>

            <div className="bg-surface-container-lowest rounded-lg p-4 ">
              <h3 className="text-lg font-semibold text-on-surface mb-1">Open Receivables</h3>
              <p className="text-3xl font-bold text-[#b45309] tabular-nums mb-4">{fmtKES(summary.openReceivables)}</p>
              <h4 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Expenses by Category</h4>
              <ul className="space-y-2">
                {Object.entries(summary.expensesByCategory || {}).map(([cat, amount]) => (
                  <li key={cat} className="flex justify-between text-sm">
                    <span className="text-on-surface-variant capitalize">{cat.toLowerCase()}</span>
                    <span className="font-semibold tabular-nums">{fmtKES(amount)}</span>
                  </li>
                ))}
                {Object.keys(summary.expensesByCategory || {}).length === 0 && (
                  <li className="text-sm text-on-surface-variant">No expenses in this period.</li>
                )}
              </ul>
            </div>
          </div>
        </div>
      ))}

      {tab === 'invoices' && (
        <div>
          <div className="flex justify-end mb-4">
            <PrimaryButton onClick={runInvoicing}><Icon name="receipt_long" /> Run Invoicing Now</PrimaryButton>
          </div>
          {invoices === null ? <Skeleton className="h-64" /> : (
            <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
              <div className="overflow-x-auto table-scroll">
                <table className="data-table w-full text-left border-collapse min-w-[800px]">
                  <thead>
                    <tr className="bg-surface-container-low/50 text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
                      <th className="">Invoice</th>
                      <th className="">Customer</th>
                      <th className="">Amount</th>
                      <th className="">Issued</th>
                      <th className="">Due</th>
                      <th className="">Status</th>
                      <th className="text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="text-sm divide-y divide-surface-variant/30">
                    {invoices.map((i) => (
                      <tr key={i.id} className="hover:bg-surface-container-low/20 transition-colors">
                        <td className="font-mono">{i.number}</td>
                        <td className="">
                          <div className="font-semibold">{i.subscriber?.fullName}</div>
                          <div className="text-xs text-on-surface-variant font-mono">{i.subscriber?.pppoeUsername}</div>
                        </td>
                        <td className="font-semibold tabular-nums">{fmtKES(i.amount)}</td>
                        <td className="text-on-surface-variant">{i.issuedOn}</td>
                        <td className="text-on-surface-variant">{i.dueOn}</td>
                        <td className="">
                          <span className={`text-xs font-semibold px-2.5 py-1 rounded-full whitespace-nowrap ${
                            i.status === 'PAID' ? 'bg-secondary-container text-on-secondary-container'
                              : i.status === 'CANCELLED' ? 'bg-surface-variant text-on-surface-variant'
                              : 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20'
                          }`}>
                            {i.status === 'PAID' ? 'Paid' : i.status === 'CANCELLED' ? 'Cancelled' : 'Unpaid'}
                          </span>
                        </td>
                        <td className="text-right">
                          {i.status === 'UNPAID' && (
                            <button onClick={() => api(`/admin/invoices/${i.id}/cancel`, { method: 'PATCH', auth }).then(loadInvoices)}
                              className="text-xs font-semibold text-error hover:underline cursor-pointer">CANCEL</button>
                          )}
                        </td>
                      </tr>
                    ))}
                    {invoices.length === 0 && (
                      <tr><td className="text-on-surface-variant" colSpan={7}>No invoices yet — they are issued automatically 5 days before a subscription lapses.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}

      {tab === 'expenses' && (
        <div className="flex flex-col gap-6">
          <form onSubmit={addExpense} className="bg-surface-container-lowest rounded-lg p-4  grid grid-cols-1 md:grid-cols-5 gap-4 items-end">
            <div className="md:col-span-2">
              <label className={LABEL_CLS}>Description</label>
              <input className={INPUT_CLS} required placeholder="e.g. Monthly bandwidth — Faiba" value={expenseForm.description} onChange={(e) => setExpenseForm({ ...expenseForm, description: e.target.value })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Category</label>
              <select className={INPUT_CLS} value={expenseForm.category} onChange={(e) => setExpenseForm({ ...expenseForm, category: e.target.value })}>
                {EXPENSE_CATEGORIES.map((c) => <option key={c} value={c}>{c.charAt(0) + c.slice(1).toLowerCase()}</option>)}
              </select>
            </div>
            <div>
              <label className={LABEL_CLS}>Amount (KES)</label>
              <input className={INPUT_CLS} type="number" min="1" required value={expenseForm.amount} onChange={(e) => setExpenseForm({ ...expenseForm, amount: e.target.value })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Date</label>
              <input className={INPUT_CLS} type="date" value={expenseForm.incurredOn} onChange={(e) => setExpenseForm({ ...expenseForm, incurredOn: e.target.value })} />
            </div>
            <PrimaryButton type="submit" disabled={busy} className="md:col-span-5 justify-self-end">
              <Icon name="add" /> {busy ? 'Saving…' : 'Record Expense'}
            </PrimaryButton>
          </form>

          {expenses === null ? <Skeleton className="h-48" /> : (
            <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
              <div className="p-4 border-b border-surface-variant/30 flex justify-between items-center">
                <h3 className="text-lg font-semibold text-on-surface">Recent Expenses</h3>
                <button onClick={() => csvDownload(
                  ['Date,Category,Description,Amount,RecordedBy',
                    ...expenses.map((e) => `${e.incurredOn},${e.category},"${e.description}",${e.amount},${e.recordedBy || ''}`)],
                  'expenses.csv')}
                  className="flex items-center gap-1.5 px-3 py-1.5 border border-outline-variant rounded-lg text-xs font-semibold hover:bg-surface-container transition-colors cursor-pointer">
                  <Icon name="download" className="text-[16px]!" /> CSV
                </button>
              </div>
              <ul className="divide-y divide-surface-variant/30">
                {expenses.map((e) => (
                  <li key={e.id} className="p-4 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-base font-semibold text-on-surface">{e.description}</p>
                      <p className="text-xs text-on-surface-variant mt-0.5">
                        {e.category.charAt(0) + e.category.slice(1).toLowerCase()} · {e.incurredOn}
                        {e.recordedBy ? ` · by ${e.recordedBy}` : ''}
                      </p>
                    </div>
                    <div className="flex items-center gap-3 shrink-0">
                      <span className="text-base font-semibold text-error tabular-nums">{fmtKES(e.amount)}</span>
                      <button onClick={() => api(`/admin/expenses/${e.id}`, { method: 'DELETE', auth }).then(() => { loadExpenses(); loadReports() })}
                        className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer" aria-label="Delete expense">
                        <Icon name="delete" className="text-[18px]!" />
                      </button>
                    </div>
                  </li>
                ))}
                {expenses.length === 0 && <li className="p-4 text-sm text-on-surface-variant">No expenses recorded yet.</li>}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
