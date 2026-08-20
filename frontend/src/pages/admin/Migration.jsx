import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { parseDelimited, sniffDelimiter } from '../../csv.js'
import { Icon, fmtKES } from '../../components/ui.jsx'

/**
 * Moving in from another system.
 *
 * <p>The shape of the screen follows the shape of the risk. Upload, then read
 * what would happen, then read what it does to the money, and only then a button
 * that creates anybody. An operator with three thousand customers is not going to
 * press something that says "import" and hope.
 */

const SOURCE_HELP = {
  SPLYNX: 'Export the internet services report — it carries the customer, the login and the tariff on one row.',
  UISP: 'Export clients with their services. Names arrive in two columns and are put back together here.',
  RADIUS_MANAGER: 'Export the users table. The expiry date is the field that decides who is still connected.',
  GENERIC: 'Any CSV with a name, a phone number, a login and a price. Headings are matched loosely.',
}

const VERDICT_STYLE = {
  NEW: 'text-secondary',
  COLLISION: 'text-warning',
  INCOMPLETE: 'text-error',
}

/** The rows as heading→value objects, which is what the server reads. */
function toRecords(header, rows) {
  return rows
    .filter((r) => r.some((c) => c && String(c).trim()))
    .map((r) => {
      const out = {}
      header.forEach((h, i) => {
        if (h && r[i] !== undefined && r[i] !== null && String(r[i]).trim()) {
          out[h] = String(r[i]).trim()
        }
      })
      return out
    })
}

