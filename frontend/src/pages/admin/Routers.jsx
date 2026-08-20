import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, Toggle, PageHeader, PrimaryButton, StatCard, fmtDate, fmtTime, relativeTime, INPUT_CLS, LABEL_CLS } from '../../components/ui.jsx'

function RouterModal({ auth, router, branches, onClose, onSaved }) {
  const editing = !!router
  const [form, setForm] = useState({
    name: router?.name || '',
    location: router?.location || '',
    host: router?.host || '',
    port: router?.port || 8728,
    username: router?.username || 'admin',
    password: '',
    useSsl: !!router?.useSsl,
    enabled: router ? router.enabled : true,
    defaultRouter: !!router?.defaultRouter,
    branchId: router?.branchId || '',
  })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api(editing ? `/admin/routers/${router.id}` : '/admin/routers', {
        method: editing ? 'PUT' : 'POST',
        auth,
        body: { ...form, port: Number(form.port) || 8728, branchId: form.branchId ? Number(form.branchId) : null },
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">{editing ? 'Edit Router' : 'Add Router'}</h3>
          <button onClick={onClose} className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4 max-h-[65vh] overflow-y-auto">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Router Name</label>
                <input className={INPUT_CLS} required placeholder="e.g. Westlands Site" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Location</label>
                <input className={INPUT_CLS} placeholder="e.g. Westlands, Nairobi" value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div className="col-span-2">
                <label className={LABEL_CLS}>IP Address / Host</label>
                <input className={INPUT_CLS} required placeholder="192.168.88.1" value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>API Port</label>
                <input className={INPUT_CLS} type="number" value={form.port} onChange={(e) => setForm({ ...form, port: e.target.value })} />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Username</label>
                <input className={INPUT_CLS} value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Password {editing && <span className="normal-case font-normal">(blank = keep)</span>}</label>
                <input className={INPUT_CLS} type="text" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
              </div>
            </div>
            {branches.length > 0 && (
              <div>
                <label className={LABEL_CLS}>Branch</label>
                <select className={INPUT_CLS} value={form.branchId} onChange={(e) => setForm({ ...form, branchId: e.target.value })}>
                  <option value="">Head office</option>
                  {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              </div>
            )}
            <div className="flex flex-wrap gap-4">
              {[
                ['useSsl', 'Use SSL/TLS'],
                ['enabled', 'Enabled'],
                ['defaultRouter', 'Default router'],
              ].map(([key, label]) => (
                <label key={key} className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors">
                  <input type="checkbox" checked={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.checked })} className="w-4 h-4 accent-primary" />
                  <span className="text-sm text-on-surface">{label}</span>
                </label>
              ))}
            </div>
            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose} className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors min-h-[40px] cursor-pointer">Cancel</button>
            <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : editing ? 'Save Changes' : 'Add Router'}</PrimaryButton>
          </div>
        </form>
      </div>
    </div>
  )
}

