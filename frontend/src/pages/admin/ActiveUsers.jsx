import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, StatCard } from '../../components/ui.jsx'

/** RouterOS byte counters can arrive as "123" or "123/456". */
function toMb(raw) {
  if (!raw) return 0
  const first = String(raw).includes('/') ? String(raw).split('/')[0] : String(raw)
  const bytes = Number(first) || 0
  return bytes / (1024 * 1024)
}

function formatData(mb) {
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`
  if (mb >= 1) return `${mb.toFixed(1)} MB`
  return mb > 0 ? '<1 MB' : '—'
}

export default function ActiveUsers({ auth }) {
  const [data, setData] = useState(null)
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState('all')
  const [msg, setMsg] = useState(null)
  const [confirmKick, setConfirmKick] = useState(null)

  const load = () => api('/admin/sessions', { auth }).then(setData).catch(() => setData({ sessions: [], total: 0 }))
  useEffect(() => {
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (data?.sessions || [])
      .filter((s) => kind === 'all' || s.kind === kind)
      .filter((s) =>
        !q ||
        s.user?.toLowerCase().includes(q) ||
        s.address?.includes(q) ||
        s.macAddress?.toLowerCase().includes(q) ||
        s.routerName?.toLowerCase().includes(q)
      )
  }, [data, query, kind])

  async function kick(s) {
    setMsg(null)
    try {
      const r = await api('/admin/sessions/disconnect', {
        method: 'POST',
        auth,
        body: { routerId: s.routerId, user: s.user, kind: s.kind },
      })
      setMsg({ ok: true, text: r.message })
      setConfirmKick(null)
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (data === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Active Users" subtitle="Everyone online right now, across every router. Refreshes every 15 seconds.">
        <div className="relative w-full sm:w-72">
          <Icon name="search" className="absolute left-3 top-1/2 -translate-y-1/2 text-outline" />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search user, IP, MAC, router…"
            className="w-full h-12 bg-surface border border-outline-variant rounded-lg pl-10 pr-4 text-sm focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all" />
        </div>
      </PageHeader>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <StatCard label="Online Now" value={data.total} accent="border-t-primary" />
        <StatCard label="Hotspot" value={data.hotspot} />
        <StatCard label="PPPoE" value={data.pppoe} />
        <StatCard label="Unreachable Routers" value={data.unreachableRouters}
          accent={data.unreachableRouters ? 'border-t-error' : ''} />
      </div>

      <div className="flex gap-2 mb-4 flex-wrap">
        {[['all', 'All'], ['hotspot', 'Hotspot'], ['pppoe', 'PPPoE']].map(([key, label]) => (
          <button key={key} onClick={() => setKind(key)}
            className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
              kind === key ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {label}
          </button>
        ))}
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      <div className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border border-surface-variant/30 overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[900px]">
            <thead>
              <tr>
                <th>User / Code</th>
                <th>Type</th>
                <th>Router</th>
                <th>IP Address</th>
                <th>MAC</th>
                <th>Uptime</th>
                <th>Data</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="text-on-surface">
              {rows.map((s, i) => {
                const id = `${s.routerId}-${s.kind}-${s.user}-${i}`
                const dataMb = toMb(s.bytesIn) + toMb(s.bytesOut)
                return (
                  <tr key={id}>
                    <td className="font-mono font-semibold">{s.user || '(unknown)'}</td>
                    <td>
                      <span className={`text-xs font-semibold px-2 py-0.5 rounded-full whitespace-nowrap ${
                        s.kind === 'pppoe' ? 'bg-primary-container/30 text-primary' : 'bg-secondary-container text-on-secondary-container'
                      }`}>
                        {s.kind === 'pppoe' ? 'PPPoE' : 'Hotspot'}
                      </span>
                    </td>
                    <td>{s.routerName}</td>
                    <td className="font-mono text-xs">{s.address || '—'}</td>
                    <td className="font-mono text-xs">{s.macAddress || '—'}</td>
                    <td className="whitespace-nowrap">{s.uptime || '—'}</td>
                    <td className="tabular-nums whitespace-nowrap">{formatData(dataMb)}</td>
                    <td className="text-right">
                      {confirmKick === id ? (
                        <div className="flex items-center gap-2 justify-end">
                          <span className="text-xs text-on-surface-variant">Disconnect?</span>
                          <button onClick={() => kick(s)} className="h-8 px-3 rounded-lg bg-error text-on-error text-xs font-semibold cursor-pointer">Yes</button>
                          <button onClick={() => setConfirmKick(null)} className="h-8 px-3 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer">No</button>
                        </div>
                      ) : (
                        <button onClick={() => setConfirmKick(id)}
                          className="px-3 py-1.5 rounded-lg border border-error text-error text-xs font-semibold hover:bg-error/5 transition-colors cursor-pointer">
                          Disconnect
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
              {rows.length === 0 && (
                <tr>
                  <td className="text-on-surface-variant" colSpan={8}>
                    {data.total === 0
                      ? 'Nobody is connected right now. If that seems wrong, check the Routers page — MikroTik integration must be enabled and reachable.'
                      : 'No sessions match that filter.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