function Upload({ auth, onStaged }) {
  const [source, setSource] = useState('SPLYNX')
  const [dateOrder, setDateOrder] = useState('AUTO')
  const [label, setLabel] = useState('')
  const [table, setTable] = useState(null)
  const [filename, setFilename] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  function read(file) {
    setMsg(null)
    setFilename(file.name)
    if (!label) setLabel(file.name.replace(/\.[^.]+$/, ''))
    const reader = new FileReader()
    reader.onload = () => {
      const text = String(reader.result)
      const recs = parseDelimited(text, sniffDelimiter(text))
        .filter((r) => r.some((c) => c && String(c).trim()))
      if (recs.length < 2) {
        setTable(null)
        setMsg({ ok: false, text: 'That file has no rows I can read.' })
        return
      }
      setTable({ header: recs[0].map((h) => (h || '').trim()), rows: recs.slice(1) })
    }
    reader.readAsText(file)
  }

  const records = useMemo(
    () => (table ? toRecords(table.header, table.rows) : []),
    [table],
  )

  async function stage() {
    setBusy(true); setMsg(null)
    try {
      const res = await api('/admin/migration', {
        method: 'POST', auth,
        body: { source, label: label || filename, dateOrder, rows: records },
      })
      onStaged(res)
      setTable(null); setFilename('')
    } catch (e) {
      setMsg({ ok: false, text: e.message || 'That upload could not be read.' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="bg-surface-container rounded-xl p-4 space-y-4">
      <div>
        <h3 className="font-semibold mb-1">Bring a customer book across</h3>
        <p className="text-sm text-on-surface-variant">
          Nothing is created until you press the last button. Uploading only reads the
          file and tells you what it found.
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label className="block text-xs text-on-surface-variant mb-1">Coming from</label>
          <select value={source} onChange={(e) => setSource(e.target.value)}
            className="w-full h-10 bg-surface border border-outline-variant rounded-lg px-3 text-sm">
            <option value="SPLYNX">Splynx</option>
            <option value="UISP">UISP / UCRM (Ubiquiti)</option>
            <option value="RADIUS_MANAGER">Radius Manager</option>
            <option value="GENERIC">Something else (CSV)</option>
          </select>
          <p className="text-xs text-on-surface-variant mt-1">{SOURCE_HELP[source]}</p>
        </div>

        <div>
          <label className="block text-xs text-on-surface-variant mb-1">
            Dates in the file are
          </label>
          <select value={dateOrder} onChange={(e) => setDateOrder(e.target.value)}
            className="w-full h-10 bg-surface border border-outline-variant rounded-lg px-3 text-sm">
            <option value="AUTO">Work it out (skip anything unclear)</option>
            <option value="DMY">Day / month / year</option>
            <option value="MDY">Month / day / year</option>
          </select>
          {/* The single most expensive field in the file. */}
          <p className="text-xs text-on-surface-variant mt-1">
            03/04/2026 could be either. Left on the first setting, a date like that is
            skipped rather than guessed — a wrong expiry cuts paying customers off.
          </p>
        </div>
      </div>

      <div>
        <label className="block text-xs text-on-surface-variant mb-1">Call this import</label>
        <input value={label} onChange={(e) => setLabel(e.target.value)}
          placeholder="Splynx book, March"
          className="w-full h-10 bg-surface border border-outline-variant rounded-lg px-3 text-sm" />
      </div>

      <label className="flex items-center gap-3 border border-dashed border-outline-variant
                        rounded-lg p-4 cursor-pointer hover:bg-surface-container-high">
        <Icon name="attach_file" className="text-on-surface-variant" />
        <span className="text-sm">
          {filename || 'Choose the CSV you exported'}
        </span>
        <input type="file" accept=".csv,.txt,text/csv" className="hidden"
          onChange={(e) => e.target.files?.[0] && read(e.target.files[0])} />
      </label>

      {table && (
        <div className="text-sm space-y-2">
          <p className="text-on-surface-variant">
            {records.length} row(s), {table.header.length} column(s) read from{' '}
            <span className="font-medium">{filename}</span>.
          </p>
          <div className="overflow-x-auto border border-outline-variant/50 rounded-lg">
            <table className="text-xs min-w-full">
              <thead className="bg-surface-container-high">
                <tr>{table.header.slice(0, 8).map((h) => (
                  <th key={h} className="text-left px-2 py-1.5 font-medium whitespace-nowrap">{h}</th>
                ))}</tr>
              </thead>
              <tbody>
                {table.rows.slice(0, 3).map((r, i) => (
                  <tr key={i} className="border-t border-outline-variant/40">
                    {r.slice(0, 8).map((c, j) => (
                      <td key={j} className="px-2 py-1.5 whitespace-nowrap">{c}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <button disabled={busy || !records.length} onClick={stage}
            className="h-10 px-4 rounded-lg bg-primary text-on-primary text-sm font-semibold
                       disabled:opacity-60 cursor-pointer">
            {busy ? 'Reading…' : `Read these ${records.length} row(s)`}
          </button>
        </div>
      )}

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
    </div>
  )
}

function Plan({ auth, batchId, onDone }) {
  const [plan, setPlan] = useState(null)
  const [compare, setCompare] = useState(null)
  const [rows, setRows] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [showRows, setShowRows] = useState(false)

  async function load() {
    const [p, c] = await Promise.all([
      api(`/admin/migration/${batchId}`, { auth }),
      api(`/admin/migration/${batchId}/compare`, { auth }),
    ])
    setPlan(p); setCompare(c)
  }

  useEffect(() => { load().catch((e) => setMsg({ ok: false, text: e.message })) },
    [batchId]) // eslint-disable-line react-hooks/exhaustive-deps

  async function openRows() {
    setShowRows(true)
    if (!rows) setRows(await api(`/admin/migration/${batchId}/rows`, { auth }))
  }

  async function promote() {
    setBusy(true); setMsg(null)
    try {
      const res = await api(`/admin/migration/${batchId}/promote`, { method: 'POST', auth })
      setMsg({
        ok: true,
        text: `${res.created} customer(s) created${res.skipped ? `, ${res.skipped} skipped` : ''}.`,
        problems: res.problems || [],
      })
      await load()
      onDone?.()
    } catch (e) {
      setMsg({ ok: false, text: e.message || 'That could not be brought across.' })
    } finally {
      setBusy(false)
    }
  }

  async function discard() {
    setBusy(true); setMsg(null)
    try {
      await api(`/admin/migration/${batchId}`, { method: 'DELETE', auth })
      onDone?.()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally {
      setBusy(false)
    }
  }

  if (!plan) return <p className="text-sm text-on-surface-variant">Loading…</p>

  const done = plan.status === 'PROMOTED'

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        {[
          ['Ready to bring across', plan.ready, 'text-secondary'],
          ['Already here', plan.collisions, 'text-warning'],
          ['Not enough to use', plan.incomplete, 'text-error'],
        ].map(([label, value, tone]) => (
          <div key={label} className="bg-surface-container rounded-xl p-4">
            <p className="text-xs text-on-surface-variant">{label}</p>
            <p className={`text-2xl font-semibold ${tone}`}>{value}</p>
          </div>
        ))}
      </div>

      {plan.packagesNotHere?.length > 0 && (
        <div className="bg-surface-container rounded-xl p-4">
          <p className="text-sm font-medium mb-1 flex items-center gap-1.5">
            <Icon name="error" className="text-warning text-[18px]!" />
            Packages the file names that do not exist here
          </p>
          <p className="text-xs text-on-surface-variant mb-2">
            Create these first and read the file again, or those customers arrive on their
            old price with no speed set.
          </p>
          <ul className="text-sm list-disc pl-5">
            {plan.packagesNotHere.map((p) => <li key={p}>{p}</li>)}
          </ul>
        </div>
      )}

      {compare && (
        <div className="bg-surface-container rounded-xl p-4 space-y-2">
          <h4 className="font-semibold">What this does to next month's billing</h4>
          <p className="text-xs text-on-surface-variant">
            Their monthly total against what we would charge the same customers. A large
            difference almost always means a package is mapped to the wrong one.
          </p>
          <div className="grid gap-3 sm:grid-cols-3 text-sm">
            <div>
              <p className="text-xs text-on-surface-variant">They charge</p>
              <p className="text-lg font-semibold">{fmtKES(compare.theirMonthlyTotal)}</p>
            </div>
            <div>
              <p className="text-xs text-on-surface-variant">We would charge</p>
              <p className="text-lg font-semibold">{fmtKES(compare.ourMonthlyTotal)}</p>
            </div>
            <div>
              <p className="text-xs text-on-surface-variant">Difference</p>
              <p className={`text-lg font-semibold ${
                Number(compare.difference) === 0 ? '' :
                Number(compare.difference) < 0 ? 'text-error' : 'text-secondary'}`}>
                {fmtKES(compare.difference)}
              </p>
            </div>
          </div>
          {compare.differenceCount > 0 && (
            <div className="overflow-x-auto">
              <table className="text-xs min-w-full">
                <thead className="text-on-surface-variant">
                  <tr>
                    <th className="text-left py-1.5">Customer</th>
                    <th className="text-left py-1.5">Their package</th>
                    <th className="text-right py-1.5">Theirs</th>
                    <th className="text-right py-1.5">Ours</th>
                    <th className="text-left py-1.5 pl-3">Why</th>
                  </tr>
                </thead>
                <tbody>
                  {compare.differences.map((d, i) => (
                    <tr key={i} className="border-t border-outline-variant/40">
                      <td className="py-1.5">{d.name}</td>
                      <td className="py-1.5">{d.planName || '—'}</td>
                      <td className="py-1.5 text-right">{fmtKES(d.theirPrice)}</td>
                      <td className="py-1.5 text-right">{fmtKES(d.ourPrice)}</td>
                      <td className="py-1.5 pl-3 text-on-surface-variant">{d.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {compare.differenceCount > compare.differences.length && (
                <p className="text-xs text-on-surface-variant mt-1">
                  Showing {compare.differences.length} of {compare.differenceCount}. The
                  totals above cover every row.
                </p>
              )}
            </div>
          )}
        </div>
      )}

      <div className="flex flex-wrap gap-2 items-center">
        <button onClick={openRows}
          className="h-10 px-4 rounded-lg border border-outline-variant text-sm cursor-pointer">
          Look at the rows
        </button>
        {!done && (
          <>
            <button disabled={busy || !plan.ready} onClick={promote}
              className="h-10 px-4 rounded-lg bg-primary text-on-primary text-sm font-semibold
                         disabled:opacity-60 cursor-pointer">
              {busy ? 'Working…' : `Create ${plan.ready} customer(s)`}
            </button>
            <button disabled={busy} onClick={discard}
              className="h-10 px-4 rounded-lg border border-outline-variant text-sm
                         text-error cursor-pointer">
              Throw this import away
            </button>
          </>
        )}
        {done && (
          <span className="text-sm text-secondary flex items-center gap-1.5">
            <Icon name="check_circle" className="text-[18px]!" />
            Brought across. The customers exist here now.
          </span>
        )}
      </div>

      {/* Said plainly, because an operator who thinks the move is finished will
          wonder why nobody can connect. */}
      {done && (
        <p className="text-xs text-on-surface-variant">
          They are in the book but not yet on a router here. Move them onto one from
          Network → Fleet when you are ready to cut over.
        </p>
      )}

      {msg && (
        <div className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>
          <p>{msg.text}</p>
          {msg.problems?.length > 0 && (
            <ul className="list-disc pl-5 mt-1 text-on-surface-variant">
              {msg.problems.map((p, i) => <li key={i}>{p}</li>)}
            </ul>
          )}
        </div>
      )}

      {showRows && rows && (
        <div className="bg-surface-container rounded-xl p-4 overflow-x-auto">
          <table className="text-xs min-w-full">
            <thead className="text-on-surface-variant">
              <tr>
                <th className="text-left py-1.5">Name</th>
                <th className="text-left py-1.5">Login</th>
                <th className="text-left py-1.5">Phone</th>
                <th className="text-left py-1.5">Package</th>
                <th className="text-right py-1.5">Price</th>
                <th className="text-left py-1.5 pl-3">Verdict</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t border-outline-variant/40">
                  <td className="py-1.5">{r.fullName || '—'}</td>
                  <td className="py-1.5 font-mono">{r.pppoeUsername || '—'}</td>
                  <td className="py-1.5">{r.phoneNumber || '—'}</td>
                  <td className="py-1.5">{r.planName || '—'}</td>
                  <td className="py-1.5 text-right">{r.monthlyPrice ? fmtKES(r.monthlyPrice) : '—'}</td>
                  <td className="py-1.5 pl-3">
                    <span className={VERDICT_STYLE[r.verdict] || ''}>{r.verdict}</span>
                    {r.verdictNote && (
                      <span className="text-on-surface-variant"> — {r.verdictNote}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

export default function MigrationPage({ auth }) {
  const [batches, setBatches] = useState(null)
  const [open, setOpen] = useState(null)
  const [msg, setMsg] = useState(null)

  async function load() {
    try {
      setBatches(await api('/admin/migration', { auth }))
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-xl font-semibold">Move in</h2>
        <p className="text-sm text-on-surface-variant">
          Bring your customers over from Splynx, UISP, Radius Manager or a plain CSV —
          read it, check what it would do, then create them.
        </p>
      </div>

      {open ? (
        <div className="space-y-4">
          <button onClick={() => { setOpen(null); load() }}
            className="text-sm text-primary cursor-pointer flex items-center gap-1">
            <Icon name="arrow_back" className="text-[18px]!" /> All imports
          </button>
          <Plan auth={auth} batchId={open} onDone={load} />
        </div>
      ) : (
        <>
          <Upload auth={auth} onStaged={(res) => { load(); setOpen(res.batchId) }} />

          <div className="bg-surface-container rounded-xl p-4">
            <h3 className="font-semibold mb-2">Imports so far</h3>
            {!batches?.length ? (
              <p className="text-sm text-on-surface-variant">Nothing brought across yet.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="text-sm min-w-full">
                  <thead className="text-on-surface-variant text-xs">
                    <tr>
                      <th className="text-left py-1.5">Import</th>
                      <th className="text-left py-1.5">From</th>
                      <th className="text-right py-1.5">Rows</th>
                      <th className="text-left py-1.5 pl-3">State</th>
                      <th className="text-left py-1.5">When</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {batches.map((b) => (
                      <tr key={b.id} className="border-t border-outline-variant/40">
                        <td className="py-2">{b.label || `Import ${b.id}`}</td>
                        <td className="py-2">{b.source}</td>
                        <td className="py-2 text-right">{b.rowCount}</td>
                        <td className="py-2 pl-3">
                          <span className={b.status === 'PROMOTED' ? 'text-secondary'
                            : b.status === 'DISCARDED' ? 'text-on-surface-variant' : 'text-warning'}>
                            {b.status === 'PROMOTED' ? 'Brought across'
                              : b.status === 'DISCARDED' ? 'Thrown away' : 'Waiting on you'}
                          </span>
                        </td>
                        <td className="py-2 text-on-surface-variant text-xs">
                          {b.createdAt ? new Date(b.createdAt).toLocaleString() : '—'}
                        </td>
                        <td className="py-2 text-right">
                          <button onClick={() => setOpen(b.id)}
                            className="text-primary text-sm cursor-pointer">Open</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {msg && <p className="text-sm text-error">{msg.text}</p>}
    </div>
  )
}
