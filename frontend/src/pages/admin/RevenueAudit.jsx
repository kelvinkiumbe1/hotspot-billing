import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, Toggle,
  fmtKES, fmtDate, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * Each check, in the operator's language rather than the enum's. The "why it
 * matters" line is the whole point of the page — a finding nobody understands
 * is a finding nobody acts on.
 */
const KINDS = {
  PAID_NO_SERVICE: {
    label: 'Paid, got nothing', icon: 'money_off',
    why: 'The customer was charged and never received a voucher. Refund or issue it by hand.',
  },
  DUPLICATE_RECEIPT: {
    label: 'Receipt used twice', icon: 'content_copy',
    why: 'One M-Pesa receipt behind several sales — a replayed callback gave away free service.',
  },
  UNAPPLIED_PAYMENT: {
    label: 'Money not applied', icon: 'inbox',
    why: 'A PayBill payment nobody has been credited for. Match it on the PayBill page.',
  },
  SERVICE_NO_PAYMENT: {
    label: 'Voucher from nowhere', icon: 'help',
    why: 'Service the system never sold. Check who has database access, or mark old stock as expected.',
  },
  GHOST_HOTSPOT_USER: {
    label: 'Login not sold by you', icon: 'person_alert',
    why: 'Somebody created this straight on the router. Free internet, invisible to every report.',
  },
  GHOST_PPPOE_SECRET: {
    label: 'Unbilled connection', icon: 'lan',
    why: 'A PPPoE account on the router that belongs to no customer in the system.',
  },
  EXPIRED_STILL_ONLINE: {
    label: 'Online without paying', icon: 'wifi_off',
    why: 'The cut-off never took on the router — they are still using the network.',
  },
  LAPSED_NOT_SUSPENDED: {
    label: 'Lapsed, never suspended', icon: 'event_busy',
    why: 'Past their paid-until date and still switched on, month after month.',
  },
  UNDERPAID: {
    label: 'Sold below price', icon: 'trending_down',
    why: 'Settled for less than the plan cost, with any promotion of the day already allowed for.',
  },
}

const SEVERITY_STYLES = {
  HIGH: 'bg-error-container text-on-error-container',
  MEDIUM: 'bg-warning/15 text-warning',
  LOW: 'bg-surface-container-high text-on-surface-variant',
}

const SEVERITY_WORDS = { HIGH: 'Serious', MEDIUM: 'Worth a look', LOW: 'Minor' }

function kindOf(kind) {
  return KINDS[kind] || { label: kind, icon: 'help', why: '' }
}