function SessionsPanel({ auth, router, onClose }) {
  const [sessions, setSessions] = useState(null)

  useEffect(() => {
    const load = () => api(`/admin/routers/${router.id}/sessions`, { auth }).then(setSessions).catch(() => setSessions([]))
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [router.id, auth])

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="flex-1 bg-on-background/30 backdrop-blur-[2px]" onClick={onClose}></div>
      <div className="w-full max-w-md bg-surface-container-lowest h-full shadow-[0_8px_24px_rgba(15,23,42,0.15)] flex flex-col">
        <div className="p-6 border-b border-outline-variant bg-surface-container-low flex justify-between items-start">
          <div>
            <h3 className="text-lg font-semibold text-on-surface">Live Sessions</h3>
            <p className="text-sm text-on-surface-variant mt-1">{router.name} · refreshes every 15s</p>
          </div>
          <button onClick={onClose} className="w-8 h-8 rounded-full hover:bg-surface-container flex items-center justify-center text-on-surface-variant cursor-pointer" aria-label="Close">
            <Icon name="close" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-4">
          {sessions === null ? <Skeleton className="h-32" /> : (
            <ul className="divide-y divide-[color:var(--color-outline-variant)]">
              {sessions.map((s, i) => (
                <li key={`${s.user}-${i}`} className="py-3 flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-on-surface font-mono">{s.user || '(unknown)'}</p>
                    <p className="text-xs text-on-surface-variant mt-0.5">
                      {s.address}{s.macAddress ? ` · ${s.macAddress}` : ''}
                    </p>
                    {s.uptime && <p className="text-xs text-on-surface-variant">up {s.uptime}</p>}
                  </div>
                  <span className={`text-xs font-semibold px-2 py-1 rounded-full whitespace-nowrap ${
                    s.kind === 'pppoe' ? 'bg-primary-container/30 text-primary' : 'bg-secondary-container text-on-secondary-container'
                  }`}>
                    {s.kind === 'pppoe' ? 'PPPoE' : 'Hotspot'}
                  </span>
                </li>
              ))}
              {sessions.length === 0 && <li className="py-6 text-sm text-on-surface-variant text-center">Nobody is connected right now.</li>}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}

export default function Routers({ auth }) {
  const [routers, setRouters] = useState(null)
  const [branches, setBranches] = useState([])
  const [modal, setModal] = useState(null) // { router } or {}
  const [sessionsFor, setSessionsFor] = useState(null)
  const [msg, setMsg] = useState(null)
  const [deleteId, setDeleteId] = useState(null)

  const load = () => api('/admin/routers', { auth }).then(setRouters).catch(() => setRouters([]))
  useEffect(() => {
    load()
    api('/admin/branches', { auth }).then(setBranches).catch(() => {})
    const t = setInterval(load, 60000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function test(r) {
    setMsg(null)
    try {
      const res = await api(`/admin/routers/${r.id}/test`, { method: 'POST', auth })
      setMsg({ ok: true, text: res.message })
    } catch (err) {
      setMsg({ ok: false, text: `${r.name}: ${err.message}` })
    }
    load()
  }

  if (routers === null) return <Skeleton className="h-64" />

  const online = routers.filter((r) => r.online).length
  const totalHotspot = routers.reduce((a, r) => a + (r.activeHotspotUsers || 0), 0)
  const totalPppoe = routers.reduce((a, r) => a + (r.activePppoeUsers || 0), 0)

  return (
    <div>
      <PageHeader title="Routers" subtitle="Every MikroTik site, with live status.">
        <PrimaryButton onClick={() => setModal({})}>
          <Icon name="add" /> Add Router
        </PrimaryButton>
      </PageHeader>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Routers" value={routers.length} accent="border-l-primary" />
        <StatCard label="Online" value={`${online}/${routers.length}`} accent={online === routers.length ? 'border-l-secondary' : 'border-l-error'} />
        <StatCard label="Hotspot Sessions" value={totalHotspot} />
        <StatCard label="PPPoE Sessions" value={totalPppoe} />
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {routers.map((r) => (
          <div key={r.id} className={`bg-surface-container-lowest rounded-lg p-4  border-l-4 ${r.online ? 'border-secondary' : r.enabled ? 'border-error' : 'border-outline-variant'}`}>
            <div className="flex justify-between items-start gap-3 mb-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <h3 className="text-lg font-semibold text-on-background">{r.name}</h3>
                  {r.defaultRouter && <span className="text-[10px] font-bold tracking-wider px-2 py-0.5 rounded-full bg-primary-container/30 text-primary">DEFAULT</span>}
                </div>
                <p className="text-sm text-on-surface-variant font-mono mt-0.5">{r.host}:{r.port}</p>
                {r.location && <p className="text-xs text-on-surface-variant">{r.location}</p>}
              </div>
              <span className={`text-xs font-semibold px-2.5 py-1 rounded-full whitespace-nowrap flex items-center gap-1.5 ${
                !r.enabled ? 'bg-surface-variant text-on-surface-variant'
                  : r.online ? 'bg-secondary-container text-on-secondary-container'
                  : 'bg-error-container text-on-error-container'
              }`}>
                <span className={`w-1.5 h-1.5 rounded-full ${!r.enabled ? 'bg-outline' : r.online ? 'bg-secondary animate-pulse' : 'bg-error'}`}></span>
                {!r.enabled ? 'Disabled' : r.online ? 'Online' : 'Offline'}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-3 text-sm mb-3">
              <div><span className="text-on-surface-variant">Uptime:</span> {r.uptime || '—'}</div>
              <div><span className="text-on-surface-variant">RouterOS:</span> {r.routerOsVersion || '—'}</div>
              <div><span className="text-on-surface-variant">Hotspot:</span> {r.activeHotspotUsers ?? '—'}</div>
              <div><span className="text-on-surface-variant">PPPoE:</span> {r.activePppoeUsers ?? '—'}</div>
            </div>
            <p className="text-xs text-on-surface-variant mb-3">
              {r.online ? `Last seen ${relativeTime(r.lastSeenAt)}` : r.lastError ? `Error: ${r.lastError}` : 'Not checked yet'}
              {r.lastCheckedAt ? ` · checked ${relativeTime(r.lastCheckedAt)}` : ''}
            </p>

            <div className="flex items-center gap-2 flex-wrap">
              <button onClick={() => test(r)} className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer">Test</button>
              <button onClick={() => setSessionsFor(r)} className="px-3 py-1.5 rounded-lg border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container transition-colors cursor-pointer">Sessions</button>
              <button onClick={() => setModal({ router: r })} className="px-3 py-1.5 rounded-lg border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container transition-colors cursor-pointer">Edit</button>
              <button onClick={() => setDeleteId(deleteId === r.id ? null : r.id)} className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer" aria-label={`Remove ${r.name}`}>
                <Icon name="delete" className="text-[18px]!" />
              </button>
            </div>
            {deleteId === r.id && (
              <div className="flex items-center gap-2 mt-3">
                <span className="text-sm text-on-surface-variant">Remove <strong className="text-on-surface">{r.name}</strong>?</span>
                <button onClick={() => api(`/admin/routers/${r.id}`, { method: 'DELETE', auth }).then(() => { setDeleteId(null); load() }).catch((e) => setMsg({ ok: false, text: e.message }))}
                  className="h-9 px-4 rounded-lg bg-error text-on-error text-sm font-semibold cursor-pointer">Yes, remove</button>
                <button onClick={() => setDeleteId(null)} className="h-9 px-4 rounded-lg border border-outline-variant text-on-surface text-sm font-semibold cursor-pointer">Cancel</button>
              </div>
            )}
          </div>
        ))}
        {routers.length === 0 && (
          <p className="text-on-surface-variant">No routers yet — add your first MikroTik.</p>
        )}
      </div>

      {modal && <RouterModal auth={auth} router={modal.router} branches={branches} onClose={() => setModal(null)} onSaved={() => { setModal(null); load() }} />}
      {sessionsFor && <SessionsPanel auth={auth} router={sessionsFor} onClose={() => setSessionsFor(null)} />}
    </div>
  )
}
