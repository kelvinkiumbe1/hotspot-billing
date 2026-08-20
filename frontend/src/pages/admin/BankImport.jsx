import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { parseDelimited, sniffDelimiter } from '../../csv.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime, fmtKES,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * Bank statements in, and a queue of money to place.
 *
 * Two screens' worth of work in one page, because they are one job: upload the
 * statement, then clear what it could not place by itself. The queue is the part
 * that matters day to day and so it is what you land on.
 *
 * The upload is deliberately three steps -- choose a file, check the columns,
 * then import. Bank CSVs disagree about everything: column names, date order,
 * whether debits are negative or in their own column. Guessing and importing in
 * one press would mean discovering the guess was wrong after the money had been
 * placed.
 */

/** Header names seen on Kenyan bank exports, per field we need. */
const COLUMN_HINTS = {
  valueDate: ['value date', 'date', 'transaction date', 'txn date', 'posting date', 'date posted'],
  narration: ['narration', 'description', 'details', 'particulars', 'transaction details', 'remarks'],
  bankReference: ['reference', 'ref', 'transaction ref', 'cheque no', 'transaction id', 'receipt no'],
  credit: ['credit', 'money in', 'amount credited', 'deposits', 'cr amount'],
  debit: ['debit', 'money out', 'amount debited', 'withdrawals', 'dr amount'],
  amount: ['amount', 'transaction amount', 'value'],
}

const FIELD_LABELS = {
  valueDate: 'Date',
  narration: 'Narration / description',
  bankReference: 'Bank reference',
  credit: 'Money in',
  debit: 'Money out',
  amount: 'Amount (if one column for both)',
}

/**
 * Dates, the way banks actually write them.
 *
 * Day-first is assumed for the ambiguous ones because that is what every bank in
 * the region prints. 03/04/2026 is the 3rd of April, and a parser that guessed
 * American order would put a third of every statement in the wrong month.
 */
