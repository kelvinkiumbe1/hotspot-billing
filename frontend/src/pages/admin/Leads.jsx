import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, PrimaryButton, fmtKES, fmtDate, relativeTime, INPUT_CLS, LABEL_CLS } from '../../components/ui.jsx'

const SOURCES = ['WALK_IN', 'REFERRAL', 'ONLINE', 'PHONE_CALL', 'FIELD_VISIT', 'OTHER']
const PIPELINE = ['NEW', 'CONTACTED', 'QUOTED', 'CONVERTED', 'LOST']

const STATUS_STYLES = {
  NEW: 'bg-primary-container/25 text-primary',
  CONTACTED: 'bg-surface-container-high text-on-surface-variant',
  QUOTED: 'bg-warning/10 text-warning border border-warning/20',
  CONVERTED: 'bg-secondary-container text-on-secondary-container',
  LOST: 'bg-error-container text-on-error-container',
}

const pretty = (s) => s.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())

function ConvertForm({ lead, onCancel, onConvert }) {
  const [form, setForm] = useState({
    pppoeUsername: lead.fullName.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 12),
    pppoePassword: '',
    mbps: '',
    monthlyFee: lead.quotedFee || '',
    initialMonths: 1,
    initialMethod: 'CASH',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onConvert({
        pppoeUsername: form.pppoeUsername,
        pppoePassword: form.pppoePassword,
        bandwidth: form.mbps ? `${form.mbps}M/${form.mbps}M` : null,
        monthlyFee: Number(form.monthlyFee),
        initialMonths: Number(form.initialMonths),
        initialMethod: form.initialMethod,
      })
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mt-3 p-4 rounded-lg bg-surface-container-low/60 border border-outline-variant grid grid-cols-2 md:grid-cols-3 gap-3 items-end">
      <div>
        <label className={LABEL_CLS}>PPPoE Username</label>
        <input className={INPUT_CLS} required value={form.pppoeUsername} onChange={(e) => setForm({ ...form, pppoeUsername: e.target.value })} />
      </div>
      <div>
        <label className={LABEL_CLS}>PPPoE Password</label>
        <input className={INPUT_CLS} required minLength={6} value={form.pppoePassword} onChange={(e) => setForm({ ...form, pppoePassword: e.target.value })} />
      </div>
      <div>
        <label className={LABEL_CLS}>Speed (Mbps)</label>
        <input className={INPUT_CLS} type="number" min="1" value={form.mbps} onChange={(e) => setForm({ ...form, mbps: e.target.value })} />
      </div>
      <div>
        <label className={LABEL_CLS}>Monthly Fee</label>
        <input className={INPUT_CLS} type="number" min="1" required value={form.monthlyFee} onChange={(e) => setForm({ ...form, monthlyFee: e.target.value })} />
      </div>
      <div>
        <label className={LABEL_CLS}>Months Paid</label>
        <input className={INPUT_CLS} type="number" min="0" max="12" value={form.initialMonths} onChange={(e) => setForm({ ...form, initialMonths: e.target.value })} />
      </div>
      <div>
        <label className={LABEL_CLS}>Payment</label>
        <select className={INPUT_CLS} value={form.initialMethod} onChange={(e) => setForm({ ...form, initialMethod: e.target.value })}>
          <option value="CASH">Cash received</option>
          <option value="MPESA">Send a payment request</option>
        </select>
      </div>
      {error && <p className="text-sm text-error col-span-full">{error}</p>}
      <div className="col-span-full flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-semibold cursor-pointer">Cancel</button>
        <button type="submit" disabled={busy} className="px-4 py-2 rounded-lg bg-primary text-on-primary text-sm font-semibold disabled:opacity-60 cursor-pointer">
          {busy ? 'Converting…' : 'Create Subscriber'}
        </button>
      </div>
    </form>
  )
}