function SettingsPanel({ auth, settings, onSaved }) {
  const [form, setForm] = useState(settings)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function save() {
    setBusy(true)
    setMsg(null)
    try {
      const saved = await api('/admin/revenue-audit/settings', { method: 'PUT', auth, body: form })
      onSaved(saved)
      setMsg({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mb-5 p-4 rounded-lg bg-surface-container-low/60 border border-outline-variant">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <label className="flex items-start gap-3">
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
          <span>
            <span className="text-sm font-semibold">Run the check every night</span>
            <span className="block text-xs text-on-surface-variant">Sweeps at 2:30am, when the network is quiet.</span>
          </span>
        </label>
        <label className="flex items-start gap-3">
          <Toggle checked={form.alertOperator} onChange={(e) => set({ alertOperator: e.target.checked })} />
          <span>
            <span className="text-sm font-semibold">Text me about serious findings</span>
            <span className="block text-xs text-on-surface-variant">Goes to the alert number under messaging settings.</span>
          </span>
        </label>
      </div>

      <div className="mt-4 grid grid-cols-1 md:grid-cols-3 gap-3">
        <div>
          <label className={LABEL_CLS}>Unmatched after (hours)</label>
          <input className={INPUT_CLS} type="number" min="1" max="720" value={form.unmatchedHours}
            onChange={(e) => set({ unmatchedHours: Number(e.target.value) })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Lapsed grace (days)</label>
          <input className={INPUT_CLS} type="number" min="0" max="90" value={form.lapsedGraceDays}
            onChange={(e) => set({ lapsedGraceDays: Number(e.target.value) })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Look back (days)</label>
          <input className={INPUT_CLS} type="number" min="1" max="365" value={form.lookbackDays}
            onChange={(e) => set({ lookbackDays: Number(e.target.value) })} />
        </div>
      </div>

      <div className="mt-3">
        <label className={LABEL_CLS}>Router accounts that are meant to be there</label>
        <input className={INPUT_CLS} placeholder="default-trial, office-laptop, test"
          value={form.ignoredAccounts || ''}
          onChange={(e) => set({ ignoredAccounts: e.target.value })} />
        <p className="mt-1 text-xs text-on-surface-variant">
          Comma-separated. A staff device or test login lives here so it stops being reported every night.
        </p>
      </div>

      {msg && <p className={`mt-3 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}
      <div className="mt-3">
        <PrimaryButton onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save settings'}</PrimaryButton>
      </div>
    </div>
  )
}

function FindingRow({ finding, onClose, showWhy }) {
  const [asking, setAsking] = useState(null) // 'resolve' | 'ignore'
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const meta = kindOf(finding.kind)

  async function submit() {
    setBusy(true)
    try {
      await onClose(finding.id, asking, note)
    } finally {
      setBusy(false)
      setAsking(null)
      setNote('')
    }
  }

  return (
    <>
      <tr>
        <td>
          <span className={`px-2 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${SEVERITY_STYLES[finding.severity]}`}>
            {SEVERITY_WORDS[finding.severity] || finding.severity}
          </span>
        </td>
        <td>
          <p className="font-semibold flex items-center gap-1.5">
            <Icon name={meta.icon} className="text-[18px]! text-on-surface-variant" />
            {meta.label}
          </p>
          <p className="text-sm mt-0.5">{finding.detail}</p>
          {showWhy && <p className="text-xs text-on-surface-variant mt-0.5">{meta.why}</p>}
        </td>
        <td className="text-right tabular-nums whitespace-nowrap">
          {finding.amount ? fmtKES(finding.amount) : '—'}
        </td>
        <td className="text-xs whitespace-nowrap text-on-surface-variant">
          {relativeTime(finding.firstSeenAt)}
        </td>
        <td className="text-right whitespace-nowrap">
          <button onClick={() => setAsking('resolve')} disabled={busy}
            className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high">
            Dealt with
          </button>
          <button onClick={() => setAsking('ignore')} disabled={busy}
            className="ml-2 px-3 py-1.5 rounded-lg text-xs font-semibold cursor-pointer text-on-surface-variant hover:bg-surface-container-high">
            Expected
          </button>
        </td>
      </tr>
      {asking && (
        <tr className="bg-surface-container-low/50">
          <td colSpan={5}>
            <div className="flex flex-wrap items-center gap-2 py-1">
              <span className="text-xs text-on-surface-variant">
                {asking === 'resolve'
                  ? 'What did you do about it?'
                  : 'Why is this expected? It will stay closed unless it changes.'}
              </span>
              <input className={`${INPUT_CLS} max-w-sm`} value={note} autoFocus
                onChange={(e) => setNote(e.target.value)}
                placeholder={asking === 'resolve' ? 'Refunded, voucher re-issued…' : 'Office test login'} />
              <PrimaryButton onClick={submit} disabled={busy}>{busy ? 'Saving…' : 'Confirm'}</PrimaryButton>
              <button onClick={() => setAsking(null)}
                className="px-3 py-1.5 rounded-lg text-xs cursor-pointer text-on-surface-variant hover:bg-surface-container-high">
                Cancel
              </button>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

/**
 * Revenue Guard — money in, service out, and what the router is actually
 * letting online, reconciled against each other every night.
 */

/**
 * The answer to "what is this costing me", on one screen.
 *
 * <p>The findings list below is a diagnostic. This is the number an operator
 * evaluating Zidi actually wants, and it is the thing no competitor produces at
 * all: they bill, they do not tell you what is leaking.
 */
function FirstLook({ auth }) {
  const [report, setReport] = useState(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)

  async function run() {
    setBusy(true); setErr(null)
    try {
      setReport(await api('/admin/revenue-audit/first-look', { method: 'POST', auth }))
    } catch (e) {
      setErr(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="bg-surface-container rounded-xl p-4 space-y-4 print:bg-transparent">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold">What is leaking</h3>
          <p className="text-sm text-on-surface-variant">
            Runs every check now and adds up what it finds, in money.
          </p>
        </div>
        <div className="flex gap-2 print:hidden">
          <PrimaryButton onClick={run} disabled={busy}>
            {busy ? 'Checking…' : report ? 'Check again' : 'Check my books'}
          </PrimaryButton>
          {report && (
            <button onClick={() => window.print()}
              className="h-10 px-4 rounded-lg border border-outline-variant text-sm cursor-pointer">
              Print
            </button>
          )}
        </div>
      </div>

      {err && <p className="text-sm text-error">{err}</p>}

      {report && (
        <div className="space-y-4">
          <p className="text-lg font-semibold">{report.headline}</p>

          <div className="grid gap-3 sm:grid-cols-2">
            <div className="bg-surface rounded-lg p-3">
              <p className="text-xs text-on-surface-variant">Still collectable</p>
              <p className="text-2xl font-semibold text-secondary">{report.recoverableText}</p>
            </div>
            <div className="bg-surface rounded-lg p-3">
              {/* Kept apart on purpose: adding them together overstates the case. */}
              <p className="text-xs text-on-surface-variant">Already gone</p>
              <p className="text-2xl font-semibold">{report.alreadyGoneText}</p>
            </div>
          </div>

          {report.lines?.length > 0 && (
            <div className="overflow-x-auto">
              <table className="text-sm min-w-full">
                <thead className="text-on-surface-variant text-xs">
                  <tr>
                    <th className="text-left py-1.5">What</th>
                    <th className="text-right py-1.5">How many</th>
                    <th className="text-right py-1.5">Worth</th>
                    <th className="text-left py-1.5 pl-3">What to do</th>
                  </tr>
                </thead>
                <tbody>
                  {report.lines.map((l) => (
                    <tr key={l.kind} className="border-t border-outline-variant/40 align-top">
                      <td className="py-2">
                        <p className="font-medium">{l.label}</p>
                        <p className="text-xs text-on-surface-variant">{l.meaning}</p>
                      </td>
                      <td className="py-2 text-right">{l.count}</td>
                      <td className="py-2 text-right font-medium">{l.amountText}</td>
                      <td className="py-2 pl-3 text-on-surface-variant">{l.action}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Said out loud. A clean report from a sweep that saw half the system
              is the worst thing this screen could produce. */}
          <p className="text-xs text-on-surface-variant border-t border-outline-variant/40 pt-3">
            {report.coverage} Checked {report.ranAt ? new Date(report.ranAt).toLocaleString() : ''}.
          </p>
        </div>
      )}
    </div>
  )
}

export default function RevenueAuditPage({ auth }) {
  const [data, setData] = useState(null)
  const [running, setRunning] = useState(false)
  const [msg, setMsg] = useState(null)
  const [showSettings, setShowSettings] = useState(false)

  const load = () => api('/admin/revenue-audit', { auth }).then(setData).catch(() => setData({ open: [] }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function runNow() {
    setRunning(true)
    setMsg(null)
    try {
      const r = await api('/admin/revenue-audit/run', { method: 'POST', auth })
      const skipped = (r.skippedRouters || []).length
      setMsg({
        ok: true,
        text: `Checked. ${r.found} open issue${r.found === 1 ? '' : 's'}, ${r.newFindings} new, ${r.closed} cleared.`
          + (skipped ? ` Could not reach ${skipped} router — those checks were skipped.` : ''),
      })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setRunning(false)
    }
  }

  async function closeFinding(id, how, note) {
    await api(`/admin/revenue-audit/${id}/${how}`, { method: 'POST', auth, body: { note } })
    await load()
  }

  if (!data) return <Skeleton className="h-64" />

  const open = data.open || []
  const high = open.filter((f) => f.severity === 'HIGH').length

  return (
    <div>
      <PageHeader
        title="Revenue Guard"
        subtitle="Money received, service issued and what the router is letting online — reconciled nightly."
      >
        <div className="flex gap-2">
          <button onClick={() => setShowSettings(!showSettings)}
            className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high flex items-center gap-1.5">
            <Icon name="tune" className="text-[18px]!" /> Settings
          </button>
          <PrimaryButton onClick={runNow} disabled={running}>
            <Icon name="policy" /> {running ? 'Checking…' : 'Check now'}
          </PrimaryButton>
        </div>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Open Issues" value={open.length} hint={open.length ? 'waiting on you' : 'everything reconciles'}
          accent={open.length ? 'border-l-error' : ''} />
        <StatCard label="Serious" value={high} hint="money already lost or leaking"
          accent={high ? 'border-l-error' : ''} />
        <StatCard label="Value At Stake" value={fmtKES(data.atRisk || 0)} hint="across every open issue" />
        <StatCard label="Last Checked" value={data.lastRunAt ? relativeTime(data.lastRunAt) : 'Never'}
          hint={data.lastRunAt ? fmtDate(data.lastRunAt) : 'run it once to start'} />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      <div className="mb-4">
        <FirstLook auth={auth} />
      </div>

      {showSettings && data.settings && (
        <SettingsPanel auth={auth} settings={data.settings}
          onSaved={(s) => setData({ ...data, settings: s })} />
      )}

      {open.length === 0 ? (
        <div className="p-10 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
          <Icon name="verified_user" className="text-[40px]! text-secondary/60" />
          <p className="mt-2 font-semibold">Everything reconciles.</p>
          <p className="mt-1 text-sm text-on-surface-variant">
            Every payment produced service, every account on the router was sold by you, and nobody is
            online who shouldn't be.
          </p>
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr>
                  <th>Severity</th>
                  <th>What was found</th>
                  <th className="text-right">Value</th>
                  <th>Since</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {/* The "why this matters" line belongs to the check, not the
                    row — on the first of a run of twenty it explains, on the
                    other nineteen it is noise. */}
                {open.map((f, i) => (
                  <FindingRow key={f.id} finding={f} onClose={closeFinding}
                    showWhy={i === open.findIndex((x) => x.kind === f.kind)} />
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {(data.recentlyClosed || []).length > 0 && (
        <div className="mt-6">
          <h3 className="text-sm font-semibold mb-2">Recently closed</h3>
          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant divide-y divide-outline-variant">
            {data.recentlyClosed.map((f) => (
              <div key={f.id} className="px-4 py-2.5 flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <span className="text-xs font-semibold">{kindOf(f.kind).label}</span>
                <span className="text-xs text-on-surface-variant flex-1 min-w-40">{f.detail}</span>
                <span className="text-xs text-on-surface-variant whitespace-nowrap">
                  {f.status === 'IGNORED' ? 'Expected' : 'Dealt with'} by {f.resolvedBy}
                  {f.resolvedAt ? ` · ${fmtDate(f.resolvedAt)}` : ''}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      <p className="mt-4 text-xs text-on-surface-variant max-w-3xl">
        An issue that stops being detected closes itself, so this list only ever holds things that are
        still true. Checks that need a router are skipped entirely when it can't be reached — silence
        from an unreachable router is never taken as good news.
      </p>
    </div>
  )
}
