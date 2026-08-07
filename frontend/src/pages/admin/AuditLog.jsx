import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, fmtDate, fmtTime, relativeTime } from '../../components/ui.jsx'

const ACTION_ICONS = {
  router: 'router',
  voucher: 'confirmation_number',
  subscriber: 'lan',
  invoice: 'receipt_long',
  expense: 'payments',
  c2b: 'account_balance',
  branch: 'add_business',
  usage: 'data_usage',
}

export default function AuditLog({ auth }) {
  const [events, setEvents] = useState(null)
  const [query, setQuery] = useState('')

  useEffect(() => {
    const load = () => api('/admin/audit', { auth }).then(setEvents).catch(() => setEvents([]))
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [auth])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (events || []).filter((e) =>
      !q || e.actor.toLowerCase().includes(q) || e.action.toLowerCase().includes(q) || e.detail.toLowerCase().includes(q)
    )
  }, [events, query])

  if (events === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Audit Log" subtitle="Every change made in the system, and who made it.">
        <div className="relative w-full sm:w-72">
          <Icon name="search" className="absolute left-3 top-1/2 -translate-y-1/2 text-outline" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search actor, action, detail…"
            className="w-full h-12 bg-surface border border-outline-variant rounded-lg pl-10 pr-4 text-sm focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all" />
        </div>
      </PageHeader>

      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        <ul className="divide-y divide-surface-variant/30">
          {filtered.map((e) => {
            const group = e.action.split('.')[0]
            return (
              <li key={e.id} className="p-4 flex items-start gap-3">
                <span className="w-9 h-9 rounded-full bg-surface-container flex items-center justify-center shrink-0">
                  <Icon name={ACTION_ICONS[group] || 'history'} className="text-[18px]! text-on-surface-variant" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm text-on-surface">{e.detail}</p>
                  <p className="text-xs text-on-surface-variant mt-0.5">
                    <span className="font-semibold capitalize">{e.actor}</span> · <span className="font-mono">{e.action}</span> · {fmtDate(e.createdAt)}, {fmtTime(e.createdAt)}
                  </p>
                </div>
                <span className="text-xs text-on-surface-variant whitespace-nowrap shrink-0">{relativeTime(e.createdAt)}</span>
              </li>
            )
          })}
          {filtered.length === 0 && (
            <li className="p-6 text-sm text-on-surface-variant text-center">
              {events.length === 0 ? 'Nothing logged yet — actions appear here as you use the system.' : 'No entries match that search.'}
            </li>
          )}
        </ul>
      </div>
    </div>
  )
}