export default function Leads({ auth }) {
  const [leads, setLeads] = useState(null)
  const [showForm, setShowForm] = useState(false)
  const [filter, setFilter] = useState('ALL')
  const [form, setForm] = useState({ fullName: '', phoneNumber: '', location: '', interestedIn: '', quotedFee: '', source: 'WALK_IN', notes: '' })
  const [convertId, setConvertId] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/leads', { auth }).then(setLeads).catch(() => setLeads([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const counts = useMemo(() => {
    const out = { ALL: leads?.length || 0 }
    PIPELINE.forEach((s) => { out[s] = (leads || []).filter((l) => l.status === s).length })
    return out
  }, [leads])

  const rows = useMemo(
    () => (leads || []).filter((l) => filter === 'ALL' || l.status === filter),
    [leads, filter]
  )

  async function create(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/leads', {
        method: 'POST',
        auth,
        body: {
          ...form,
          phoneNumber: form.phoneNumber.replace(/\D/g, ''),
          quotedFee: form.quotedFee ? Number(form.quotedFee) : null,
        },
      })
      setForm({ fullName: '', phoneNumber: '', location: '', interestedIn: '', quotedFee: '', source: 'WALK_IN', notes: '' })
      setShowForm(false)
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function setStatus(id, status) {
    await api(`/admin/leads/${id}/status`, { method: 'PATCH', auth, body: { status } }).catch((e) => setMsg({ ok: false, text: e.message }))
    load()
  }

  async function convert(id, body) {
    await api(`/admin/leads/${id}/convert`, { method: 'POST', auth, body })
    setConvertId(null)
    setMsg({ ok: true, text: 'Lead converted — the subscriber is live on the router.' })
    load()
  }

  if (leads === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Leads" subtitle="People who have shown interest but are not signed up yet.">
        <PrimaryButton onClick={() => setShowForm(!showForm)}>
          <Icon name="person_add" /> Add Lead
        </PrimaryButton>
      </PageHeader>

      <div className="flex gap-2 mb-6 flex-wrap">
        {['ALL', ...PIPELINE].map((s) => (
          <button key={s} onClick={() => setFilter(s)}
            className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
              filter === s ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {s === 'ALL' ? 'All' : pretty(s)} <span className="tabular-nums opacity-70">{counts[s] || 0}</span>
          </button>
        ))}
      </div>

      {msg && <p className={`text-sm font-semibold mb-4 ${msg.ok ? 'text-surface-tint' : 'text-error'}`}>{msg.text}</p>}

      {showForm && (
        <form onSubmit={create} className="bg-surface-container-lowest rounded-lg p-4  grid grid-cols-1 md:grid-cols-3 gap-4 items-end mb-6">
          <div>
            <label className={LABEL_CLS}>Full Name</label>
            <input className={INPUT_CLS} required value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Phone</label>
            <input className={INPUT_CLS} required placeholder="2547XXXXXXXX" value={form.phoneNumber} onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Location</label>
            <input className={INPUT_CLS} placeholder="e.g. Kileleshwa" value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Interested In</label>
            <input className={INPUT_CLS} placeholder="e.g. 10 Mbps home" value={form.interestedIn} onChange={(e) => setForm({ ...form, interestedIn: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Quoted Fee (KES)</label>
            <input className={INPUT_CLS} type="number" min="1" value={form.quotedFee} onChange={(e) => setForm({ ...form, quotedFee: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Source</label>
            <select className={INPUT_CLS} value={form.source} onChange={(e) => setForm({ ...form, source: e.target.value })}>
              {SOURCES.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}
            </select>
          </div>
          <div className="md:col-span-2">
            <label className={LABEL_CLS}>Notes</label>
            <input className={INPUT_CLS} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
          </div>
          <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save Lead'}</PrimaryButton>
        </form>
      )}

      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full text-left border-collapse min-w-[900px]">
            <thead>
              <tr>
                <th>Prospect</th>
                <th>Interested In</th>
                <th>Quoted</th>
                <th>Source</th>
                <th>Added</th>
                <th>Status</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((l) => (
                <tr key={l.id}>
                  <td>
                    <div className="font-semibold text-on-background">{l.fullName}</div>
                    <div className="text-xs text-on-surface-variant">{l.phoneNumber}{l.location ? ` · ${l.location}` : ''}</div>
                  </td>
                  <td>
                    {l.interestedIn || '—'}
                    {l.notes && <div className="text-xs text-on-surface-variant mt-0.5 max-w-xs">{l.notes}</div>}
                  </td>
                  <td className="tabular-nums whitespace-nowrap">{l.quotedFee ? fmtKES(l.quotedFee) : '—'}</td>
                  <td className="text-on-surface-variant">{pretty(l.source)}</td>
                  <td className="text-on-surface-variant whitespace-nowrap">
                    {fmtDate(l.createdAt)}
                    <div className="text-xs">{relativeTime(l.createdAt)}</div>
                  </td>
                  <td>
                    <span className={`text-xs font-semibold px-2.5 py-1 rounded-full whitespace-nowrap ${STATUS_STYLES[l.status]}`}>
                      {pretty(l.status)}
                    </span>
                  </td>
                  <td className="text-right">
                    <div className="flex items-center justify-end gap-2 flex-wrap">
                      {l.status !== 'CONVERTED' && (
                        <>
                          <select
                            value={l.status}
                            onChange={(e) => setStatus(l.id, e.target.value)}
                            className="h-8 bg-surface border border-outline-variant rounded-lg px-2 text-xs focus:outline-none focus:border-primary cursor-pointer"
                            aria-label={`Status for ${l.fullName}`}
                          >
                            {PIPELINE.filter((s) => s !== 'CONVERTED').map((s) => (
                              <option key={s} value={s}>{pretty(s)}</option>
                            ))}
                          </select>
                          <button onClick={() => setConvertId(convertId === l.id ? null : l.id)}
                            className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer">
                            Convert
                          </button>
                        </>
                      )}
                      {l.status === 'CONVERTED' && (
                        <span className="text-xs text-on-surface-variant">Subscriber #{l.subscriberId}</span>
                      )}
                      <button onClick={() => api(`/admin/leads/${l.id}`, { method: 'DELETE', auth }).then(load)}
                        className="text-tertiary hover:text-error transition-colors p-1 cursor-pointer" aria-label={`Remove ${l.fullName}`}>
                        <Icon name="delete" className="text-[18px]!" />
                      </button>
                    </div>
                    {convertId === l.id && (
                      <ConvertForm lead={l} onCancel={() => setConvertId(null)} onConvert={(body) => convert(l.id, body)} />
                    )}
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr><td className="text-on-surface-variant" colSpan={7}>
                  {leads.length === 0 ? 'No leads yet — add the first prospect.' : 'No leads in this stage.'}
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
