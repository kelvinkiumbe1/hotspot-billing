import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, AreaSparkline,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * How much each fibre customer is using, and what happens when they use too much.
 *
 * <p>The list is ordered heaviest-first and nothing here lets you sort it any
 * other way. "Who is eating the uplink this month" is the question this page
 * exists to answer; two thousand customers ordered by name is a page nobody opens.
 *
 * Two numbers are deliberately kept apart. The bar is usage against the
 * customer's own allowance, which is about them; the totals at the top are usage
 * against the network, which is about buying more capacity. Mixing them into one
 * percentage would answer neither.
 */

/** MB into something a person reads without counting digits. */
function size(mb) {
  if (mb === null || mb === undefined) return '—'
  if (mb < 1024) return `${Math.round(mb)} MB`
  const gb = mb / 1024
  if (gb < 1024) return `${gb.toFixed(gb < 10 ? 1 : 0)} GB`
  return `${(gb / 1024).toFixed(2)} TB`
}

const ACTIONS = [
  ['THROTTLE', 'Slow them down', 'They stay connected at a lower speed.'],
  ['BLOCK', 'Cut them off', 'The line is disabled until the month turns over.'],
  ['NOTIFY', 'Just tell them', 'Nothing changes on the router; they get a message.'],
]

/** Usage against one customer's own allowance. */
function CapBar({ usedMb, capMb }) {
  if (!capMb) {
    return <span className="text-xs text-on-surface-variant">No allowance</span>
  }
  const pct = Math.min(100, Math.round((usedMb / capMb) * 100))
  const over = usedMb >= capMb
  return (
    <div className="min-w-[7rem]">
      <div className="h-1.5 rounded-full bg-surface-container-high overflow-hidden">
        <div className={`h-full rounded-full ${over ? 'bg-error' : pct >= 80 ? 'bg-[#d97706]' : 'bg-secondary'}`}
          style={{ width: `${Math.max(2, pct)}%` }}></div>
      </div>
      <p className="text-xs text-on-surface-variant mt-1">{pct}% of {size(capMb)}</p>
    </div>
  )
}

