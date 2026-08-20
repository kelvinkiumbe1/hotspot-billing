import { useEffect, useMemo, useState } from 'react'
import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, Cell,
  PieChart, Pie,
} from 'recharts'
import { api } from '../../api.js'
import { Icon, Skeleton, CardLabel, PageHeader, fmtKES, AreaSparkline } from '../../components/ui.jsx'

/** Human byte sizes: 0 B → 1.4 GB. Traffic figures are stored as raw bytes. */
function fmtBytes(n) {
  let v = Number(n || 0)
  if (v < 1024) return `${v} B`
  const units = ['KB', 'MB', 'GB', 'TB', 'PB']
  let i = -1
  do { v /= 1024; i++ } while (v >= 1024 && i < units.length - 1)
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`
}

/** Distinct hues for pie slices — brand amber first, then legible accents. */
const SLICE_COLORS = ['#fdbf2d', '#38bdf8', '#3da35d', '#c084fc', '#fb7185', '#2dd4bf', '#f59e0b', '#94a3b8']

/** Shared Recharts tooltip styling that reads on the dark console. */
const TOOLTIP_STYLE = {
  contentStyle: {
    background: 'var(--color-surface-container-high)',
    border: '1px solid var(--color-outline-variant)',
    borderRadius: 8,
    fontSize: 12,
  },
  labelStyle: { color: 'var(--color-on-surface)' },
  itemStyle: { color: 'var(--color-on-surface)' },
}

/** Chart accents. Amber is the brand; the other two are distinct hues that
 *  stay legible on the dark console and never collide with the hotspot/PPPoE
 *  legend used lower down the page. */
const CHART = {
  revenue: '#fdbf2d',
  transactions: '#38bdf8',
  signups: '#3da35d',
}

/** Prettier axis-free date for a tooltip: "9 Jul". */
function shortDate(iso) {
  const d = new Date(iso)
  return d.toLocaleDateString('en-KE', { day: 'numeric', month: 'short' })
}

/**
 * Headline metric: icon + title, the figure, and the shape of the period as
 * a gradient area chart. Modelled on the shadcn area-card the owner shared,
 * rebuilt in the house theme and fed a real daily series rather than the
 * demo's canned numbers.
 */
function MetricCard({ icon, title, value, sub, subTone = '', color, series, labels, format }) {
  return (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-xl p-5 flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <Icon name={icon} className="text-[20px]!" style={{ color }} />
        <span className="text-sm font-semibold text-on-surface">{title}</span>
      </div>
      <div className="flex items-end justify-between gap-3">
        <div className="min-w-0">
          <div className="font-mono text-2xl font-bold tracking-tight tabular-nums text-on-surface">{value}</div>
          {sub && <div className={`text-xs mt-1 ${subTone || 'text-on-surface-variant'}`}>{sub}</div>}
        </div>
        <div className="w-32 sm:w-40 shrink-0">
          <AreaSparkline data={series} labels={labels} color={color} height={56} format={format} />
        </div>
      </div>
    </div>
  )
}

const WINDOWS = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
  { days: 365, label: '1 year' },
]

/** Bare number, no currency, for counts sitting next to money figures. */
const fmtNum = (n) => new Intl.NumberFormat('en-KE').format(n ?? 0)

function Panel({ title, hint, children, className = '' }) {
  return (
    <div className={`bg-surface-container-lowest rounded-lg p-4 border border-outline-variant ${className}`}>
      <div className="flex items-baseline justify-between gap-3 mb-4">
        <CardLabel>{title}</CardLabel>
        {hint && <span className="text-xs text-on-surface-variant shrink-0">{hint}</span>}
      </div>
      {children}
    </div>
  )
}

function Empty({ children }) {
  return <p className="text-sm text-on-surface-variant py-6 text-center">{children}</p>
}

/** Daily revenue, hotspot stacked on top of PPPoE so the split is visible. */
function RevenueBars({ perDay }) {
  const peak = Math.max(1, ...perDay.map((d) => Number(d.total)))
  const anyMoney = perDay.some((d) => Number(d.total) > 0)
  // Long windows would render hair-thin bars, so label only the ends.
  const dense = perDay.length > 45

  if (!anyMoney) return <Empty>No payments in this period yet.</Empty>

  return (
    <div>
      <div className={`flex items-end h-48 ${dense ? 'gap-px' : 'gap-1'}`}>
        {perDay.map((d) => {
          const hotspot = Number(d.hotspot)
          const pppoe = Number(d.pppoe)
          const total = Number(d.total)
          return (
            <div
              key={d.date}
              className="flex-1 h-full flex flex-col justify-end min-w-0"
              title={`${d.date}\nHotspot ${fmtKES(hotspot)}\nPPPoE ${fmtKES(pppoe)}\nTotal ${fmtKES(total)}`}
            >
              <div className="w-full flex flex-col justify-end" style={{ height: `${(total / peak) * 100}%` }}>
                {pppoe > 0 && (
                  <div className="w-full bg-secondary rounded-t-sm" style={{ flexGrow: pppoe }} />
                )}
                {hotspot > 0 && (
                  <div className={`w-full bg-primary ${pppoe > 0 ? '' : 'rounded-t-sm'}`} style={{ flexGrow: hotspot }} />
                )}
              </div>
              {total === 0 && <div className="w-full h-[3px] bg-surface-container-high rounded-sm" />}
            </div>
          )
        })}
      </div>
      <div className="mt-2 flex justify-between text-[10px] text-on-surface-variant">
        <span>{perDay[0]?.date}</span>
        <span>Today</span>
      </div>
      <div className="mt-3 flex gap-4 text-xs">
        <span className="flex items-center gap-1.5"><i className="w-3 h-3 rounded-sm bg-primary inline-block" /> Hotspot</span>
        <span className="flex items-center gap-1.5"><i className="w-3 h-3 rounded-sm bg-secondary inline-block" /> PPPoE</span>
      </div>
    </div>
  )
}

/** Horizontal bars for a label/value list — plans, payment methods. */
function BarList({ rows, format = fmtKES, colour = 'bg-primary' }) {
  const peak = Math.max(1, ...rows.map((r) => Number(r.value)))
  if (rows.length === 0) return <Empty>Nothing to show for this period.</Empty>
  return (
    <ul className="space-y-3">
      {rows.map((r) => (
        <li key={r.label}>
          <div className="flex justify-between items-baseline gap-3 mb-1">
            <span className="text-sm truncate">{r.label}</span>
            <span className="text-sm font-semibold shrink-0">
              {format(r.value)}
              {r.note && <span className="text-on-surface-variant font-normal"> · {r.note}</span>}
            </span>
          </div>
          <div className="h-2 rounded-full bg-surface-container-high overflow-hidden">
            <div className={`h-full rounded-full ${colour}`} style={{ width: `${(Number(r.value) / peak) * 100}%` }} />
          </div>
        </li>
      ))}
    </ul>
  )
}

/** A weekday × hour grid, each cell shaded by how many distinct users were
 *  online then. Recharts has no heatmap, so this is a hand-built grid in the
 *  house style — the one chart the library doesn't cover. */
function TrafficHeatmap({ cells }) {
  const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
  const grid = useMemo(() => {
    const g = Array.from({ length: 7 }, () => new Array(24).fill(0))
    for (const c of cells) g[c.weekday][c.hour] = c.users
    return g
  }, [cells])
  const peak = Math.max(1, ...cells.map((c) => c.users))

  return (
    <div className="overflow-x-auto">
      <div className="min-w-[560px]">
        <div className="flex">
          <div className="w-9 shrink-0" />
          <div className="flex-1 grid gap-0.5" style={{ gridTemplateColumns: 'repeat(24, minmax(0, 1fr))' }}>
            {Array.from({ length: 24 }, (_, h) => (
              <div key={h} className="text-[9px] text-on-surface-variant text-center">
                {h % 6 === 0 ? `${String(h).padStart(2, '0')}` : ''}
              </div>
            ))}
          </div>
        </div>
        {grid.map((row, wd) => (
          <div key={wd} className="flex items-center mt-0.5">
            <div className="w-9 shrink-0 text-[11px] text-on-surface-variant">{WEEKDAYS[wd]}</div>
            <div className="flex-1 grid gap-0.5" style={{ gridTemplateColumns: 'repeat(24, minmax(0, 1fr))' }}>
              {row.map((users, h) => (
                <div
                  key={h}
                  className="aspect-square rounded-[2px]"
                  title={`${WEEKDAYS[wd]} ${String(h).padStart(2, '0')}:00 — ${users} user${users === 1 ? '' : 's'}`}
                  style={{
                    background: users === 0
                      ? 'var(--color-surface-container-high)'
                      : `color-mix(in srgb, var(--color-primary) ${20 + (users / peak) * 80}%, transparent)`,
                  }}
                />
              ))}
            </div>
          </div>
        ))}
      </div>
      <p className="mt-3 text-xs text-on-surface-variant">Darker cells = more people online. Times are local.</p>
    </div>
  )
}

/** Data-usage reports. Fetched separately from the money overview because it
 *  aggregates a different table and only fills in as traffic is captured. */
function TrafficSection({ auth, days }) {
  const [t, setT] = useState(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setT(null)
    setFailed(false)
    api(`/admin/analytics/traffic?days=${days}`, { auth }).then(setT).catch(() => setFailed(true))
  }, [auth, days])

  if (failed) return null
  if (!t) return <Skeleton className="h-72 mt-6" />

  if (!t.hasData) {
    return (
      <Panel title="Network traffic" className="mt-6">
        <div className="py-8 text-center">
          <Icon name="insights" className="text-[40px]! text-on-surface-variant/40" />
          <p className="mt-2 text-on-surface-variant">No traffic recorded yet for this period.</p>
          <p className="text-xs text-on-surface-variant mt-1 max-w-md mx-auto">
            Usage is captured live from your routers going forward — these charts fill in as
            customers browse. There is no history to backfill from before capture began.
          </p>
        </div>
      </Panel>
    )
  }

  const { up, down } = t.uploadDownload
  const upDownData = [
    { name: 'Download', value: Number(down) },
    { name: 'Upload', value: Number(up) },
  ]
  const planData = t.usageByPlan.map((p) => ({ name: p.plan, value: Number(p.bytes) }))
  const duCurrent = Number(t.dataUsage.current)
  const duPrev = Number(t.dataUsage.previous)
  const duChange = t.dataUsage.changePercent

  return (
    <div className="mt-6">
      <div className="flex items-center gap-2 mb-4">
        <Icon name="router" className="text-primary" />
        <h2 className="text-lg font-semibold text-on-surface">Network traffic</h2>
      </div>

      {/* Data total this period vs the previous one + up/down split */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <Panel title="Data usage" hint="Current vs previous period">
          <div className="flex items-end justify-between gap-3">
            <div>
              <p className="font-mono text-3xl font-bold tabular-nums text-on-surface">{fmtBytes(duCurrent)}</p>
              <p className={`text-xs mt-1 ${
                duChange === null || duChange === undefined ? 'text-on-surface-variant'
                  : Number(duChange) >= 0 ? 'text-secondary' : 'text-error'
              }`}>
                {duChange === null || duChange === undefined
                  ? 'No previous period to compare'
                  : `${Number(duChange) >= 0 ? '+' : ''}${duChange}% vs ${fmtBytes(duPrev)} before`}
              </p>
            </div>
            <div className="w-32 h-16">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={[{ name: 'Prev', v: duPrev }, { name: 'Now', v: duCurrent }]}>
                  <Bar dataKey="v" radius={[3, 3, 0, 0]}>
                    <Cell fill="var(--color-surface-container-high)" />
                    <Cell fill="var(--color-primary)" />
                  </Bar>
                  <Tooltip {...TOOLTIP_STYLE} formatter={(v) => fmtBytes(v)} cursor={{ fill: 'transparent' }} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </Panel>

        <Panel title="Upload vs download">
          <div className="flex items-center gap-4">
            <div className="w-28 h-28 shrink-0">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={upDownData} dataKey="value" innerRadius={30} outerRadius={52} paddingAngle={2}>
                    <Cell fill="var(--color-primary)" />
                    <Cell fill="#38bdf8" />
                  </Pie>
                  <Tooltip {...TOOLTIP_STYLE} formatter={(v) => fmtBytes(v)} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <dl className="flex-1 space-y-2">
              <div className="flex justify-between items-baseline gap-2">
                <dt className="text-sm flex items-center gap-1.5"><i className="w-2.5 h-2.5 rounded-sm bg-primary inline-block" /> Download</dt>
                <dd className="font-semibold tabular-nums">{fmtBytes(down)}</dd>
              </div>
              <div className="flex justify-between items-baseline gap-2">
                <dt className="text-sm flex items-center gap-1.5"><i className="w-2.5 h-2.5 rounded-sm inline-block" style={{ background: '#38bdf8' }} /> Upload</dt>
                <dd className="font-semibold tabular-nums">{fmtBytes(up)}</dd>
              </div>
            </dl>
          </div>
        </Panel>
      </div>

      {/* Per-router performance */}
      <Panel title="MikroTik performance" hint="Per router, this period" className="mb-6">
        {t.perRouter.length === 0 ? (
          <Empty>No per-router traffic yet.</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-on-surface-variant border-b border-outline-variant">
                  <th className="pb-2 font-medium">Router</th>
                  <th className="pb-2 font-medium text-right">Users</th>
                  <th className="pb-2 font-medium text-right">Data</th>
                  <th className="pb-2 font-medium text-right">Revenue</th>
                </tr>
              </thead>
              <tbody>
                {t.perRouter.map((r) => (
                  <tr key={r.router} className="border-b border-outline-variant/50 last:border-0">
                    <td className="py-2.5 font-medium">{r.router}</td>
                    <td className="py-2.5 text-right tabular-nums">{fmtNum(r.users)}</td>
                    <td className="py-2.5 text-right tabular-nums font-mono">{fmtBytes(r.bytes)}</td>
                    <td className="py-2.5 text-right tabular-nums">{fmtKES(r.revenue)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      {/* Usage by plan + top talkers */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <Panel title="Usage by plan" hint="Share of total data">
          {planData.length === 0 ? (
            <Empty>No plan-attributed traffic yet.</Empty>
          ) : (
            <div className="flex items-center gap-4">
              <div className="w-32 h-32 shrink-0">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={planData} dataKey="value" nameKey="name" innerRadius={34} outerRadius={60} paddingAngle={2}>
                      {planData.map((_, i) => <Cell key={i} fill={SLICE_COLORS[i % SLICE_COLORS.length]} />)}
                    </Pie>
                    <Tooltip {...TOOLTIP_STYLE} formatter={(v) => fmtBytes(v)} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <ul className="flex-1 space-y-1.5 min-w-0">
                {planData.slice(0, 6).map((p, i) => (
                  <li key={p.name} className="flex justify-between items-baseline gap-2 text-sm">
                    <span className="flex items-center gap-1.5 min-w-0 truncate">
                      <i className="w-2.5 h-2.5 rounded-sm inline-block shrink-0" style={{ background: SLICE_COLORS[i % SLICE_COLORS.length] }} />
                      <span className="truncate">{p.name}</span>
                    </span>
                    <span className="font-semibold tabular-nums shrink-0">{fmtBytes(p.value)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Panel>

        <Panel title="Top talkers" hint="Heaviest users">
          {t.topTalkers.length === 0 ? (
            <Empty>No usage yet.</Empty>
          ) : (
            <BarList
              rows={t.topTalkers.map((u) => ({ label: u.user, value: Number(u.bytes) }))}
              format={fmtBytes}
            />
          )}
        </Panel>
      </div>

      {/* Weekday × hour heatmap */}
      <Panel title="Traffic heatmap" hint="Distinct users by day & hour">
        {t.heatmap.length === 0 ? <Empty>Not enough data yet.</Empty> : <TrafficHeatmap cells={t.heatmap} />}
      </Panel>
    </div>
  )
}

export default function AnalyticsPage({ auth }) {
  const [days, setDays] = useState(30)
  const [data, setData] = useState(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setData(null)
    setFailed(false)
    api(`/admin/analytics?days=${days}`, { auth }).then(setData).catch(() => setFailed(true))
  }, [auth, days])

  const busiestHour = useMemo(() => {
    if (!data) return null
    const top = [...data.byHour].sort((a, b) => b.count - a.count)[0]
    return top && top.count > 0 ? top : null
  }, [data])

  const hourPeak = data ? Math.max(1, ...data.byHour.map((h) => h.count)) : 1
  const weekdayPeak = data ? Math.max(1, ...data.byWeekday.map((d) => d.count)) : 1

  const windowPicker = (
    <div className="flex rounded-full border border-outline-variant overflow-hidden">
      {WINDOWS.map((w) => (
        <button
          key={w.days}
          onClick={() => setDays(w.days)}
          aria-pressed={days === w.days}
          className={`px-4 py-1.5 text-sm cursor-pointer transition-colors ${
            days === w.days
              ? 'bg-inverse-surface text-primary-fixed font-semibold'
              : 'text-on-surface-variant hover:bg-surface-container-high'
          }`}
        >
          {w.label}
        </button>
      ))}
    </div>
  )

  if (failed) {
    return (
      <div>
        <PageHeader title="Analytics" subtitle="How the business is doing across hotspot and PPPoE." />
        <div className="p-8 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
          <Icon name="cloud_off" className="text-[40px]! text-on-surface-variant/40" />
          <p className="mt-2 text-on-surface-variant">Could not reach the server. Is the backend running?</p>
        </div>
      </div>
    )
  }

  if (!data) {
    return (
      <div>
        <PageHeader title="Analytics" subtitle="How the business is doing across hotspot and PPPoE.">
          {windowPicker}
        </PageHeader>
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
          {[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-28" />)}
        </div>
        <Skeleton className="h-72" />
      </div>
    )
  }

  const { revenue, subscribers, vouchers, leads } = data
  const change = revenue.changePercent
  const net = Number(revenue.net)

  return (
    <div>
      <PageHeader
        title="Analytics"
        subtitle={`How the business is doing, ${data.from} to ${data.to}.`}
      >
        {windowPicker}
      </PageHeader>

      {/* Headline metrics — each an area chart over a real daily series. */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
        <MetricCard
          icon="payments"
          title="Revenue"
          color={CHART.revenue}
          value={fmtKES(revenue.total)}
          series={data.perDay.map((d) => Number(d.total))}
          labels={data.perDay.map((d) => shortDate(d.date))}
          format={fmtKES}
          sub={
            change === null || change === undefined
              ? `No baseline · last ${data.windowDays} days`
              : `${Number(change) >= 0 ? '+' : ''}${change}% vs previous ${data.windowDays} days`
          }
          subTone={
            change === null || change === undefined
              ? ''
              : Number(change) >= 0 ? 'text-secondary' : 'text-error'
          }
        />
        <MetricCard
          icon="receipt_long"
          title="Transactions"
          color={CHART.transactions}
          value={fmtNum(data.perDay.reduce((s, d) => s + Number(d.count), 0))}
          series={data.perDay.map((d) => Number(d.count))}
          labels={data.perDay.map((d) => shortDate(d.date))}
          format={(v) => `${fmtNum(v)} txn${v === 1 ? '' : 's'}`}
          sub={`Avg ${(data.perDay.reduce((s, d) => s + Number(d.count), 0) / data.windowDays).toFixed(1)} per day`}
        />
        <MetricCard
          icon="group_add"
          title="New Subscribers"
          color={CHART.signups}
          value={fmtNum(subscribers.newInWindow)}
          series={data.perDay.map((d) => Number(d.signups))}
          labels={data.perDay.map((d) => shortDate(d.date))}
          format={(v) => `${fmtNum(v)} new`}
          sub={`${fmtNum(subscribers.active)} active now`}
        />
      </div>

      {/* Supporting figures that have no daily series of their own. */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-2.5 mb-6">
        {/* Both sides shown, rather than leading with one figure that may be
            zero while the other carries the whole business. */}
        <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
          <CardLabel>Where It Came From</CardLabel>
          <dl className="mt-2 space-y-1.5">
            <div className="flex justify-between items-baseline gap-2">
              <dt className="text-sm flex items-center gap-1.5">
                <i className="w-2.5 h-2.5 rounded-sm bg-primary inline-block" /> Hotspot
              </dt>
              <dd className="text-lg font-bold tabular-nums">{fmtKES(revenue.hotspot)}</dd>
            </div>
            <div className="flex justify-between items-baseline gap-2">
              <dt className="text-sm flex items-center gap-1.5">
                <i className="w-2.5 h-2.5 rounded-sm bg-secondary inline-block" /> PPPoE
              </dt>
              <dd className="text-lg font-bold tabular-nums">{fmtKES(revenue.pppoe)}</dd>
            </div>
          </dl>
          {Number(revenue.total) > 0 && (
            <div className="mt-3 h-2 rounded-full bg-secondary overflow-hidden flex">
              <div className="h-full bg-primary" style={{ width: `${(Number(revenue.hotspot) / Number(revenue.total)) * 100}%` }} />
            </div>
          )}
        </div>

        <div className={`bg-surface-container-lowest rounded-lg p-4 border border-outline-variant ${net < 0 ? 'border-l-2 border-l-error' : ''}`}>
          <CardLabel>After Expenses</CardLabel>
          <p className={`text-[22px] leading-tight font-semibold mt-0.5 tabular-nums ${net < 0 ? 'text-error' : ''}`}>
            {fmtKES(net)}
          </p>
          <p className="text-xs text-on-surface-variant mt-2">
            {fmtKES(revenue.expenses)} spent{net < 0 ? ' — running at a loss' : ''}
          </p>
        </div>

        <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
          <CardLabel>Monthly Recurring</CardLabel>
          <p className="text-[22px] leading-tight font-semibold mt-0.5 tabular-nums">{fmtKES(subscribers.monthlyRecurring)}</p>
          <p className="text-xs text-on-surface-variant mt-2">
            {fmtNum(subscribers.active)} active · {fmtKES(subscribers.arpu)} each
          </p>
        </div>
      </div>

      <Panel
        title={`Revenue per day, last ${data.windowDays} days`}
        hint={busiestHour ? `Busiest hour: ${String(busiestHour.hour).padStart(2, '0')}:00` : null}
        className="mb-6"
      >
        <RevenueBars perDay={data.perDay} />
      </Panel>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <Panel title="Best selling plans" hint="Hotspot, by revenue">
          <BarList
            rows={data.topPlans.map((p) => ({
              label: p.plan,
              value: p.revenue,
              note: `${fmtNum(p.sales)} ${p.sales === 1 ? 'sale' : 'sales'}`,
            }))}
          />
        </Panel>

        <Panel title="How customers paid">
          <BarList
            rows={data.methodMix.map((m) => ({ label: m.label, value: m.amount }))}
            colour="bg-secondary"
          />
        </Panel>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
        <Panel title="Top customers" hint="Highest-paying, this period">
          {(!data.topCustomers || data.topCustomers.length === 0) ? (
            <Empty>No customer spend in this period yet.</Empty>
          ) : (
            <BarList
              rows={data.topCustomers.map((c) => ({
                label: c.name,
                value: c.spent,
                note: c.name === c.phone ? null : c.phone,
              }))}
            />
          )}
        </Panel>

        <Panel title="Busiest days">
          <div className="space-y-2">
            {data.byWeekday.map((d) => (
              <div key={d.day} className="flex items-center gap-3">
                <span className="text-sm w-9 shrink-0">{d.day}</span>
                <div className="flex-1 h-2 rounded-full bg-surface-container-high overflow-hidden">
                  <div className="h-full rounded-full bg-primary" style={{ width: `${(d.count / weekdayPeak) * 100}%` }} />
                </div>
                <span className="text-sm font-semibold w-8 text-right tabular-nums">{d.count}</span>
              </div>
            ))}
          </div>
        </Panel>
      </div>

      <Panel title="When people buy" hint="Hotspot purchases by hour" className="mb-6">
        {busiestHour === null ? (
          <Empty>No hotspot purchases in this period yet.</Empty>
        ) : (
          <>
            <div className="flex items-end gap-1 h-36">
              {data.byHour.map((h) => (
                <div key={h.hour} className="flex-1 h-full flex flex-col justify-end items-center gap-1"
                  title={`${String(h.hour).padStart(2, '0')}:00 — ${h.count} purchase${h.count === 1 ? '' : 's'}`}>
                  <div
                    className={`w-full rounded-t-sm ${h.count === hourPeak ? 'bg-primary' : h.count ? 'bg-primary/50' : 'bg-surface-container-high'}`}
                    style={{ height: `${Math.max(3, (h.count / hourPeak) * 100)}%` }}
                  />
                </div>
              ))}
            </div>
            <div className="mt-2 flex justify-between text-[10px] text-on-surface-variant">
              <span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>23:00</span>
            </div>
            <p className="mt-3 text-xs text-on-surface-variant">
              Peak is {String(busiestHour.hour).padStart(2, '0')}:00 — the best window for a promo push.
            </p>
          </>
        )}
      </Panel>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <Panel title="Voucher sell-through" hint={`${fmtNum(vouchers.stock)} in stock`}>
          <p className="text-3xl font-bold tracking-tight tabular-nums">{vouchers.sellThroughPercent}%</p>
          <p className="text-sm text-on-surface-variant mt-1">
            {fmtNum(vouchers.used)} of {fmtNum(vouchers.issued)} issued this period were used
          </p>
          <div className="mt-4 h-2 rounded-full bg-surface-container-high overflow-hidden">
            <div className="h-full rounded-full bg-primary" style={{ width: `${Math.min(100, Number(vouchers.sellThroughPercent))}%` }} />
          </div>
          {Number(vouchers.issued) > 0 && Number(vouchers.sellThroughPercent) < 40 && (
            <p className="mt-3 text-xs text-warning">
              {(100 - Number(vouchers.sellThroughPercent)).toFixed(1)}% of what you printed this
              period is still unused — worth slowing down the next batch.
            </p>
          )}
        </Panel>

        {/* Face value of vouchers redeemed, not cash banked — an agent's
            cash sale never passes through M-Pesa, so the two would not
            reconcile if this used settled payments. */}
        <Panel title="Agents vs direct" hint="Voucher value redeemed">
          <BarList
            rows={[
              { label: 'Sold through agents', value: vouchers.agentValue },
              { label: 'Sold directly', value: vouchers.directValue },
            ]}
          />
          <p className="mt-4 text-xs text-on-surface-variant">
            {fmtKES(vouchers.redeemedValue)} of voucher value used in this period.
          </p>
        </Panel>

        <Panel title="Lead conversion">
          <p className="text-3xl font-bold tracking-tight tabular-nums">{leads.conversionPercent}%</p>
          <p className="text-sm text-on-surface-variant mt-1">
            {fmtNum(leads.converted)} of {fmtNum(leads.created)} new leads signed up
          </p>
          <dl className="mt-4 space-y-2 text-sm">
            <div className="flex justify-between">
              <dt className="text-on-surface-variant">New subscribers</dt>
              <dd className="font-semibold tabular-nums">{fmtNum(subscribers.newInWindow)}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-on-surface-variant">Suspended for non-payment</dt>
              <dd className={`font-semibold tabular-nums ${Number(subscribers.suspended) > 0 ? 'text-error' : ''}`}>
                {fmtNum(subscribers.suspended)}
              </dd>
            </div>
          </dl>
        </Panel>
      </div>

      <TrafficSection auth={auth} days={days} />
    </div>
  )
}
