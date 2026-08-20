import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import { money } from '../../money.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime, fmtDate,
} from '../../components/ui.jsx'

const BAND_STYLE = {
  CRITICAL: 'bg-error text-on-error',
  AT_RISK: 'bg-warning text-black',
  WATCH: 'bg-surface-container-high text-on-surface',
  STEADY: 'bg-surface-container-high text-on-surface-variant',
}

const BAND_LABEL = {
  CRITICAL: 'ABOUT TO GO',
  AT_RISK: 'AT RISK',
  WATCH: 'WATCH',
  STEADY: 'STEADY',
}

function bps(value) {
  if (!value) return '—'
  if (value >= 1e9) return `${(value / 1e9).toFixed(2)} Gbps`
  if (value >= 1e6) return `${(value / 1e6).toFixed(1)} Mbps`
  return `${Math.round(value / 1e3)} kbps`
}

export default function Retention({ auth }) {
  const [data, setData] = useState(null)
  const [speed, setSpeed] = useState(null)
  const [msg, setMsg] = useState(null)
  const [busy, setBusy] = useState(false)
  const [tab, setTab] = useState('leaving')

  const load = () => api('/admin/retention', { auth }).then(setData).catch(() => setData({ customers: [] }))
  const loadSpeed = () => api('/admin/retention/speed?days=14', { auth })
    .then(setSpeed).catch(() => setSpeed({ customers: [], measured: false }))

  useEffect(() => { load(); loadSpeed() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function rescore() {
    setBusy(true)
    setMsg(null)
    try {
      const r = await api('/admin/retention/rescore', { method: 'POST', auth })
      setMsg({ ok: true, text: `Scored ${r.scored} customers.` })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
      load()
    }
  }

  async function acknowledge(row) {
    try {
      await api(`/admin/retention/${row.subscriberId}/acknowledge`, { method: 'POST', auth })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
    load()
  }

  if (data === null) return <Skeleton className="h-64" />

  const customers = data.customers || []
  const summary = data.summary || {}
  const outstanding = customers.filter((c) => !c.acknowledgedAt)

  return (
    <div>
      <PageHeader
        title="Keeping customers"
        subtitle="Dunning and win-back both start after the loss. This is the list of people you can still keep."
      >
        <PrimaryButton onClick={rescore} disabled={busy}>
          {busy ? 'Scoring…' : 'Score now'}
        </PrimaryButton>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <StatCard label="About to go" value={summary.critical ?? 0}
          accent={(summary.critical ?? 0) > 0 ? 'border-t-[color:var(--color-error)]' : undefined}
          hint="ring these today" />
        <StatCard label="At risk" value={summary.at_risk ?? 0} />
        <StatCard label="Worth watching" value={summary.watch ?? 0} />
        <StatCard label="Steady" value={summary.steady ?? 0}
          hint={summary.scoredAt ? `scored ${relativeTime(summary.scoredAt)}` : 'not scored yet'} />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      <div className="flex gap-2 mb-4">
        {[['leaving', `Leaving (${outstanding.length})`], ['speed', 'Not getting their speed']].map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
            className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
              tab === key
                ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant hover:bg-surface-container-high'
            }`}>
            {label}
          </button>
        ))}
      </div>

      {tab === 'leaving' && (
        customers.length === 0 ? (
          <div className="bg-surface-container-lowest rounded-lg p-8 border border-outline-variant/40 text-center">
            <Icon name="monitor_heart" className="text-[40px]! text-on-surface-variant" />
            <p className="mt-3 font-semibold">Nobody looks like leaving</p>
            <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
              Either the book is healthy or nothing has been scored yet — “Score now” settles which.
            </p>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {customers.map((c) => (
              <div key={c.subscriberId}
                className={`bg-surface-container-lowest rounded-lg p-4 border transition-colors ${
                  c.acknowledgedAt ? 'border-outline-variant/40 opacity-60'
                    : c.band === 'CRITICAL' ? 'border-error' : 'border-outline-variant/40'
                }`}>
                <div className="flex items-start justify-between gap-4 flex-wrap">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <h3 className="font-bold truncate">{c.name || 'Unnamed customer'}</h3>
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold tracking-wider ${BAND_STYLE[c.band]}`}>
                        {BAND_LABEL[c.band]} {c.score}
                      </span>
                      {c.worsening && (
                        <span className="px-2 py-0.5 rounded-full bg-error/10 text-error text-[10px] font-bold tracking-wider">
                          GETTING WORSE
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-on-surface-variant">
                      {c.phoneNumber}
                      {c.monthlyFee ? ` · ${money(c.monthlyFee)}/month` : ''}
                      {c.paidUntil ? ` · paid to ${fmtDate(c.paidUntil)}` : ''}
                    </p>
                  </div>
                  <div className="flex gap-2 shrink-0">
                    <a href={`tel:${c.phoneNumber}`}
                      className="px-3 py-1.5 rounded-md bg-primary text-on-primary text-xs font-semibold hover:opacity-90 transition-opacity">
                      Call
                    </a>
                    {!c.acknowledgedAt && (
                      <button onClick={() => acknowledge(c)}
                        className="px-3 py-1.5 rounded-md border border-outline-variant text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
                        Done
                      </button>
                    )}
                  </div>
                </div>

                {/* The reasons are the feature. A score alone tells an operator
                    nothing they can say when the customer picks up. */}
                {c.reasons.length > 0 && (
                  <ul className="mt-3 flex flex-col gap-1">
                    {c.reasons.map((reason, i) => (
                      <li key={i} className="text-sm flex items-start gap-2">
                        <span className={`mt-1.5 w-1.5 h-1.5 rounded-full shrink-0 ${
                          i === 0 ? 'bg-error' : 'bg-outline-variant'
                        }`}></span>
                        <span className={i === 0 ? 'text-on-surface' : 'text-on-surface-variant'}>{reason}</span>
                      </li>
                    ))}
                  </ul>
                )}

                {c.action && (
                  <p className="mt-3 pt-3 border-t border-outline-variant/50 text-sm font-medium text-primary">
                    {c.action}
                  </p>
                )}
                {c.acknowledgedAt && (
                  <p className="mt-2 text-xs text-on-surface-variant">
                    Followed up by {c.acknowledgedBy} {relativeTime(c.acknowledgedAt)}
                  </p>
                )}
              </div>
            ))}
          </div>
        )
      )}

      {tab === 'speed' && (
        speed === null ? <Skeleton className="h-32" /> : (
          <div>
            {!speed.measured && (
              <div className="mb-4 p-4 rounded-lg bg-warning/10 border border-warning/30">
                <p className="text-sm text-warning">
                  Nothing has been measured yet. Delivered speed is worked out from RADIUS
                  accounting, so this stays empty until RADIUS is switched on and routers have
                  been reporting for a day or two. An empty list here does not mean everyone is
                  getting their speed.
                </p>
              </div>
            )}
            {speed.customers.length === 0 ? (
              <div className="bg-surface-container-lowest rounded-lg p-8 border border-outline-variant/40 text-center">
                <p className="font-semibold">No shortfalls in the last fortnight</p>
                <p className="text-sm text-on-surface-variant mt-1 max-w-lg mx-auto">
                  A customer is only listed once several days show them well under the speed they
                  pay for — someone who never asks for much is not evidence of a fault.
                </p>
              </div>
            ) : (
              <div className="bg-surface-container-lowest rounded-lg border border-outline-variant/40 overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="text-xs uppercase tracking-wider text-on-surface-variant bg-surface-container-low">
                    <tr>
                      <th className="text-left px-4 py-2 font-semibold">Customer</th>
                      <th className="text-left px-2 py-2 font-semibold">Pays for</th>
                      <th className="text-left px-2 py-2 font-semibold">Best seen</th>
                      <th className="text-left px-2 py-2 font-semibold">Worst day</th>
                      <th className="text-left px-4 py-2 font-semibold">Bad days</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-[color:var(--color-outline-variant)]">
                    {speed.customers.map((c) => (
                      <tr key={c.subscriberId}>
                        <td className="px-4 py-2">
                          <p className="font-medium">{c.name}</p>
                          <p className="text-xs text-on-surface-variant">{c.phoneNumber}</p>
                        </td>
                        <td className="px-2 py-2 font-mono text-xs">{c.plan || '—'}</td>
                        <td className="px-2 py-2 whitespace-nowrap">{bps(c.bestBpsSeen)}</td>
                        <td className="px-2 py-2">
                          {c.worstPercent === null || c.worstPercent === undefined ? '—' : (
                            <span className="text-error font-semibold">{c.worstPercent}%</span>
                          )}
                        </td>
                        <td className="px-4 py-2">{c.badDays} of {c.daysMeasured}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )
      )}

      <p className="mt-6 text-xs text-on-surface-variant max-w-3xl">
        Scores are worked out overnight from signals already in the database — nothing is sent
        anywhere and no customer is contacted automatically. Every reason is shown so you can
        disagree with it; a score you cannot argue with is one you end up ignoring.
      </p>
    </div>
  )
}