/** One customer: their last 30 days, and their allowance. */
function CustomerUsage({ auth, row, onClose, onChanged }) {
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api(`/admin/subscribers/${row.subscriberId}/usage?days=30`, { auth })
    .then((d) => {
      setData(d)
      setForm({
        dataCapMb: d.cap?.capMb ? String(d.cap.capMb) : '',
        action: d.cap?.action || 'THROTTLE',
        fupRate: '',
      })
    })
    .catch((e) => setMsg({ ok: false, text: e.message }))

  useEffect(() => { load() }, [row.subscriberId]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/subscribers/${row.subscriberId}/fair-use`, {
        method: 'PATCH',
        auth,
        body: {
          // Blank clears the allowance. Sent as null rather than 0 so the
          // backend can tell "no cap" from "a cap of nothing".
          dataCapMb: form.dataCapMb.trim() ? Number(form.dataCapMb) : null,
          action: form.dataCapMb.trim() ? form.action : null,
          fupRate: form.fupRate.trim(),
        },
      })
      setMsg({ ok: true, text: r.message })
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  async function lift() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/subscribers/${row.subscriberId}/fair-use/lift`,
        { method: 'POST', auth })
      setMsg({ ok: r.ok, text: r.message })
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  const series = data?.series || []
  const peak = useMemo(() => series.reduce((m, d) => Math.max(m, d.totalMb), 0), [series])

  return (
    <div className="fixed inset-0 bg-black/40 flex items-start justify-center p-4 z-50 overflow-y-auto">
      <div className="bg-surface rounded-xl w-full max-w-2xl my-8 p-5 space-y-5">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-lg font-semibold truncate">{row.name}</p>
            <p className="text-xs font-mono text-on-surface-variant">{row.pppoeUsername}</p>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer"><Icon name="close" /></button>
        </div>

        {!data ? <Skeleton className="h-48" /> : (
          <>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              <StatCard label="This month" value={size(data.thisCycleMb)}
                hint={`since ${data.cycleStart}`} />
              <StatCard label="Busiest day" value={size(peak)} />
              <StatCard label="Allowance"
                value={data.cap ? size(data.cap.capMb) : 'None'}
                hint={data.cap ? `${size(data.cap.remainingMb)} left` : undefined} />
            </div>

            {data.cap?.appliedAt && (
              <div className="rounded-lg border border-[#d97706]/40 bg-[#fffbeb] p-3 flex items-start gap-2">
                <Icon name="warning" className="text-[18px]! text-[#b45309] mt-0.5" />
                <div className="flex-1 min-w-0">
                  <p className="text-sm text-[#78350f]">
                    Their allowance ran out and <strong>
                      {data.cap.action === 'BLOCK' ? 'the line was cut off'
                        : data.cap.action === 'THROTTLE' ? 'they were slowed down'
                          : 'they were sent a message'}
                    </strong>. This lifts on its own when the month turns over.
                  </p>
                  {data.cap.action !== 'NOTIFY' && (
                    <button type="button" disabled={busy} onClick={lift}
                      className="mt-2 px-3 py-1.5 rounded-lg border border-[#b45309] text-[#78350f] text-sm cursor-pointer hover:bg-[#fef3c7]">
                      Give them full speed back now
                    </button>
                  )}
                </div>
              </div>
            )}

            <div>
              <p className="text-sm font-semibold mb-2">Last 30 days</p>
              {/* Quiet days are real zeroes from the backend, not gaps -- a line
                  that was down for a week has to look like a week that was down. */}
              <AreaSparkline data={series.map((d) => d.totalMb)} height={72}
                labels={series.map((d) => d.day)} format={(v) => size(v)} />
            </div>

            <div className="rounded-lg border border-outline-variant p-4 space-y-3">
              <div>
                <p className="text-sm font-semibold">Monthly allowance</p>
                <p className="text-xs text-on-surface-variant mt-1">
                  Leave blank for unlimited, which is what most fibre customers should be.
                </p>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className={LABEL_CLS}>Allowance (MB a month)</label>
                  <input className={INPUT_CLS} type="number" value={form?.dataCapMb || ''}
                    placeholder="blank = unlimited"
                    onChange={(e) => setForm({ ...form, dataCapMb: e.target.value })} />
                </div>
                <div>
                  <label className={LABEL_CLS}>Once it runs out</label>
                  <select className={INPUT_CLS} value={form?.action || 'THROTTLE'}
                    disabled={!form?.dataCapMb?.trim()}
                    onChange={(e) => setForm({ ...form, action: e.target.value })}>
                    {ACTIONS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
                  </select>
                  <p className="text-xs text-on-surface-variant mt-1">
                    {ACTIONS.find((a) => a[0] === form?.action)?.[2]}
                  </p>
                </div>
              </div>
              {form?.action === 'THROTTLE' && form?.dataCapMb?.trim() && (
                <div>
                  <label className={LABEL_CLS}>Slow them to (blank = keep their normal speed)</label>
                  <input className={INPUT_CLS} value={form.fupRate} placeholder="2M/2M"
                    onChange={(e) => setForm({ ...form, fupRate: e.target.value })} />
                  {/* Not a detail. Somebody will press this on a customer who is
                      on a call and needs to know that is what happens. */}
                  <p className="text-xs text-[#b45309] mt-1 flex items-start gap-1.5">
                    <Icon name="info" className="text-[14px]! mt-0.5" />
                    Changing speed drops their connection for a few seconds — a router
                    only applies a new speed when the line redials.
                  </p>
                </div>
              )}
              <PrimaryButton disabled={busy} onClick={save}>
                {busy ? 'Saving…' : 'Save allowance'}
              </PrimaryButton>
            </div>

            {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
          </>
        )}
      </div>
    </div>
  )
}

export default function UsagePage({ auth }) {
  const [top, setTop] = useState(null)
  const [network, setNetwork] = useState(null)
  const [days, setDays] = useState(30)
  const [filter, setFilter] = useState('')
  const [open, setOpen] = useState(null)

  const load = () => {
    api('/admin/usage/top', { auth }).then(setTop).catch(() => setTop({ rows: [] }))
    api(`/admin/usage/network?days=${days}`, { auth })
      .then(setNetwork).catch(() => setNetwork({ series: [], totalMb: 0 }))
  }
  useEffect(() => { load() }, [auth, days]) // eslint-disable-line react-hooks/exhaustive-deps

  const shown = useMemo(() => {
    const rows = top?.rows || []
    const needle = filter.trim().toLowerCase()
    if (!needle) return rows
    return rows.filter((r) => [r.name, r.pppoeUsername]
      .some((v) => (v || '').toLowerCase().includes(needle)))
  }, [top, filter])

  if (!top || !network) return <Skeleton className="h-64" />

  const throttled = (top.rows || []).filter((r) => r.throttled).length
  const capped = (top.rows || []).filter((r) => r.capMb).length

  return (
    <>
      <PageHeader title="Data usage"
        subtitle="What each fibre and PPPoE customer has used, and who is over their allowance." />

      {top.rows.length === 0 ? (
        <div className="rounded-lg border border-outline-variant p-6 text-center">
          <Icon name="data_usage" className="text-[32px]! text-on-surface-variant" />
          <p className="text-base font-semibold mt-2">No usage recorded yet</p>
          <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
            Usage is counted from RADIUS accounting, so it appears here once customers
            start connecting through RADIUS. Nothing is backfilled &mdash; before today
            the numbers were never kept.
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <StatCard label={`Whole network, ${days} days`} value={size(network.totalMb)} />
            <StatCard label="This month, all customers"
              value={size((top.rows || []).reduce((s, r) => s + r.totalMb, 0))} />
            <StatCard label="On an allowance" value={capped} />
            <StatCard label="Over it right now" value={throttled}
              accent={throttled > 0 ? 'border-t-error' : undefined} />
          </div>

          <div className="rounded-lg border border-outline-variant p-4">
            <div className="flex items-center justify-between gap-3 mb-2">
              <p className="text-sm font-semibold">Whole network</p>
              <select className="text-sm bg-transparent cursor-pointer"
                value={days} onChange={(e) => setDays(Number(e.target.value))}>
                <option value={7}>7 days</option>
                <option value={30}>30 days</option>
                <option value={90}>90 days</option>
              </select>
            </div>
            <AreaSparkline data={network.series.map((d) => d.totalMb)} height={80}
              labels={network.series.map((d) => d.day)} format={(v) => size(v)} />
          </div>

          <input className={INPUT_CLS} value={filter} placeholder="customer name or PPPoE username"
            onChange={(e) => setFilter(e.target.value)} />

          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Customer</th>
                  <th className="text-left font-medium px-3 py-2">This month</th>
                  <th className="text-left font-medium px-3 py-2">Down / up</th>
                  <th className="text-left font-medium px-3 py-2">Allowance</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {shown.map((r) => (
                  <tr key={r.subscriberId} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2">
                      <p className="font-medium flex items-center gap-2">
                        {r.name}
                        {r.throttled && (
                          <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full bg-error-container text-on-error-container text-xs">
                            <Icon name="speed" className="text-[12px]!" /> limited
                          </span>
                        )}
                      </p>
                      <p className="text-xs font-mono text-on-surface-variant">{r.pppoeUsername}</p>
                    </td>
                    <td className="px-3 py-2 font-medium">{size(r.totalMb)}</td>
                    <td className="px-3 py-2 text-on-surface-variant text-xs">
                      {size(r.downMb)} / {size(r.upMb)}
                    </td>
                    <td className="px-3 py-2"><CapBar usedMb={r.totalMb} capMb={r.capMb} /></td>
                    <td className="px-3 py-2 text-right">
                      <button type="button" onClick={() => setOpen(r)}
                        className="text-primary text-sm cursor-pointer hover:underline">
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="text-xs text-on-surface-variant flex items-start gap-2">
            <Icon name="info" className="text-[16px]! mt-0.5" />
            Counted from RADIUS accounting and kept for just over a year. Allowances are
            checked every ten minutes, and a customer who goes over gets their full speed
            back automatically when their month turns over.
          </p>
        </div>
      )}

      {open && <CustomerUsage auth={auth} row={open}
        onClose={() => setOpen(null)} onChanged={load} />}
    </>
  )
}
