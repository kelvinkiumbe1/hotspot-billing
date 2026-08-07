import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, CardLabel, PageHeader, fmtKES, AreaSparkline } from '../../components/ui.jsx'

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
          <div className="text-2xl font-bold tracking-tight tabular-nums text-on-surface">{value}</div>
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

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mb-6">
        <Panel title="When people buy" hint="Hotspot purchases by hour" className="lg:col-span-2">
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
            <p className="mt-3 text-xs text-[#b45309]">
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
    </div>
  )
}
