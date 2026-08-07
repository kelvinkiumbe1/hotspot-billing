import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard,
  fmtKES, fmtDate, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const KINDS = ['ROUTER', 'ONT', 'ACCESS_POINT', 'SWITCH', 'CABLE', 'ANTENNA', 'POWER', 'TOOL', 'OTHER']
const STATUSES = ['IN_STOCK', 'WITH_TECHNICIAN', 'DEPLOYED', 'FAULTY', 'RETIRED']

const KIND_ICONS = {
  ROUTER: 'router', ONT: 'settings_input_hdmi', ACCESS_POINT: 'wifi_tethering',
  SWITCH: 'lan', CABLE: 'cable', ANTENNA: 'cell_tower', POWER: 'bolt',
  TOOL: 'handyman', OTHER: 'inventory_2',
}

// Status colour is always paired with the label text, never carrying the meaning alone.
const STATUS_STYLES = {
  IN_STOCK: 'bg-secondary-container text-on-secondary-container',
  WITH_TECHNICIAN: 'bg-primary-container/25 text-primary',
  DEPLOYED: 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20',
  FAULTY: 'bg-error-container text-on-error-container',
  RETIRED: 'bg-surface-container-high text-on-surface-variant',
}

const pretty = (s) => (s || '').replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase())

/** Moving an item needs a holder for two of the five states, so it gets its own row. */
function MoveForm({ item, technicians, subscribers, onCancel, onMove }) {
  const [status, setStatus] = useState(item.status === 'IN_STOCK' ? 'WITH_TECHNICIAN' : 'IN_STOCK')
  const [technicianId, setTechnicianId] = useState(item.technicianId || '')
  const [subscriberId, setSubscriberId] = useState(item.subscriberId || '')
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await onMove({
        status,
        technicianId: status === 'WITH_TECHNICIAN' ? Number(technicianId) : null,
        subscriberId: status === 'DEPLOYED' ? Number(subscriberId) : null,
        notes: notes || null,
      })
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mt-3 p-4 rounded-lg bg-surface-container-low/60 border border-outline-variant grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
      <div>
        <label className={LABEL_CLS}>Move To</label>
        <select className={INPUT_CLS} value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{pretty(s)}</option>)}
        </select>
      </div>

      {status === 'WITH_TECHNICIAN' && (
        <div>
          <label className={LABEL_CLS}>Technician</label>
          <select className={INPUT_CLS} required value={technicianId} onChange={(e) => setTechnicianId(e.target.value)}>
            <option value="">Choose…</option>
            {technicians.map((t) => <option key={t.id} value={t.id}>{t.fullName}</option>)}
          </select>
        </div>
      )}

      {status === 'DEPLOYED' && (
        <div>
          <label className={LABEL_CLS}>Installed For</label>
          <select className={INPUT_CLS} required value={subscriberId} onChange={(e) => setSubscriberId(e.target.value)}>
            <option value="">Choose…</option>
            {subscribers.map((s) => (
              <option key={s.id} value={s.id}>{s.fullName} · {s.pppoeUsername}</option>
            ))}
          </select>
        </div>
      )}

      <div className={status === 'IN_STOCK' || status === 'FAULTY' || status === 'RETIRED' ? 'md:col-span-2' : ''}>
        <label className={LABEL_CLS}>Note (optional)</label>
        <input className={INPUT_CLS} value={notes} onChange={(e) => setNotes(e.target.value)}
          placeholder={status === 'FAULTY' ? 'What went wrong?' : 'Anything worth recording'} />
      </div>

      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Confirm'}</PrimaryButton>
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>

      {error && <p className="md:col-span-4 text-sm text-error">{error}</p>}
    </form>
  )
}