function parseDate(raw) {
  const s = (raw || '').trim()
  if (!s) return null
  const iso = /^(\d{4})-(\d{2})-(\d{2})/.exec(s)
  if (iso) return `${iso[1]}-${iso[2]}-${iso[3]}`
  const dmy = /^(\d{1,2})[/.\- ](\d{1,2})[/.\- ](\d{2,4})/.exec(s)
  if (dmy) {
    const year = dmy[3].length === 2 ? `20${dmy[3]}` : dmy[3]
    return `${year}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
  }
  // "05 Aug 2026" and "05-AUG-26", both common on printed statements.
  const named = /^(\d{1,2})[-\s]([A-Za-z]{3})[A-Za-z]*[-\s](\d{2,4})/.exec(s)
  if (named) {
    const months = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']
    const m = months.indexOf(named[2].toLowerCase())
    if (m >= 0) {
      const year = named[3].length === 2 ? `20${named[3]}` : named[3]
      return `${year}-${String(m + 1).padStart(2, '0')}-${named[1].padStart(2, '0')}`
    }
  }
  return null
}

/** Bank amount strings: thousands separators, brackets for negative, stray currency. */
function parseAmount(raw) {
  let s = (raw || '').trim()
  if (!s) return null
  const bracketed = /^\((.*)\)$/.exec(s)
  if (bracketed) s = `-${bracketed[1]}`
  s = s.replace(/[^0-9.-]/g, '')
  if (!s || s === '-' || s === '.') return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

function UploadPanel({ auth, onDone }) {
  const [filename, setFilename] = useState('')
  const [bankName, setBankName] = useState('')
  const [table, setTable] = useState(null)
  const [cols, setCols] = useState({})
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [result, setResult] = useState(null)

  function read(file) {
    setMsg(null); setResult(null)
    setFilename(file.name)
    const reader = new FileReader()
    reader.onload = () => {
      const text = String(reader.result)
      const recs = parseDelimited(text, sniffDelimiter(text))
        .filter((r) => r.some((c) => c && c.trim()))
      if (recs.length < 2) {
        setTable(null)
        setMsg({ ok: false, text: 'That file has no rows I can read.' })
        return
      }
      // Statements often carry a few title lines before the real header. The
      // header is the first row that looks like one of ours.
      let headerAt = 0
      for (let i = 0; i < Math.min(recs.length, 12); i++) {
        const lower = recs[i].map((h) => (h || '').trim().toLowerCase())
        const looksLikeHeader = Object.values(COLUMN_HINTS)
          .some((names) => lower.some((h) => names.includes(h)))
        if (looksLikeHeader) { headerAt = i; break }
      }
      const header = recs[headerAt].map((h) => (h || '').trim())
      const lower = header.map((h) => h.toLowerCase())
      const guessed = {}
      for (const [field, names] of Object.entries(COLUMN_HINTS)) {
        const at = lower.findIndex((h) => names.includes(h))
        guessed[field] = at >= 0 ? String(at) : ''
      }
      setCols(guessed)
      setTable({ header, rows: recs.slice(headerAt + 1) })
    }
    reader.readAsText(file)
  }

  /**
   * The rows as they will be sent. Credits only: a debit is the operator paying
   * somebody, and the server drops them anyway, but showing the count here means
   * the operator can see the mapping is right before importing.
   */
  const parsed = useMemo(() => {
    if (!table) return null
    const at = (field) => (cols[field] === '' || cols[field] === undefined ? -1 : Number(cols[field]))
    const out = []
    let debits = 0
    let unreadable = 0
    for (const r of table.rows) {
      const get = (field) => {
        const i = at(field)
        return i >= 0 && i < r.length ? r[i] : ''
      }
      let amount = parseAmount(get('credit'))
      if (amount === null && at('debit') >= 0 && parseAmount(get('debit')) !== null) {
        debits++
        continue
      }
      if (amount === null) amount = parseAmount(get('amount'))
      if (amount === null) { unreadable++; continue }
      if (amount <= 0) { debits++; continue }
      const narration = (get('narration') || '').trim()
      if (!narration) { unreadable++; continue }
      out.push({
        valueDate: parseDate(get('valueDate')),
        narration,
        bankReference: (get('bankReference') || '').trim() || null,
        amount,
      })
    }
    return { rows: out, debits, unreadable }
  }, [table, cols])

  async function doImport() {
    setBusy(true); setMsg(null)
    try {
      const res = await api('/admin/bank/import', {
        method: 'POST', auth,
        body: { filename, bankName: bankName.trim() || null, rows: parsed.rows },
      })
      setResult(res)
      onDone()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  const noDate = parsed ? parsed.rows.filter((r) => !r.valueDate).length : 0

  return (
    <div className="rounded-lg border border-outline-variant p-4 space-y-4">
      <div>
        <p className="text-sm font-semibold">Import a statement</p>
        <p className="text-xs text-on-surface-variant mt-1">
          Export it from your bank as CSV. Nothing is credited to any customer until
          you say so, except a transfer that quotes one of our own payment references.
        </p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <label className={LABEL_CLS}>Statement file</label>
          <input type="file" accept=".csv,.txt,text/csv" className={INPUT_CLS}
            onChange={(e) => e.target.files?.[0] && read(e.target.files[0])} />
        </div>
        <div>
          <label className={LABEL_CLS}>Which bank (optional)</label>
          <input className={INPUT_CLS} value={bankName} placeholder="Equity, KCB, Co-op…"
            onChange={(e) => setBankName(e.target.value)} />
        </div>
      </div>

      {table && (
        <>
          <div>
            <p className="text-sm font-semibold mb-2">Which column is which?</p>
            <p className="text-xs text-on-surface-variant mb-3">
              Guessed from the headings. Fix anything wrong &mdash; the preview below
              updates as you do.
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
              {Object.keys(COLUMN_HINTS).map((field) => (
                <div key={field}>
                  <label className={LABEL_CLS}>{FIELD_LABELS[field]}</label>
                  <select className={INPUT_CLS} value={cols[field] ?? ''}
                    onChange={(e) => setCols({ ...cols, [field]: e.target.value })}>
                    <option value="">not in this file</option>
                    {table.header.map((h, i) => (
                      <option key={i} value={String(i)}>{h || `column ${i + 1}`}</option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
          </div>

          {parsed && (
            <div className="space-y-3">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <StatCard label="Money in" value={parsed.rows.length} />
                <StatCard label="Money out (skipped)" value={parsed.debits} />
                <StatCard label="Could not read" value={parsed.unreadable}
                  accent={parsed.unreadable > 0 ? 'border-t-error' : undefined} />
                <StatCard label="No date" value={noDate}
                  accent={noDate > 0 ? 'border-t-error' : undefined} />
              </div>

              {noDate > 0 && (
                <p className="text-xs text-warning flex items-start gap-2">
                  <Icon name="warning" className="text-[16px]! mt-0.5" />
                  {noDate} row(s) have no date I could read. The date is part of how a
                  repeated statement is recognised, so importing without it makes a
                  double-credit more likely. Check the date column first.
                </p>
              )}

              {parsed.rows.length > 0 && (
                <div className="overflow-x-auto rounded-lg border border-outline-variant">
                  <table className="w-full text-xs">
                    <thead className="bg-surface-container-low text-on-surface-variant">
                      <tr>
                        <th className="text-left font-medium px-2 py-1.5">Date</th>
                        <th className="text-left font-medium px-2 py-1.5">Narration</th>
                        <th className="text-left font-medium px-2 py-1.5">Reference</th>
                        <th className="text-right font-medium px-2 py-1.5">Amount</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-outline-variant/40">
                      {parsed.rows.slice(0, 6).map((r, i) => (
                        <tr key={i}>
                          <td className="px-2 py-1.5 font-mono">{r.valueDate || '—'}</td>
                          <td className="px-2 py-1.5 max-w-md truncate">{r.narration}</td>
                          <td className="px-2 py-1.5 font-mono">{r.bankReference || '—'}</td>
                          <td className="px-2 py-1.5 text-right font-mono">{fmtKES(r.amount)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {parsed.rows.length > 6 && (
                    <p className="text-xs text-on-surface-variant px-2 py-1.5">
                      …and {parsed.rows.length - 6} more.
                    </p>
                  )}
                </div>
              )}

              <PrimaryButton disabled={busy || parsed.rows.length === 0} onClick={doImport}>
                {busy ? 'Importing…' : `Import ${parsed.rows.length} credit(s)`}
              </PrimaryButton>
            </div>
          )}
        </>
      )}

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}

      {result && (
        <div className="rounded-lg border border-secondary/40 bg-secondary-container/20 p-3 text-sm space-y-1">
          <p className="font-semibold">Imported.</p>
          <p>{result.credits} credit(s) read.</p>
          {result.duplicates > 0 && (
            <p>{result.duplicates} were already imported and were skipped.</p>
          )}
          {result.applied > 0 && (
            <p>{result.applied} quoted our own reference and were credited automatically.</p>
          )}
          <p>{result.waiting} waiting for you below.</p>
        </div>
      )}
    </div>
  )
}

/** One line, and the decision it needs. */
function QueueRow({ auth, txn, customers, onDone }) {
  const [choice, setChoice] = useState(txn.subscriberId ? String(txn.subscriberId) : '')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const chosen = customers.find((c) => String(c.id) === choice)
  const months = chosen && chosen.monthlyFee > 0
    ? Math.floor(txn.amount / chosen.monthlyFee) : 1

  async function act(what) {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/bank/transaction/${txn.id}/${what}`, {
        method: 'POST', auth,
        body: what === 'apply' ? { subscriberId: choice ? Number(choice) : null } : undefined,
      })
      if (r.ok) onDone()
      else setMsg({ ok: false, text: r.message })
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <li className="p-3 space-y-2">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="min-w-0 flex-1">
          <p className="text-sm break-words">{txn.narration}</p>
          <p className="text-xs text-on-surface-variant font-mono mt-0.5">
            {txn.valueDate || 'no date'} {txn.bankReference ? `· ${txn.bankReference}` : ''}
          </p>
          {/* The reason in words, so this is something to agree or disagree with
              rather than a score to trust. */}
          {txn.matchReason && (
            <p className="text-xs text-secondary mt-1 flex items-start gap-1">
              <Icon name="lightbulb" className="text-[14px]! mt-0.5" />
              {txn.matchReason}
            </p>
          )}
        </div>
        <p className="font-mono text-lg font-semibold tabular-nums shrink-0">
          {fmtKES(txn.amount)}
        </p>
      </div>

      <div className="flex items-end gap-2 flex-wrap">
        <div className="flex-1 min-w-[14rem]">
          <label className={LABEL_CLS}>Whose money is this?</label>
          <select className={INPUT_CLS} value={choice} onChange={(e) => setChoice(e.target.value)}>
            <option value="">Nobody yet — pick a customer</option>
            {customers.map((c) => (
              <option key={c.id} value={String(c.id)}>
                {c.fullName} ({c.pppoeUsername}) — {fmtKES(c.monthlyFee)}/mo
              </option>
            ))}
          </select>
        </div>
        <PrimaryButton disabled={busy || !choice} onClick={() => act('apply')}>
          {busy ? 'Working…' : `Credit ${months} month${months === 1 ? '' : 's'}`}
        </PrimaryButton>
        <button type="button" disabled={busy} onClick={() => act('ignore')}
          className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Not a customer payment
        </button>
      </div>

      {choice && chosen && months === 0 && (
        <p className="text-xs text-warning flex items-start gap-2">
          <Icon name="warning" className="text-[16px]! mt-0.5" />
          {fmtKES(txn.amount)} is less than {chosen.fullName}&rsquo;s {fmtKES(chosen.monthlyFee)}
          {' '}monthly fee, so this buys no whole month. Crediting it will record the payment
          and extend nothing.
        </p>
      )}
      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
    </li>
  )
}

