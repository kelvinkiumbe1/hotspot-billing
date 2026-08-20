import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, fmtKES, fmtDate,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * Quotes and credit notes -- the two documents a business customer asks for.
 *
 * They share a page because they share an audience: the person here is dealing
 * with a company's finance department, not a household. Everything else in this
 * admin is built for the household case.
 *
 * The one thing this screen insists on saying out loud is which documents are
 * debts. A quote is not, and that is the whole reason it exists -- issuing a real
 * invoice to give a company something to pay against used to put them in the
 * arrears list and the dunning queue for money they had never agreed to owe.
 */

const TABS = [
  ['quotes', 'Quotes', 'request_quote'],
  ['credits', 'Credit notes', 'receipt'],
]

function QuoteStatus({ q }) {
  const [label, cls] = q.status === 'CONVERTED'
    ? [`invoiced ${q.invoiceNumber || ''}`.trim(), 'bg-secondary-container text-on-secondary-container']
    : q.status === 'CANCELLED' ? ['cancelled', 'bg-surface-container-high text-on-surface-variant']
      : q.expired ? ['expired', 'bg-error-container text-on-error-container']
        : ['open', 'bg-primary-fixed/40 text-primary']
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      {label}
    </span>
  )
}

function NewQuote({ auth, customers, onDone, onClose }) {
  const [form, setForm] = useState({
    subscriberId: '', months: 1, amount: '', description: '', validDays: 30,
  })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  const chosen = customers.find((c) => String(c.id) === form.subscriberId)
  const listPrice = chosen ? Number(chosen.monthlyFee) * Number(form.months || 1) : null

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/billing-documents/quotes', {
        method: 'POST', auth,
        body: {
          subscriberId: Number(form.subscriberId),
          months: Number(form.months),
          // Blank means the list price. Sent as null so the server does the
          // arithmetic once, rather than the browser and the server both doing it.
          amount: form.amount.trim() ? Number(form.amount) : null,
          description: form.description.trim() || null,
          validDays: Number(form.validDays) || 30,
        },
      })
      setMsg({ ok: true, text: r.message })
      onDone()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="rounded-lg border border-outline-variant p-4 space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-semibold">New quote</p>
          <p className="text-xs text-on-surface-variant mt-1">
            A priced document with a number on it. It is not a debt &mdash; nobody is
            chased for a quote, and it only becomes money owed when you turn it into
            an invoice.
          </p>
        </div>
        <button type="button" onClick={onClose} className="cursor-pointer"><Icon name="close" /></button>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>Customer</label>
          <select className={INPUT_CLS} value={form.subscriberId}
            onChange={(e) => set({ subscriberId: e.target.value })}>
            <option value="">Choose one</option>
            {customers.map((c) => (
              <option key={c.id} value={String(c.id)}>
                {c.fullName} ({c.pppoeUsername}) — {fmtKES(c.monthlyFee)}/mo
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className={LABEL_CLS}>Months</label>
          <input className={INPUT_CLS} type="number" min="1" max="60" value={form.months}
            onChange={(e) => set({ months: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Valid for (days)</label>
          <input className={INPUT_CLS} type="number" min="1" max="365" value={form.validDays}
            onChange={(e) => set({ validDays: e.target.value })} />
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>
            Price {listPrice !== null && (
              <span className="normal-case font-normal">
                (blank uses the list price, {fmtKES(listPrice)})
              </span>
            )}
          </label>
          <input className={INPUT_CLS} type="number" value={form.amount}
            placeholder={listPrice !== null ? String(listPrice) : 'total for the whole period'}
            onChange={(e) => set({ amount: e.target.value })} />
          <p className="text-xs text-on-surface-variant mt-1">
            A negotiated annual figure goes here. It is the total for the whole period,
            not per month.
          </p>
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>What it is for</label>
          <input className={INPUT_CLS} value={form.description}
            placeholder="Dedicated 50Mbps fibre, 12 months"
            onChange={(e) => set({ description: e.target.value })} />
        </div>
      </div>

      <PrimaryButton disabled={busy || !form.subscriberId} onClick={save}>
        {busy ? 'Issuing…' : 'Issue quote'}
      </PrimaryButton>
      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
    </div>
  )
}

function NewCreditNote({ auth, customers, onDone, onClose }) {
  const [form, setForm] = useState({ subscriberId: '', invoiceId: '', amount: '', reason: '' })
  const [invoices, setInvoices] = useState([])
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  useEffect(() => {
    if (!form.subscriberId) { setInvoices([]); return }
    api(`/admin/billing-documents/creditable/${form.subscriberId}`, { auth })
      .then((d) => setInvoices(d.invoices || []))
      .catch(() => setInvoices([]))
  }, [form.subscriberId, auth])

  const invoice = invoices.find((i) => String(i.id) === form.invoiceId)
  const overLimit = invoice && form.amount && Number(form.amount) > Number(invoice.creditable)

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/billing-documents/credit-notes', {
        method: 'POST', auth,
        body: {
          subscriberId: Number(form.subscriberId),
          invoiceId: form.invoiceId ? Number(form.invoiceId) : null,
          amount: Number(form.amount),
          reason: form.reason,
        },
      })
      setMsg({ ok: true, text: r.message })
      onDone()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="rounded-lg border border-outline-variant p-4 space-y-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-semibold">New credit note</p>
          <p className="text-xs text-on-surface-variant mt-1">
            Reverses a charge and puts the customer in credit. The tax is reversed at
            the rate the invoice charged it, not today&rsquo;s.
          </p>
        </div>
        <button type="button" onClick={onClose} className="cursor-pointer"><Icon name="close" /></button>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>Customer</label>
          <select className={INPUT_CLS} value={form.subscriberId}
            onChange={(e) => set({ subscriberId: e.target.value, invoiceId: '' })}>
            <option value="">Choose one</option>
            {customers.map((c) => (
              <option key={c.id} value={String(c.id)}>{c.fullName} ({c.pppoeUsername})</option>
            ))}
          </select>
        </div>
        <div className="sm:col-span-2">
          <label className={LABEL_CLS}>Which invoice</label>
          <select className={INPUT_CLS} value={form.invoiceId}
            onChange={(e) => set({ invoiceId: e.target.value })}>
            <option value="">None — a goodwill credit</option>
            {invoices.filter((i) => Number(i.creditable) > 0).map((i) => (
              <option key={i.id} value={String(i.id)}>
                {i.number} — {fmtKES(i.amount)} ({fmtKES(i.creditable)} still creditable)
              </option>
            ))}
          </select>
          <p className="text-xs text-on-surface-variant mt-1">
            Naming the invoice is what makes this answerable to an auditor. A goodwill
            credit that answers to no invoice is allowed, and says so.
          </p>
        </div>
        <div>
          <label className={LABEL_CLS}>Amount</label>
          <input className={INPUT_CLS} type="number" value={form.amount}
            onChange={(e) => set({ amount: e.target.value })} />
          {overLimit && (
            <p className="text-xs text-[#b91c1c] mt-1">
              Only {fmtKES(invoice.creditable)} of {invoice.number} can still be credited.
            </p>
          )}
        </div>
        <div>
          <label className={LABEL_CLS}>Why</label>
          <input className={INPUT_CLS} value={form.reason} placeholder="Three days offline"
            onChange={(e) => set({ reason: e.target.value })} />
        </div>
      </div>

      <PrimaryButton
        disabled={busy || !form.subscriberId || !form.amount || !form.reason.trim() || overLimit}
        onClick={save}>
        {busy ? 'Issuing…' : 'Issue credit note'}
      </PrimaryButton>
      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
    </div>
  )
}

export default function BillingDocumentsPage({ auth }) {
  const [tab, setTab] = useState('quotes')
  const [quotes, setQuotes] = useState(null)
  const [credits, setCredits] = useState(null)
  const [customers, setCustomers] = useState([])
  const [creating, setCreating] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => {
    api('/admin/billing-documents/quotes', { auth })
      .then((d) => setQuotes(d.quotes || [])).catch(() => setQuotes([]))
    api('/admin/billing-documents/credit-notes', { auth })
      .then((d) => setCredits(d.creditNotes || [])).catch(() => setCredits([]))
  }
  useEffect(() => {
    load()
    api('/admin/subscribers', { auth }).then((d) => setCustomers(d || [])).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function act(id, what) {
    setMsg(null)
    try {
      const r = await api(`/admin/billing-documents/quotes/${id}/${what}`, { method: 'POST', auth })
      setMsg({ ok: true, text: r.message })
      load()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  const open = useMemo(() => (quotes || []).filter((q) => q.live), [quotes])

  if (quotes === null || credits === null) return <Skeleton className="h-64" />

  return (
    <>
      <PageHeader title="Quotes and credit notes"
        subtitle="The documents a business customer asks for before and after they pay.">
        <PrimaryButton onClick={() => setCreating((v) => !v)}>
          {creating ? 'Close' : tab === 'quotes' ? 'New quote' : 'New credit note'}
        </PrimaryButton>
      </PageHeader>

      <div className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="Open quotes" value={open.length} />
          <StatCard label="Quoted and waiting"
            value={fmtKES(open.reduce((s, q) => s + Number(q.amount), 0))} />
          <StatCard label="Credit notes issued" value={credits.length} />
          <StatCard label="Credited back"
            value={fmtKES(credits.reduce((s, c) => s + Number(c.amount), 0))} />
        </div>

        <div className="flex gap-1 border-b border-outline-variant">
          {TABS.map(([key, label, icon]) => (
            <button key={key} type="button"
              onClick={() => { setTab(key); setCreating(false) }}
              className={`px-4 py-2 text-sm font-medium cursor-pointer flex items-center gap-2 border-b-2 ${
                tab === key ? 'border-primary text-primary'
                  : 'border-transparent text-on-surface-variant hover:text-on-surface'}`}>
              <Icon name={icon} className="text-[18px]!" />{label}
            </button>
          ))}
        </div>

        {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}

        {creating && tab === 'quotes' && (
          <NewQuote auth={auth} customers={customers}
            onDone={load} onClose={() => setCreating(false)} />
        )}
        {creating && tab === 'credits' && (
          <NewCreditNote auth={auth} customers={customers}
            onDone={load} onClose={() => setCreating(false)} />
        )}

        {tab === 'quotes' && (quotes.length === 0 ? (
          <div className="rounded-lg border border-outline-variant p-6 text-center">
            <Icon name="request_quote" className="text-[32px]! text-on-surface-variant" />
            <p className="text-base font-semibold mt-2">No quotes yet</p>
            <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
              Issue one when a company needs a document to raise a payment against. Unlike
              an invoice it is not a debt, so it never appears in arrears and nobody is
              chased for it.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Number</th>
                  <th className="text-left font-medium px-3 py-2">Customer</th>
                  <th className="text-right font-medium px-3 py-2">Amount</th>
                  <th className="text-left font-medium px-3 py-2">Valid until</th>
                  <th className="text-left font-medium px-3 py-2">State</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {quotes.map((q) => (
                  <tr key={q.id} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2 font-mono text-xs">{q.number}</td>
                    <td className="px-3 py-2">
                      <p className="font-medium">{q.customer}</p>
                      {q.description && (
                        <p className="text-xs text-on-surface-variant truncate max-w-xs">
                          {q.description}
                        </p>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">
                      {fmtKES(q.amount)}
                      <span className="block text-xs text-on-surface-variant">
                        {q.months} month{q.months === 1 ? '' : 's'}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-on-surface-variant">{fmtDate(q.validUntil)}</td>
                    <td className="px-3 py-2"><QuoteStatus q={q} /></td>
                    <td className="px-3 py-2 text-right whitespace-nowrap">
                      {q.live && (
                        <>
                          <button type="button" onClick={() => act(q.id, 'convert')}
                            className="text-primary text-sm cursor-pointer hover:underline">
                            Make it an invoice
                          </button>
                          <button type="button" onClick={() => act(q.id, 'cancel')}
                            className="ml-3 text-on-surface-variant text-sm cursor-pointer hover:underline">
                            Cancel
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}

        {tab === 'credits' && (credits.length === 0 ? (
          <div className="rounded-lg border border-outline-variant p-6 text-center">
            <Icon name="receipt" className="text-[32px]! text-on-surface-variant" />
            <p className="text-base font-semibold mt-2">No credit notes yet</p>
            <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
              Issue one when a customer has been overcharged or is owed something back.
              It puts them in credit and reverses the tax at the rate the invoice charged.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Number</th>
                  <th className="text-left font-medium px-3 py-2">Customer</th>
                  <th className="text-right font-medium px-3 py-2">Amount</th>
                  <th className="text-left font-medium px-3 py-2">Against</th>
                  <th className="text-left font-medium px-3 py-2">Why</th>
                  <th className="text-left font-medium px-3 py-2">Issued</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {credits.map((c) => (
                  <tr key={c.id} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2 font-mono text-xs">{c.number}</td>
                    <td className="px-3 py-2 font-medium">{c.customer}</td>
                    <td className="px-3 py-2 text-right font-mono tabular-nums">
                      {fmtKES(c.amount)}
                      {c.vatAmount != null && (
                        <span className="block text-xs text-on-surface-variant">
                          incl. {fmtKES(c.vatAmount)} tax
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">
                      {c.invoiceNumber || <span className="font-sans italic">goodwill</span>}
                    </td>
                    <td className="px-3 py-2 max-w-xs truncate" title={c.reason}>{c.reason}</td>
                    <td className="px-3 py-2 text-on-surface-variant">{fmtDate(c.issuedOn)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </>
  )
}
