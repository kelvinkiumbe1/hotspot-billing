import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * The router fleet: who is on which box, moving them, and looking inside one.
 *
 * Three jobs on one page because they are the same job -- the reason to look
 * inside a router is usually to decide whether to move somebody off it.
 *
 * Everything under "Inside a router" reads live hardware, so nothing here polls.
 * A page that re-read six routers every thirty seconds would be a load nobody
 * asked for on the boxes least able to carry it.
 */

const TABS = [
  ['fleet', 'Fleet', 'lan'],
  ['move', 'Move customers', 'move_up'],
  ['replace', 'Replace a router', 'swap_horiz'],
  ['inside', 'Inside a router', 'terminal'],
]

function Problems({ result }) {
  if (!result) return null
  return (
    <div className={`rounded-lg border p-3 space-y-2 ${
      result.ok ? 'border-secondary' : 'border-warning'}`}>
      <p className="text-sm">{result.message}</p>
      {result.problems?.length > 0 && (
        <>
          {/* Named, so what failed is a list to retry rather than a discrepancy
              somebody finds weeks later. */}
          <p className="text-xs font-semibold text-on-surface-variant">
            These did not move:
          </p>
          <ul className="text-xs space-y-0.5">
            {result.problems.map((p, i) => (
              <li key={i} className="text-warning font-mono">{p}</li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

function MoveCustomers({ auth, routers, onDone }) {
  const [customers, setCustomers] = useState(null)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [picked, setPicked] = useState([])
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  useEffect(() => {
    api('/admin/subscribers/lookup', { auth }).then((d) => setCustomers(d || [])).catch(() => setCustomers([]))
  }, [auth])

  const onFrom = useMemo(() => {
    if (!customers) return []
    if (!from) return customers
    return customers.filter((c) => String(c.routerId ?? '') === from)
  }, [customers, from])

  async function go() {
    setBusy(true); setResult(null)
    try {
      const r = await api('/admin/fleet/transfer', {
        method: 'POST', auth,
        body: { subscriberIds: picked.map(Number), toRouterId: Number(to) },
      })
      setResult(r)
      setPicked([])
      onDone()
    } catch (e) {
      setResult({ ok: false, message: e.message })
    } finally { setBusy(false) }
  }

  if (!customers) return <Skeleton className="h-48" />

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-outline-variant p-4 space-y-3">
        <div>
          <p className="text-sm font-semibold">Move customers to another router</p>
          <p className="text-xs text-on-surface-variant mt-1">
            Each customer is set up on the new router before being removed from the old
            one, so a router that stops answering leaves them working where they were.
            Anyone who does not make it is listed rather than silently skipped.
          </p>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Currently on</label>
            <select className={INPUT_CLS} value={from}
              onChange={(e) => { setFrom(e.target.value); setPicked([]) }}>
              <option value="">Any router</option>
              {routers.map((r) => (
                <option key={r.id} value={String(r.id)}>{r.name} ({r.customers})</option>
              ))}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Move to</label>
            <select className={INPUT_CLS} value={to} onChange={(e) => setTo(e.target.value)}>
              <option value="">Choose a router</option>
              {routers.filter((r) => r.enabled && String(r.id) !== from).map((r) => (
                <option key={r.id} value={String(r.id)}>{r.name}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="flex items-center justify-between gap-3">
          <p className="text-xs text-on-surface-variant">
            {picked.length} of {onFrom.length} selected
          </p>
          <div className="flex gap-2">
            <button type="button" onClick={() => setPicked(onFrom.map((c) => String(c.id)))}
              className="text-xs text-primary cursor-pointer hover:underline">Select all</button>
            <button type="button" onClick={() => setPicked([])}
              className="text-xs text-on-surface-variant cursor-pointer hover:underline">Clear</button>
          </div>
        </div>

        <div className="max-h-72 overflow-y-auto rounded-lg border border-outline-variant divide-y divide-outline-variant/40">
          {onFrom.map((c) => (
            <label key={c.id}
              className="flex items-center gap-3 px-3 py-2 cursor-pointer hover:bg-surface-container-low">
              <input type="checkbox" checked={picked.includes(String(c.id))}
                onChange={(e) => setPicked(e.target.checked
                  ? [...picked, String(c.id)]
                  : picked.filter((x) => x !== String(c.id)))} />
              <span className="flex-1 min-w-0">
                <span className="text-sm font-medium block truncate">{c.fullName}</span>
                <span className="text-xs font-mono text-on-surface-variant">{c.pppoeUsername}</span>
              </span>
            </label>
          ))}
          {onFrom.length === 0 && (
            <p className="text-sm text-on-surface-variant px-3 py-4">Nobody on that router.</p>
          )}
        </div>

        <PrimaryButton disabled={busy || !to || picked.length === 0} onClick={go}>
          {busy ? 'Moving…' : `Move ${picked.length} customer(s)`}
        </PrimaryButton>
      </div>
      <Problems result={result} />
    </div>
  )
}

function ReplaceRouter({ auth, routers, onDone }) {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [copySettings, setCopySettings] = useState(true)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  const source = routers.find((r) => String(r.id) === from)

  async function go() {
    if (!window.confirm(`Move all ${source?.customers ?? 0} customer(s) off `
      + `${source?.name} and switch it off? Each one is set up on the new router first, `
      + `so nobody is left without a connection.`)) return
    setBusy(true); setResult(null)
    try {
      const r = await api('/admin/fleet/replace', {
        method: 'POST', auth,
        body: { fromRouterId: Number(from), toRouterId: Number(to), copySettings },
      })
      setResult(r)
      onDone()
    } catch (e) {
      setResult({ ok: false, message: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-outline-variant p-4 space-y-3">
        <div>
          <p className="text-sm font-semibold">Replace a router</p>
          <p className="text-xs text-on-surface-variant mt-1">
            For the box that has died or is being swapped out. Everybody on it is moved to
            the replacement, and the old one is switched off &mdash; not deleted, because its
            configuration backup and history are the only record of what the site looked
            like.
          </p>
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Router being replaced</label>
            <select className={INPUT_CLS} value={from} onChange={(e) => setFrom(e.target.value)}>
              <option value="">Choose one</option>
              {routers.map((r) => (
                <option key={r.id} value={String(r.id)}>{r.name} ({r.customers} customers)</option>
              ))}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Its replacement</label>
            <select className={INPUT_CLS} value={to} onChange={(e) => setTo(e.target.value)}>
              <option value="">Choose one</option>
              {routers.filter((r) => r.enabled && String(r.id) !== from).map((r) => (
                <option key={r.id} value={String(r.id)}>{r.name}</option>
              ))}
            </select>
          </div>
        </div>
        <label className="flex items-start gap-3 cursor-pointer">
          <input type="checkbox" className="mt-1" checked={copySettings}
            onChange={(e) => setCopySettings(e.target.checked)} />
          <span className="text-sm">
            Move the site&rsquo;s details too
            <span className="block text-xs text-on-surface-variant">
              Branch, location and uplink capacity. These describe the place rather than
              the hardware, and capacity planning quietly loses a site without them.
            </span>
          </span>
        </label>
        <PrimaryButton disabled={busy || !from || !to} onClick={go}>
          {busy ? 'Moving everybody…' : 'Replace it'}
        </PrimaryButton>
      </div>
      <Problems result={result} />
    </div>
  )
}

function InsideRouter({ auth, routers }) {
  const [routerId, setRouterId] = useState('')
  const [view, setView] = useState('logs')
  const [data, setData] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [topics, setTopics] = useState('')
  const [editing, setEditing] = useState(null)

  async function load(which) {
    if (!routerId) return
    setBusy(true); setMsg(null); setData(null); setView(which)
    try {
      const path = which === 'logs'
        ? `/admin/fleet/${routerId}/logs?limit=200${topics ? `&topics=${encodeURIComponent(topics)}` : ''}`
        : `/admin/fleet/${routerId}/${which}`
      setData(await api(path, { auth }))
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  async function saveWireless() {
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/fleet/${routerId}/wireless`, {
        method: 'POST', auth,
        body: {
          api: editing.api, name: editing.name,
          ssid: editing.ssid || null,
          password: editing.password || null,
        },
      })
      // The server's own sentence: it knows which security profile the password
      // landed on, and whether other networks share it.
      setMsg({ ok: true, text: r.message })
      setEditing(null)
      load('wireless')
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[12rem]">
          <label className={LABEL_CLS}>Router</label>
          <select className={INPUT_CLS} value={routerId}
            onChange={(e) => { setRouterId(e.target.value); setData(null) }}>
            <option value="">Choose one</option>
            {routers.filter((r) => r.enabled).map((r) => (
              <option key={r.id} value={String(r.id)}>{r.name}</option>
            ))}
          </select>
        </div>
        {['logs', 'interfaces', 'wireless', 'bridges'].map((w) => (
          <button key={w} type="button" disabled={!routerId || busy} onClick={() => load(w)}
            className={`px-3 py-2 rounded-lg border text-sm cursor-pointer disabled:opacity-50 ${
              view === w && data ? 'border-primary text-primary' : 'border-outline-variant hover:bg-surface-container-high'}`}>
            {w === 'logs' ? 'Log' : w === 'interfaces' ? 'Interfaces'
              : w === 'wireless' ? 'WiFi' : 'Bridges'}
          </button>
        ))}
        {view === 'logs' && (
          <div className="min-w-[10rem]">
            <label className={LABEL_CLS}>Only topics containing</label>
            <input className={INPUT_CLS} value={topics} placeholder="pppoe, dhcp, error"
              onChange={(e) => setTopics(e.target.value)} />
          </div>
        )}
      </div>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
      {busy && <Skeleton className="h-40" />}

      {data?.logs && (
        <div className="rounded-lg border border-outline-variant overflow-hidden">
          {/* Newest first, because the reason is nearly always the last thing
              that happened. */}
          <table className="w-full text-xs">
            <thead className="bg-surface-container-low text-on-surface-variant">
              <tr>
                <th className="text-left font-medium px-3 py-2">Time</th>
                <th className="text-left font-medium px-3 py-2">Topics</th>
                <th className="text-left font-medium px-3 py-2">Message</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/40 font-mono">
              {data.logs.map((l, i) => (
                <tr key={i} className={/error|fail|critical/i.test(l.topics) ? 'text-error' : ''}>
                  <td className="px-3 py-1.5 whitespace-nowrap">{l.time}</td>
                  <td className="px-3 py-1.5 whitespace-nowrap text-on-surface-variant">{l.topics}</td>
                  <td className="px-3 py-1.5 break-all">{l.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.logs.length === 0 && (
            <p className="text-sm text-on-surface-variant p-4">
              Nothing in the log matched. RouterOS keeps this in memory, so a router that
              rebooted recently has very little.
            </p>
          )}
        </div>
      )}

      {data?.interfaces && (
        <div className="overflow-x-auto rounded-lg border border-outline-variant">
          <table className="w-full text-sm">
            <thead className="bg-surface-container-low text-on-surface-variant">
              <tr>
                <th className="text-left font-medium px-3 py-2">Interface</th>
                <th className="text-left font-medium px-3 py-2">Type</th>
                <th className="text-left font-medium px-3 py-2">State</th>
                <th className="text-right font-medium px-3 py-2">In / out</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/40">
              {data.interfaces.map((i) => (
                <tr key={i.name}>
                  <td className="px-3 py-2 font-mono">{i.name}</td>
                  <td className="px-3 py-2 text-on-surface-variant">{i.type}</td>
                  <td className="px-3 py-2">
                    {i.disabled === 'true'
                      ? <span className="text-on-surface-variant">disabled</span>
                      : i.running === 'true'
                        ? <span className="text-secondary">up</span>
                        : <span className="text-error">down</span>}
                  </td>
                  <td className="px-3 py-2 text-right font-mono text-xs text-on-surface-variant">
                    {Math.round(Number(i.rxByte) / 1048576)} / {Math.round(Number(i.txByte) / 1048576)} MB
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data?.wireless && (
        <div className="space-y-3">
          {data.wireless.length === 0 && (
            <p className="text-sm text-on-surface-variant">
              No wireless interfaces. This board may be wired-only, or running a RouterOS
              build with neither menu.
            </p>
          )}
          {data.wireless.map((w) => (
            <div key={w.api + w.name} className="rounded-lg border border-outline-variant p-3 space-y-2">
              <div className="flex items-start justify-between gap-3 flex-wrap">
                <div>
                  <p className="font-medium">
                    {w.ssid || <span className="italic text-on-surface-variant">no name set</span>}
                  </p>
                  <p className="text-xs font-mono text-on-surface-variant">
                    {w.name} · {w.api} {w.band ? `· ${w.band}` : ''}
                    {w.disabled === 'true' ? ' · disabled' : ''}
                  </p>
                </div>
                <button type="button"
                  onClick={() => setEditing({ api: w.api, name: w.name, ssid: w.ssid, password: '' })}
                  className="text-primary text-sm cursor-pointer hover:underline">Change</button>
              </div>
              {editing?.name === w.name && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 items-end">
                  <div>
                    <label className={LABEL_CLS}>Network name</label>
                    <input className={INPUT_CLS} value={editing.ssid || ''}
                      onChange={(e) => setEditing({ ...editing, ssid: e.target.value })} />
                  </div>
                  <div>
                    <label className={LABEL_CLS}>New password (blank = keep)</label>
                    <input className={INPUT_CLS} value={editing.password}
                      onChange={(e) => setEditing({ ...editing, password: e.target.value })} />
                  </div>
                  <div className="sm:col-span-2 flex gap-2">
                    <PrimaryButton disabled={busy} onClick={saveWireless}>Save</PrimaryButton>
                    <button type="button" onClick={() => setEditing(null)}
                      className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer">
                      Cancel
                    </button>
                  </div>
                  <p className="sm:col-span-2 text-xs text-warning flex items-start gap-1.5">
                    <Icon name="warning" className="text-[14px]! mt-0.5" />
                    On classic RouterOS the password lives on a security profile that other
                    networks may share. The reply says which one was changed.
                  </p>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {data?.bridges && (
        <div className="space-y-3">
          {data.bridges.map((b) => (
            <div key={b.name} className="rounded-lg border border-outline-variant p-3">
              <p className="font-medium font-mono text-sm">{b.name}</p>
              <p className="text-xs text-on-surface-variant">
                {b.protocolMode || 'no STP'} · vlan filtering {b.vlanFiltering}
                {b.disabled === 'true' ? ' · disabled' : ''}
              </p>
              <div className="mt-2 flex flex-wrap gap-1">
                {(data.ports || []).filter((p) => p.bridge === b.name).map((p) => (
                  <span key={p.interfaceName}
                    className="px-2 py-0.5 rounded-full bg-surface-container-high text-xs font-mono">
                    {p.interfaceName}{p.pvid && p.pvid !== '1' ? ` (pvid ${p.pvid})` : ''}
                  </span>
                ))}
              </div>
            </div>
          ))}
          {data.bridges.length === 0 && (
            <p className="text-sm text-on-surface-variant">No bridges on this router.</p>
          )}
        </div>
      )}
    </div>
  )
}

export default function FleetPage({ auth }) {
  const [data, setData] = useState(null)
  const [tab, setTab] = useState('fleet')

  const load = () => api('/admin/fleet', { auth }).then(setData).catch(() => setData({ routers: [], moves: [] }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return <Skeleton className="h-64" />
  const routers = data.routers || []

  return (
    <>
      <PageHeader title="Router fleet"
        subtitle="Who is on which router, moving them, and what the router itself says." />

      <div className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="Routers" value={routers.length} />
          <StatCard label="Switched on" value={routers.filter((r) => r.enabled).length} />
          <StatCard label="Customers placed"
            value={routers.reduce((s, r) => s + r.customers, 0)} />
          <StatCard label="Voucher roaming" value={data.roamingEnabled ? 'on' : 'off'} />
        </div>

        <div className="flex gap-1 border-b border-outline-variant overflow-x-auto">
          {TABS.map(([k, label, icon]) => (
            <button key={k} type="button" onClick={() => setTab(k)}
              className={`px-4 py-2 text-sm font-medium cursor-pointer flex items-center gap-2 border-b-2 whitespace-nowrap ${
                tab === k ? 'border-primary text-primary'
                  : 'border-transparent text-on-surface-variant hover:text-on-surface'}`}>
              <Icon name={icon} className="text-[18px]!" />{label}
            </button>
          ))}
        </div>

        {tab === 'fleet' && (
          <div className="space-y-4">
            <div className="overflow-x-auto rounded-lg border border-outline-variant">
              <table className="w-full text-sm">
                <thead className="bg-surface-container-low text-on-surface-variant">
                  <tr>
                    <th className="text-left font-medium px-3 py-2">Router</th>
                    <th className="text-right font-medium px-3 py-2">Customers</th>
                    <th className="text-left font-medium px-3 py-2">Uplink</th>
                    <th className="text-left font-medium px-3 py-2">State</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-outline-variant/40">
                  {routers.map((r) => (
                    <tr key={r.id} className="hover:bg-surface-container-low">
                      <td className="px-3 py-2">
                        <p className="font-medium">
                          {r.name}
                          {r.defaultRouter && (
                            <span className="ml-2 text-xs px-1.5 py-0.5 rounded-full bg-primary-fixed/40 text-primary">
                              default
                            </span>
                          )}
                        </p>
                        <p className="text-xs font-mono text-on-surface-variant">{r.host}</p>
                      </td>
                      <td className="px-3 py-2 text-right font-mono tabular-nums">{r.customers}</td>
                      <td className="px-3 py-2 text-on-surface-variant">
                        {r.capacityMbps ? `${r.capacityMbps} Mbps` : '—'}
                      </td>
                      <td className="px-3 py-2">
                        {!r.enabled ? <span className="text-on-surface-variant">switched off</span>
                          : r.online ? <span className="text-secondary">online</span>
                            : <span className="text-warning">not answering</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {data.moves?.length > 0 && (
              <details>
                <summary className="cursor-pointer text-sm font-semibold">
                  Recent moves
                </summary>
                <ul className="mt-2 divide-y divide-outline-variant/40 text-sm">
                  {data.moves.map((m) => (
                    <li key={m.id} className="py-2">
                      <p>
                        {m.kind === 'REPLACE' ? 'Replaced a router' : 'Moved customers'}
                        {' — '}{m.moved} moved
                        {m.failed > 0 && <span className="text-warning">, {m.failed} failed</span>}
                      </p>
                      <p className="text-xs text-on-surface-variant">
                        {relativeTime(m.startedAt)} · {m.startedBy}
                      </p>
                      {m.detail && (
                        <pre className="text-xs font-mono text-warning mt-1 whitespace-pre-wrap">{m.detail}</pre>
                      )}
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        )}

        {tab === 'move' && <MoveCustomers auth={auth} routers={routers} onDone={load} />}
        {tab === 'replace' && <ReplaceRouter auth={auth} routers={routers} onDone={load} />}
        {tab === 'inside' && <InsideRouter auth={auth} routers={routers} />}
      </div>
    </>
  )
}