export default function BankImportPage({ auth }) {
  const [queue, setQueue] = useState(null)
  const [history, setHistory] = useState(null)
  const [customers, setCustomers] = useState([])
  const [showUpload, setShowUpload] = useState(false)

  const load = () => {
    api('/admin/bank/queue', { auth })
      .then((d) => setQueue(d.transactions || [])).catch(() => setQueue([]))
    api('/admin/bank/imports', { auth }).then(setHistory).catch(() => setHistory({ imports: [] }))
  }
  useEffect(() => {
    load()
    api('/admin/subscribers', { auth }).then((d) => setCustomers(d || [])).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (queue === null || history === null) return <Skeleton className="h-64" />

  const unmatched = queue.filter((t) => t.status === 'UNMATCHED').length

  return (
    <>
      <PageHeader title="Bank transfers"
        subtitle="Money that arrived at the bank, and who it belongs to.">
        <PrimaryButton onClick={() => setShowUpload((v) => !v)}>
          {showUpload ? 'Hide the importer' : 'Import a statement'}
        </PrimaryButton>
      </PageHeader>

      <div className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
          <StatCard label="Waiting for you" value={queue.length}
            accent={queue.length > 0 ? 'border-t-error' : undefined} />
          <StatCard label="With a suggestion" value={queue.length - unmatched} />
          <StatCard label="Statements imported" value={history.imports.length} />
        </div>

        {showUpload && <UploadPanel auth={auth} onDone={load} />}

        {queue.length === 0 ? (
          <div className="rounded-lg border border-outline-variant p-6 text-center">
            <Icon name="account_balance" className="text-[32px]! text-on-surface-variant" />
            <p className="text-base font-semibold mt-2">Nothing waiting</p>
            <p className="text-sm text-on-surface-variant mt-1">
              Every bank transfer imported so far has been placed or set aside.
            </p>
          </div>
        ) : (
          <div>
            <p className="text-sm font-semibold mb-2">
              {queue.length} transfer{queue.length === 1 ? '' : 's'} to place
              <span className="font-normal text-on-surface-variant">
                {' '}&mdash; oldest first
              </span>
            </p>
            <ul className="divide-y divide-outline-variant/40 rounded-lg border border-outline-variant">
              {queue.map((t) => (
                <QueueRow key={t.id} auth={auth} txn={t} customers={customers} onDone={load} />
              ))}
            </ul>
          </div>
        )}

        {history.imports.length > 0 && (
          <details>
            <summary className="cursor-pointer text-sm font-semibold">
              Statements imported
            </summary>
            <ul className="mt-2 divide-y divide-outline-variant/40 text-sm">
              {history.imports.map((b) => (
                <li key={b.id} className="py-2 flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate">{b.filename}{b.bankName ? ` · ${b.bankName}` : ''}</p>
                    <p className="text-xs text-on-surface-variant">
                      {b.credits} credit(s), {b.applied} credited automatically
                      {b.duplicates > 0 && `, ${b.duplicates} already seen`}
                    </p>
                  </div>
                  <span className="text-xs text-on-surface-variant whitespace-nowrap">
                    {relativeTime(b.uploadedAt)} · {b.uploadedBy}
                  </span>
                </li>
              ))}
            </ul>
          </details>
        )}
      </div>
    </>
  )
}
