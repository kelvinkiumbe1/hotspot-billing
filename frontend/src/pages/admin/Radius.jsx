import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const VENDORS = [
  ['MIKROTIK', 'MikroTik'],
  ['UBIQUITI', 'Ubiquiti'],
  ['CAMBIUM', 'Cambium'],
  ['RUCKUS', 'Ruckus'],
  ['OMADA', 'TP-Link Omada'],
  ['CISCO', 'Cisco'],
  ['GENERIC', 'Something else'],
]

function bytes(n) {
  if (!n) return '0 B'
  if (n >= 1e9) return `${(n / 1e9).toFixed(2)} GB`
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)} MB`
  if (n >= 1e3) return `${Math.round(n / 1e3)} kB`
  return `${n} B`
}

function duration(seconds) {
  if (!seconds) return '—'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}

function ClientModal({ auth, client, onClose, onSaved }) {
  const editing = !!client
  const [form, setForm] = useState({
    name: client?.name || '',
    address: client?.address || '',
    sharedSecret: '',
    vendor: client?.vendor || 'MIKROTIK',
    coaPort: client?.coaPort || 3799,
    enabled: client ? client.enabled : true,
    notes: client?.notes || '',
  })
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api(editing ? `/admin/radius/clients/${client.id}` : '/admin/radius/clients', {
        method: editing ? 'PUT' : 'POST',
        auth,
        body: { ...form, coaPort: Number(form.coaPort) || 3799 },
      })
      onSaved()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5"
      onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-lg rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)]">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-center">
          <h3 className="text-2xl font-bold text-on-background">
            {editing ? 'Edit router' : 'Allow a router'}
          </h3>
          <button onClick={onClose} aria-label="Close"
            className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer">
            <Icon name="close" />
          </button>
        </div>
        <form onSubmit={save}>
          <div className="p-6 space-y-4 max-h-[65vh] overflow-y-auto">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Name</label>
                <input className={INPUT_CLS} required placeholder="e.g. Westlands Site"
                  value={form.name} onChange={(e) => set({ name: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Make</label>
                <select className={INPUT_CLS} value={form.vendor}
                  onChange={(e) => set({ vendor: e.target.value })}>
                  {VENDORS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
                </select>
              </div>
            </div>

            <div>
              <label className={LABEL_CLS}>Address it will ask from</label>
              <input className={INPUT_CLS} required placeholder="10.90.0.2 — or 10.90.0.0/24 for a range"
                value={form.address} onChange={(e) => set({ address: e.target.value })} />
              <p className="text-xs text-on-surface-variant mt-1">
                This is the router's own address as this server sees it, not the customer's.
                Anything not listed here is ignored entirely.
              </p>
            </div>

            <div>
              <label className={LABEL_CLS}>
                Shared secret {editing && <span className="normal-case font-normal">(blank = keep)</span>}
              </label>
              <input className={INPUT_CLS} type="password" value={form.sharedSecret}
                onChange={(e) => set({ sharedSecret: e.target.value })}
                placeholder={client?.hasSecret ? '••••••••' : 'the same string you type on the router'} />
              <p className="text-xs text-warning mt-1">
                Must match the router exactly. There is no way for either side to tell a mistyped
                secret from a wrong password, so it shows up as every login failing.
              </p>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Disconnect port</label>
                <input className={INPUT_CLS} type="number" value={form.coaPort}
                  onChange={(e) => set({ coaPort: e.target.value })} />
                <p className="text-xs text-on-surface-variant mt-1">3799 for most; some use 1700.</p>
              </div>
              <div className="flex items-end">
                <label className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors w-full">
                  <input type="checkbox" checked={form.enabled}
                    onChange={(e) => set({ enabled: e.target.checked })}
                    className="w-4 h-4 accent-primary" />
                  <span className="text-sm text-on-surface">Allowed to ask</span>
                </label>
              </div>
            </div>

            <div>
              <label className={LABEL_CLS}>Notes</label>
              <textarea className={`${INPUT_CLS} min-h-[60px]`} value={form.notes}
                onChange={(e) => set({ notes: e.target.value })} />
            </div>

            {error && <p className="text-sm text-error">{error}</p>}
          </div>
          <div className="p-6 border-t border-outline-variant/50 bg-surface-container/30 flex justify-end gap-3 rounded-b-xl">
            <button type="button" onClick={onClose}
              className="px-4 h-10 rounded-md text-sm font-semibold border border-primary text-primary hover:bg-primary/5 transition-colors cursor-pointer">
              Cancel
            </button>
            <PrimaryButton type="submit" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Allow it'}
            </PrimaryButton>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function Radius({ auth }) {
  const [data, setData] = useState(null)
  const [sessions, setSessions] = useState(null)
  const [modal, setModal] = useState(null)
  const [msg, setMsg] = useState(null)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState(null)

  const load = () => api('/admin/radius', { auth }).then((d) => {
    setData(d)
    setForm((f) => f || {
      enabled: d.enabled,
      authPort: d.authPort,
      acctPort: d.acctPort,
      interimSeconds: d.interimSeconds,
      disconnectEnabled: d.disconnectEnabled,
    })
  }).catch(() => setData({ clients: [] }))

  const loadSessions = () => api('/admin/radius/sessions', { auth })
    .then(setSessions).catch(() => setSessions({ live: [], recent: [] }))

  useEffect(() => {
    load()
    loadSessions()
    const t = setInterval(() => { load(); loadSessions() }, 30000)
    return () => clearInterval(t)
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function saveSettings(patch) {
    const next = { ...form, ...patch }
    setForm(next)
    setSaving(true)
    setMsg(null)
    try {
      const r = await api('/admin/radius/settings', {
        method: 'PUT', auth,
        body: { ...next, authPort: Number(next.authPort), acctPort: Number(next.acctPort),
          interimSeconds: Number(next.interimSeconds) },
      })
      setMsg({ ok: r.running || !next.enabled, text: r.message })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setSaving(false)
      load()
    }
  }

  async function removeClient(c) {
    try {
      await api(`/admin/radius/clients/${c.id}`, { method: 'DELETE', auth })
      setMsg({ ok: true, text: `${c.name} can no longer use RADIUS.` })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
    load()
  }

  async function cutOff(username) {
    setMsg(null)
    try {
      const r = await api('/admin/radius/sessions/disconnect', {
        method: 'POST', auth, body: { username },
      })
      setMsg({ ok: r.acknowledged === r.asked && r.asked > 0, text: r.message })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
    loadSessions()
  }

  if (data === null || form === null) return <Skeleton className="h-64" />

  const clients = data.clients || []
  const live = sessions?.live || []

  return (
    <div>
      <PageHeader
        title="RADIUS"
        subtitle="Let any make of router ask this system whether someone may log in — instead of copying every voucher onto every router."
      >
        <PrimaryButton onClick={() => setModal({})}>
          <Icon name="add" className="text-[18px]!" /> Allow a router
        </PrimaryButton>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
        <StatCard label="Server" value={data.running ? 'Listening' : data.enabled ? 'Stopped' : 'Off'}
          accent={data.enabled && !data.running ? 'border-t-[color:var(--color-error)]' : undefined}
          hint={data.running ? `ports ${data.authPort} / ${data.acctPort}` : undefined} />
        <StatCard label="Routers allowed" value={clients.length} />
        <StatCard label="Connected now" value={data.liveSessions} />
        <StatCard label="Accepted" value={clients.reduce((a, c) => a + (c.accepts || 0), 0)}
          hint={`${clients.reduce((a, c) => a + (c.rejects || 0), 0)} refused`} />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {data.note && (
        <div className="mb-6 p-4 rounded-lg bg-warning/10 border border-warning/30">
          <p className="text-sm text-warning">{data.note}</p>
        </div>
      )}

      <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 mb-6">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="min-w-0">
            <h3 className="font-bold">Answer logins over RADIUS</h3>
            <p className="text-sm text-on-surface-variant mt-1 max-w-2xl">
              While this is off, users are written onto each MikroTik through its API and nothing
              else works. With it on, routers ask at the moment of login — so a pass exists in one
              place, and any make of hardware can use it.
            </p>
          </div>
          <label className="flex items-center gap-3 shrink-0 cursor-pointer">
            <input type="checkbox" checked={form.enabled} disabled={saving}
              onChange={(e) => saveSettings({ enabled: e.target.checked })}
              className="w-5 h-5 accent-primary" />
            <span className="text-sm font-semibold">{form.enabled ? 'On' : 'Off'}</span>
          </label>
        </div>

        {form.enabled && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4 pt-4 border-t border-outline-variant/50">
            <div>
              <label className={LABEL_CLS}>Auth port</label>
              <input className={INPUT_CLS} type="number" value={form.authPort}
                onChange={(e) => setForm({ ...form, authPort: e.target.value })}
                onBlur={() => saveSettings({})} />
            </div>
            <div>
              <label className={LABEL_CLS}>Accounting port</label>
              <input className={INPUT_CLS} type="number" value={form.acctPort}
                onChange={(e) => setForm({ ...form, acctPort: e.target.value })}
                onBlur={() => saveSettings({})} />
            </div>
            <div>
              <label className={LABEL_CLS}>Update every</label>
              <select className={INPUT_CLS} value={form.interimSeconds}
                onChange={(e) => saveSettings({ interimSeconds: e.target.value })}>
                <option value={60}>1 minute</option>
                <option value={300}>5 minutes</option>
                <option value={600}>10 minutes</option>
                <option value={1800}>30 minutes</option>
              </select>
              <p className="text-xs text-on-surface-variant mt-1">
                How much billing is lost if a router dies.
              </p>
            </div>
            <div className="flex items-end">
              <label className="flex items-center gap-3 p-3 border border-outline-variant rounded-lg cursor-pointer hover:bg-surface-container-low transition-colors w-full">
                <input type="checkbox" checked={form.disconnectEnabled}
                  onChange={(e) => saveSettings({ disconnectEnabled: e.target.checked })}
                  className="w-4 h-4 accent-primary" />
                <span className="text-sm text-on-surface">Allow cutting sessions off</span>
              </label>
            </div>
          </div>
        )}
      </div>

      <h3 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-3">
        Routers allowed to ask
      </h3>
      {clients.length === 0 ? (
        <div className="bg-surface-container-lowest rounded-lg p-6 border border-outline-variant/40 text-center mb-6">
          <p className="text-sm text-on-surface-variant max-w-lg mx-auto">
            No router may ask yet. Anything not listed here is ignored without a reply — a server
            that answers strangers is a way to find out which voucher codes exist.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
          {clients.map((c) => (
            <div key={c.id}
              className={`bg-surface-container-lowest rounded-lg p-4 border ${
                c.enabled ? 'border-outline-variant/40' : 'border-outline-variant/40 opacity-60'
              }`}>
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h4 className="font-bold truncate">{c.name}</h4>
                    <span className="px-1.5 py-0.5 rounded bg-surface-container-high text-[10px] font-bold tracking-wider">
                      {(VENDORS.find((v) => v[0] === c.vendor) || ['', c.vendor])[1]}
                    </span>
                    {!c.enabled && (
                      <span className="px-2 py-0.5 rounded-full bg-surface-container-high text-[10px] font-bold tracking-wider">
                        BLOCKED
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-on-surface-variant font-mono">{c.address}</p>
                </div>
                <div className="flex gap-1 shrink-0">
                  <button onClick={() => setModal({ client: c })} title="Edit"
                    className="p-1.5 rounded-md hover:bg-surface-container-high cursor-pointer">
                    <Icon name="edit" className="text-[18px]!" />
                  </button>
                  <button onClick={() => removeClient(c)} title="Remove"
                    className="p-1.5 rounded-md hover:bg-error/10 text-on-surface-variant hover:text-error cursor-pointer">
                    <Icon name="delete" className="text-[18px]!" />
                  </button>
                </div>
              </div>
              <div className="flex items-center justify-between gap-3 mt-3 pt-3 border-t border-outline-variant/50 text-xs text-on-surface-variant">
                <span>{c.accepts} in · {c.rejects} refused</span>
                <span>{c.lastRequestAt ? `asked ${relativeTime(c.lastRequestAt)}` : 'never asked'}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      <h3 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-3">
        Connected now
      </h3>
      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant/40 overflow-hidden">
        {sessions === null ? <Skeleton className="h-24 m-4" /> : live.length === 0 ? (
          <p className="p-6 text-sm text-on-surface-variant text-center">Nobody is connected through RADIUS.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-xs uppercase tracking-wider text-on-surface-variant bg-surface-container-low">
                <tr>
                  <th className="text-left px-4 py-2 font-semibold">Who</th>
                  <th className="text-left px-2 py-2 font-semibold">Router</th>
                  <th className="text-left px-2 py-2 font-semibold">Address</th>
                  <th className="text-left px-2 py-2 font-semibold">On for</th>
                  <th className="text-left px-2 py-2 font-semibold">Used</th>
                  <th className="text-right px-4 py-2 font-semibold"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[color:var(--color-outline-variant)]">
                {live.map((s) => (
                  <tr key={s.id}>
                    <td className="px-4 py-2">
                      <p className="font-mono font-medium">{s.username}</p>
                      <p className="text-xs text-on-surface-variant">
                        {s.kind === 'PPPOE' ? 'PPPoE' : 'Hotspot'}
                        {s.callingStation ? ` · ${s.callingStation}` : ''}
                      </p>
                    </td>
                    <td className="px-2 py-2 font-mono text-xs">{s.nasAddress}</td>
                    <td className="px-2 py-2 font-mono text-xs">{s.framedIp || '—'}</td>
                    <td className="px-2 py-2 whitespace-nowrap">{duration(s.sessionSeconds)}</td>
                    <td className="px-2 py-2 whitespace-nowrap">{bytes(s.bytes)}</td>
                    <td className="px-4 py-2 text-right">
                      <button onClick={() => cutOff(s.username)}
                        className="px-3 py-1 rounded-md border border-outline-variant text-xs font-semibold hover:bg-error/10 hover:text-error hover:border-error transition-colors cursor-pointer">
                        Cut off
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <p className="mt-6 text-xs text-on-surface-variant max-w-3xl">
        Nothing here has been tried against real hardware yet. The packet format, the signatures and
        the billing arithmetic are tested; a live router accepting these answers is not. Point one
        site at it, watch a real login, and confirm the pass counts down before moving the rest.
      </p>

      {modal && (
        <ClientModal auth={auth} client={modal.client}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            setMsg({ ok: true, text: 'Saved.' })
            load()
          }} />
      )}
    </div>
  )
}
