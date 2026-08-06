import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, PrimaryButton, fmtKES, INPUT_CLS, LABEL_CLS } from '../../components/ui.jsx'

export default function Branches({ auth }) {
  const [branches, setBranches] = useState(null)
  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ name: '', town: '', contactPhone: '' })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [deleteId, setDeleteId] = useState(null)

  const load = () => api('/admin/branches', { auth }).then(setBranches).catch(() => setBranches([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function create(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/branches', { method: 'POST', auth, body: form })
      setForm({ name: '', town: '', contactPhone: '' })
      setShowForm(false)
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  if (branches === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Branches" subtitle="Franchise locations, each with its own routers and customers.">
        <PrimaryButton onClick={() => setShowForm(!showForm)}>
          <Icon name="add_business" /> Add Branch
        </PrimaryButton>
      </PageHeader>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      {showForm && (
        <form onSubmit={create} className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] grid grid-cols-1 md:grid-cols-4 gap-4 items-end mb-6">
          <div>
            <label className={LABEL_CLS}>Branch Name</label>
            <input className={INPUT_CLS} required placeholder="e.g. Westlands" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Town</label>
            <input className={INPUT_CLS} placeholder="e.g. Nairobi" value={form.town} onChange={(e) => setForm({ ...form, town: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Contact Phone</label>
            <input className={INPUT_CLS} placeholder="2547XXXXXXXX" value={form.contactPhone} onChange={(e) => setForm({ ...form, contactPhone: e.target.value })} />
          </div>
          <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Create'}</PrimaryButton>
        </form>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {branches.map((b) => (
          <div key={b.id} className="bg-surface-container-lowest rounded-xl p-5 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-primary">
            <div className="flex justify-between items-start gap-3 mb-4">
              <div>
                <h3 className="text-lg font-semibold text-on-background">{b.name}</h3>
                {b.town && <p className="text-sm text-on-surface-variant">{b.town}</p>}
                {b.contactPhone && <p className="text-xs text-on-surface-variant mt-0.5">{b.contactPhone}</p>}
              </div>
              <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${b.active ? 'bg-secondary-container text-on-secondary-container' : 'bg-surface-variant text-on-surface-variant'}`}>
                {b.active ? 'Active' : 'Inactive'}
              </span>
            </div>
            <div className="grid grid-cols-3 gap-2 text-center mb-3">
              <div>
                <p className="text-2xl font-bold text-on-surface tabular-nums">{b.routers}</p>
                <p className="text-xs text-on-surface-variant">Routers</p>
              </div>
              <div>
                <p className={`text-2xl font-bold tabular-nums ${b.routersOnline === b.routers ? 'text-secondary' : 'text-error'}`}>{b.routersOnline}</p>
                <p className="text-xs text-on-surface-variant">Online</p>
              </div>
              <div>
                <p className="text-2xl font-bold text-on-surface tabular-nums">{b.subscribers}</p>
                <p className="text-xs text-on-surface-variant">Customers</p>
              </div>
            </div>
            <div className="border-t border-surface-variant pt-3 flex items-center justify-between">
              <div>
                <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">Monthly Revenue</p>
                <p className="text-lg font-semibold text-primary tabular-nums">{fmtKES(b.monthlyRevenue)}</p>
              </div>
              <button onClick={() => setDeleteId(deleteId === b.id ? null : b.id)} className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer" aria-label={`Remove ${b.name}`}>
                <Icon name="delete" className="text-[18px]!" />
              </button>
            </div>
            {deleteId === b.id && (
              <div className="flex items-center gap-2 mt-3">
                <span className="text-sm text-on-surface-variant">Remove <strong>{b.name}</strong>? Routers and customers stay, unassigned.</span>
                <button onClick={() => api(`/admin/branches/${b.id}`, { method: 'DELETE', auth }).then(() => { setDeleteId(null); load() })}
                  className="h-9 px-3 rounded-lg bg-error text-on-error text-sm font-semibold cursor-pointer shrink-0">Yes</button>
              </div>
            )}
          </div>
        ))}
        {branches.length === 0 && (
          <p className="text-on-surface-variant">No branches yet — everything is treated as head office until you add one.</p>
        )}
      </div>
    </div>
  )
}