export default function EquipmentPage({ auth }) {
  const [items, setItems] = useState(null)
  const [summary, setSummary] = useState(null)
  const [technicians, setTechnicians] = useState([])
  const [subscribers, setSubscribers] = useState([])
  const [filter, setFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [moveId, setMoveId] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [form, setForm] = useState({
    name: '', kind: 'ROUTER', model: '', serialNumber: '', macAddress: '',
    quantity: 1, purchaseCost: '', purchasedAt: '', warrantyMonths: '', notes: '',
  })

  const load = () => {
    api('/admin/equipment', { auth }).then(setItems).catch(() => setItems([]))
    api('/admin/equipment/summary', { auth }).then(setSummary).catch(() => {})
  }

  useEffect(() => {
    load()
    api('/admin/technicians', { auth }).then(setTechnicians).catch(() => {})
    api('/admin/subscribers', { auth }).then(setSubscribers).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const counts = useMemo(() => {
    const out = { ALL: items?.length || 0 }
    STATUSES.forEach((s) => { out[s] = (items || []).filter((i) => i.status === s).length })
    return out
  }, [items])

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return (items || [])
      .filter((i) => filter === 'ALL' || i.status === filter)
      .filter((i) => !needle || [i.name, i.model, i.serialNumber, i.macAddress, i.technicianName, i.subscriberName]
        .some((f) => (f || '').toLowerCase().includes(needle)))
  }, [items, filter, search])

  async function create(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/equipment', {
        method: 'POST',
        auth,
        body: {
          ...form,
          quantity: Number(form.quantity) || 1,
          purchaseCost: form.purchaseCost ? Number(form.purchaseCost) : null,
          purchasedAt: form.purchasedAt || null,
          warrantyMonths: form.warrantyMonths ? Number(form.warrantyMonths) : null,
        },
      })
      setForm({ name: '', kind: 'ROUTER', model: '', serialNumber: '', macAddress: '', quantity: 1, purchaseCost: '', purchasedAt: '', warrantyMonths: '', notes: '' })
      setShowForm(false)
      setMsg({ ok: true, text: 'Item added to stock.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function move(id, body) {
    await api(`/admin/equipment/${id}/status`, { method: 'PATCH', auth, body })
    setMoveId(null)
    setMsg({ ok: true, text: 'Item moved.' })
    load()
  }

  async function remove(item) {
    if (!confirm(`Remove ${item.name} from the stock register? This cannot be undone.`)) return
    await api(`/admin/equipment/${item.id}`, { method: 'DELETE', auth }).catch((e) => setMsg({ ok: false, text: e.message }))
    load()
  }

  if (items === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Equipment" subtitle="Every router, ONT and cable drum you own, and where it is right now.">
        <PrimaryButton onClick={() => setShowForm(!showForm)}>
          <Icon name="add" /> Add Item
        </PrimaryButton>
      </PageHeader>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5 mb-4">
        {/* Cards count rows so they agree with the filter chips; the unit
            total goes in the hint, since one row can be a drum of four. */}
        <StatCard label="In Stock" value={counts.IN_STOCK} hint={`${summary?.IN_STOCK ?? 0} units on the shelf`} accent="border-l-primary" />
        <StatCard label="Out With Technicians" value={counts.WITH_TECHNICIAN} hint={`${summary?.WITH_TECHNICIAN ?? 0} units signed out`} />
        <StatCard label="Deployed" value={counts.DEPLOYED} hint={`${summary?.DEPLOYED ?? 0} units at customers`} />
        <StatCard
          label="Asset Value"
          value={summary ? fmtKES(summary.assetValue) : '—'}
          hint={summary?.warrantyExpiringSoon > 0
            ? `${summary.warrantyExpiringSoon} warranty expiring within 60 days`
            : 'Excludes retired items'}
          accent={summary?.warrantyExpiringSoon > 0 ? 'border-l-[#f59e0b]' : ''}
        />
      </div>

      {msg && (
        <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>
      )}

      {showForm && (
        <form onSubmit={create} className="mb-6 p-5 rounded-lg bg-surface-container-lowest border border-outline-variant grid grid-cols-1 md:grid-cols-3 gap-4">
          <div>
            <label className={LABEL_CLS}>Item Name</label>
            <input className={INPUT_CLS} required value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="TP-Link EC220 router" />
          </div>
          <div>
            <label className={LABEL_CLS}>Type</label>
            <select className={INPUT_CLS} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
              {KINDS.map((k) => <option key={k} value={k}>{pretty(k)}</option>)}
            </select>
          </div>
          <div>
            <label className={LABEL_CLS}>Model</label>
            <input className={INPUT_CLS} value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Serial Number</label>
            <input className={INPUT_CLS} value={form.serialNumber}
              onChange={(e) => setForm({ ...form, serialNumber: e.target.value })}
              placeholder="Leave blank for cable and consumables" />
          </div>
          <div>
            <label className={LABEL_CLS}>MAC Address</label>
            <input className={INPUT_CLS} value={form.macAddress}
              onChange={(e) => setForm({ ...form, macAddress: e.target.value })} placeholder="AA:BB:CC:DD:EE:FF" />
          </div>
          <div>
            <label className={LABEL_CLS}>Quantity</label>
            <input className={INPUT_CLS} type="number" min="1" value={form.quantity}
              onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
            <p className="text-xs text-on-surface-variant mt-1">Count more than one only for items without serials.</p>
          </div>
          <div>
            <label className={LABEL_CLS}>Purchase Cost (KES)</label>
            <input className={INPUT_CLS} type="number" min="0" step="0.01" value={form.purchaseCost}
              onChange={(e) => setForm({ ...form, purchaseCost: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Purchased On</label>
            <input className={INPUT_CLS} type="date" value={form.purchasedAt}
              onChange={(e) => setForm({ ...form, purchasedAt: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Warranty (months)</label>
            <input className={INPUT_CLS} type="number" min="0" max="120" value={form.warrantyMonths}
              onChange={(e) => setForm({ ...form, warrantyMonths: e.target.value })} />
          </div>
          <div className="md:col-span-3">
            <label className={LABEL_CLS}>Notes</label>
            <input className={INPUT_CLS} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
          </div>
          <div className="md:col-span-3 flex gap-2">
            <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Add To Stock'}</PrimaryButton>
            <button type="button" onClick={() => setShowForm(false)}
              className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
              Cancel
            </button>
          </div>
        </form>
      )}

      <div className="flex gap-2 mb-4 flex-wrap items-center">
        {['ALL', ...STATUSES].map((s) => (
          <button key={s} onClick={() => setFilter(s)}
            className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
              filter === s
                ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {s === 'ALL' ? 'All' : pretty(s)} <span className="opacity-60">{counts[s]}</span>
          </button>
        ))}
        <input
          className={`${INPUT_CLS} ml-auto max-w-xs`}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search name, serial, MAC or holder…"
          aria-label="Search equipment"
        />
      </div>

      {rows.length === 0 ? (
        <div className="p-10 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
          <Icon name="inventory_2" className="text-[40px]! text-on-surface-variant/40" />
          <p className="mt-2 text-on-surface-variant">
            {items.length === 0 ? 'No equipment logged yet. Add your first router or ONT above.' : 'Nothing matches that filter.'}
          </p>
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr>
                  <th>Item</th>
                  <th>Serial / MAC</th>
                  <th>Status</th>
                  <th>Held By</th>
                  <th>Cost</th>
                  <th>Warranty</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((item) => (
                  <tr key={item.id}>
                    <td>
                      <div className="flex items-center gap-2">
                        <Icon name={KIND_ICONS[item.kind] || 'inventory_2'} className="text-[18px]! text-on-surface-variant" />
                        <div>
                          <p className="font-semibold">
                            {item.name}
                            {item.quantity > 1 && <span className="text-on-surface-variant font-normal"> ×{item.quantity}</span>}
                          </p>
                          <p className="text-xs text-on-surface-variant">
                            {pretty(item.kind)}{item.model ? ` · ${item.model}` : ''}
                          </p>
                        </div>
                      </div>
                      {moveId === item.id && (
                        <MoveForm
                          item={item}
                          technicians={technicians}
                          subscribers={subscribers}
                          onCancel={() => setMoveId(null)}
                          onMove={(body) => move(item.id, body)}
                        />
                      )}
                    </td>
                    <td className="font-mono text-xs">
                      {item.serialNumber || <span className="text-on-surface-variant font-sans">—</span>}
                      {item.macAddress && <p className="text-on-surface-variant">{item.macAddress}</p>}
                    </td>
                    <td>
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${STATUS_STYLES[item.status]}`}>
                        {pretty(item.status)}
                      </span>
                    </td>
                    <td>
                      {item.technicianName || item.subscriberName
                        ? <span>{item.technicianName || item.subscriberName}</span>
                        : <span className="text-on-surface-variant">Store</span>}
                    </td>
                    <td>{item.purchaseCost ? fmtKES(item.purchaseCost) : <span className="text-on-surface-variant">—</span>}</td>
                    <td className="text-xs">
                      {item.warrantyExpiry
                        ? <span className={new Date(item.warrantyExpiry) < new Date() ? 'text-error' : ''}>
                            {new Date(item.warrantyExpiry) < new Date() ? 'Expired ' : 'Until '}{fmtDate(item.warrantyExpiry)}
                          </span>
                        : <span className="text-on-surface-variant">—</span>}
                    </td>
                    <td>
                      <div className="flex gap-1.5 justify-end">
                        <button onClick={() => setMoveId(moveId === item.id ? null : item.id)}
                          className="px-3 py-1.5 rounded-md border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
                          Move
                        </button>
                        <button onClick={() => remove(item)} aria-label={`Remove ${item.name}`}
                          className="px-2 py-1.5 rounded-lg border border-outline-variant text-on-surface-variant cursor-pointer hover:bg-error-container hover:text-on-error-container">
                          <Icon name="delete" className="text-[16px]!" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
