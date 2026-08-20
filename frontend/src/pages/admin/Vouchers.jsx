import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard,
  fmtKES, fmtDate, fmtTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const STATUS_STYLES = {
  UNUSED: 'bg-secondary-container text-on-secondary-container',
  ACTIVE: 'bg-primary-container/25 text-primary',
  EXPIRED: 'bg-surface-container-high text-on-surface-variant',
}

const TABS = [
  { key: 'UNUSED', label: 'Unused' },
  { key: 'ACTIVE', label: 'In use' },
  { key: 'EXPIRED', label: 'Finished' },
  { key: 'ALL', label: 'All' },
]

const TEMPLATES = [
  { key: 'card', label: 'Cards (85×54mm)', hint: 'Business-card size, one per voucher.' },
  { key: 'strip', label: 'Thermal strips', hint: 'Narrow 58mm roll, for a receipt printer.' },
  { key: 'table', label: 'Plain list', hint: 'Dense table for the office copy.' },
]

function humanMinutes(mins) {
  if (!mins) return '—'
  if (mins < 60) return `${mins} min`
  if (mins < 1440) {
    const h = Math.floor(mins / 60)
    const m = mins % 60
    return m ? `${h}h ${m}m` : `${h} hour${h > 1 ? 's' : ''}`
  }
  const d = Math.floor(mins / 1440)
  const h = Math.floor((mins % 1440) / 60)
  return h ? `${d}d ${h}h` : `${d} day${d > 1 ? 's' : ''}`
}

/** Connect-time left on an in-use voucher, from the app's used-time tracking. */
function fmtLeft(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0))
  if (s <= 0) return '0m'
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (h > 0) return `${h}h ${m}m`
  if (m > 0) return `${m}m`
  return `${s}s`
}

/** Duration actually granted — a custom voucher overrides its plan. */
const voucherMinutes = (v) => v.customDurationMinutes || v.plan?.durationMinutes || 0

const packageLine = (v) => {
  const bits = []
  if (v.plan?.bandwidth) bits.push(v.plan.bandwidth)
  if (v.plan?.price != null) bits.push(fmtKES(v.plan.price))
  bits.push(humanMinutes(voucherMinutes(v)))
  return bits.join(' · ')
}

/**
 * Opens a print window in the chosen layout. Everything is inlined because
 * the new window has no access to the app's stylesheet.
 */
function printVouchers(vouchers, template, business) {
  if (vouchers.length === 0) return
  const win = window.open('', '_blank')
  if (!win) {
    alert('Your browser blocked the print window. Allow pop-ups for this site and try again.')
    return
  }

  const esc = (s) => String(s ?? '').replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]))
  const title = `${business} — ${vouchers.length} voucher${vouchers.length > 1 ? 's' : ''}`

  let body
  let css
  if (template === 'table') {
    body = `<table>
      <thead><tr><th>Code</th><th>Package</th><th>Duration</th><th>Price</th><th>Created</th></tr></thead>
      <tbody>${vouchers.map((v) => `<tr>
        <td class="code">${esc(v.code)}</td>
        <td>${esc(v.plan?.name || 'Custom')}</td>
        <td>${esc(humanMinutes(voucherMinutes(v)))}</td>
        <td>${esc(v.plan?.price != null ? fmtKES(v.plan.price) : '')}</td>
        <td>${esc(new Date(v.createdAt).toLocaleDateString('en-KE'))}</td>
      </tr>`).join('')}</tbody></table>`
    css = `
      table { width: 100%; border-collapse: collapse; font-size: 11pt; }
      th, td { border: 1px solid #999; padding: 5px 8px; text-align: left; }
      th { background: #eee; }
      .code { font-family: monospace; font-weight: bold; letter-spacing: 1px; }`
  } else if (template === 'strip') {
    body = vouchers.map((v) => `<div class="strip">
      <div class="biz">${esc(business)}</div>
      <div class="code">${esc(v.code)}</div>
      <div class="meta">${esc(v.plan?.name || 'Custom')} · ${esc(humanMinutes(voucherMinutes(v)))}</div>
      <div class="how">Use the code as both WiFi username and password</div>
    </div>`).join('')
    css = `
      .strip { width: 54mm; padding: 4mm 2mm; border-bottom: 1px dashed #999; text-align: center; page-break-inside: avoid; }
      .biz { font-size: 9pt; font-weight: bold; text-transform: uppercase; letter-spacing: 1px; }
      .code { font-family: monospace; font-size: 17pt; font-weight: bold; letter-spacing: 2px; margin: 2mm 0; }
      .meta { font-size: 9pt; }
      .how { font-size: 7pt; color: #444; margin-top: 1mm; }`
  } else {
    body = `<div class="grid">${vouchers.map((v) => `<div class="card">
      <div class="head"><strong>${esc(business)}</strong><span>INTERNET ACCESS</span></div>
      <div class="code-box"><small>ACCESS CODE</small><div class="code">${esc(v.code)}</div></div>
      <div class="foot">
        <span>${esc(v.plan?.name || 'Custom')} · ${esc(humanMinutes(voucherMinutes(v)))}</span>
        <span>Use as WiFi username &amp; password</span>
      </div>
    </div>`).join('')}</div>`
    css = `
      .grid { display: flex; flex-wrap: wrap; gap: 4mm; }
      .card { width: 85mm; height: 54mm; border: 1px solid #333; border-radius: 3mm;
              padding: 4mm; box-sizing: border-box; display: flex; flex-direction: column;
              justify-content: space-between; page-break-inside: avoid; }
      .head { display: flex; justify-content: space-between; align-items: baseline; font-size: 9pt; }
      .head span { letter-spacing: 1px; color: #555; font-size: 7pt; }
      .code-box { text-align: center; border: 1px dashed #666; border-radius: 2mm; padding: 3mm 2mm; }
      .code-box small { font-size: 6.5pt; letter-spacing: 1.5px; color: #555; }
      .code { font-family: monospace; font-size: 20pt; font-weight: bold; letter-spacing: 3px; }
      .foot { display: flex; flex-direction: column; gap: 1mm; font-size: 7.5pt; color: #333; }`
  }

  win.document.write(`<!doctype html><html><head><title>${esc(title)}</title><style>
    body { font-family: Arial, Helvetica, sans-serif; margin: 8mm; }
    h1 { font-size: 12pt; margin: 0 0 4mm; }
    .toolbar { margin-bottom: 5mm; }
    button { font-size: 11pt; padding: 6px 14px; cursor: pointer; }
    @media print { .toolbar, h1 { display: none; } body { margin: 4mm; } }
    ${css}
  </style></head><body>
    <h1>${esc(title)}</h1>
    <div class="toolbar"><button onclick="window.print()">Print these ${vouchers.length}</button></div>
    ${body}
  </body></html>`)
  win.document.close()
}

/** Generates a run of codes as one batch, so print-runs stay grouped. */
function GenerateModal({ auth, plans, agents, onClose, onDone }) {
  const [form, setForm] = useState({
    planId: plans.find((p) => p.name !== 'Custom Time') ? String(plans.find((p) => p.name !== 'Custom Time').id) : '',
    customMinutes: 60,
    count: 20,
    prefix: '',
    codeLength: 8,
    agentId: '',
    note: '',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  const custom = form.planId === 'custom'
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  // The generator fills the remaining characters randomly, so a long prefix
  // on a short code leaves nothing to randomise.
  const randomChars = Number(form.codeLength) - form.prefix.trim().length
  const prefixTooLong = randomChars < 4

  async function submit(e) {
    e.preventDefault()
    if (prefixTooLong) {
      setError('Leave at least 4 characters for the random part — shorten the prefix or raise the length.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const batch = await api('/admin/batches', {
        method: 'POST',
        auth,
        body: {
          planId: custom ? null : Number(form.planId),
          customMinutes: custom ? Number(form.customMinutes) : null,
          count: Number(form.count),
          prefix: form.prefix.trim().toUpperCase() || null,
          codeLength: Number(form.codeLength),
          agentId: form.agentId ? Number(form.agentId) : null,
          note: form.note.trim() || null,
        },
      })
      onDone(batch)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-start justify-center p-5 overflow-y-auto"
      onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-xl rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)] my-8">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-start">
          <div>
            <h3 className="text-2xl font-bold">Generate vouchers</h3>
            <p className="text-sm text-on-surface-variant mt-0.5">
              Codes are created as one batch you can hand out and print together.
            </p>
          </div>
          <button onClick={onClose} aria-label="Close"
            className="text-on-surface-variant hover:text-error p-1 rounded-full hover:bg-error/10 cursor-pointer">
            <Icon name="close" />
          </button>
        </div>

        <form onSubmit={submit} className="p-6 space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Package</label>
              <select className={INPUT_CLS} value={form.planId} onChange={(e) => set({ planId: e.target.value })}>
                {plans.filter((p) => p.name !== 'Custom Time').map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} — {fmtKES(p.price)} · {humanMinutes(p.durationMinutes)}
                  </option>
                ))}
                <option value="custom">Custom duration…</option>
              </select>
            </div>
            {custom ? (
              <div>
                <label className={LABEL_CLS}>Minutes</label>
                <input className={INPUT_CLS} type="number" min="1" max="44640" value={form.customMinutes}
                  onChange={(e) => set({ customMinutes: e.target.value })} />
                <p className="text-xs text-on-surface-variant mt-1">= {humanMinutes(Number(form.customMinutes))}</p>
              </div>
            ) : (
              <div>
                <label className={LABEL_CLS}>How many</label>
                <input className={INPUT_CLS} type="number" min="1" max="500" required value={form.count}
                  onChange={(e) => set({ count: e.target.value })} />
              </div>
            )}
          </div>

          {custom && (
            <div>
              <label className={LABEL_CLS}>How many</label>
              <input className={INPUT_CLS} type="number" min="1" max="500" required value={form.count}
                onChange={(e) => set({ count: e.target.value })} />
            </div>
          )}

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Code prefix</label>
              <input className={INPUT_CLS} maxLength={10} placeholder="e.g. SPA" value={form.prefix}
                onChange={(e) => set({ prefix: e.target.value.toUpperCase() })} />
              <p className="text-xs text-on-surface-variant mt-1">Optional — makes codes easy to trace back.</p>
            </div>
            <div>
              <label className={LABEL_CLS}>Total code length</label>
              <input className={INPUT_CLS} type="number" min="6" max="16" value={form.codeLength}
                onChange={(e) => set({ codeLength: e.target.value })} />
              <p className={`text-xs mt-1 ${prefixTooLong ? 'text-error' : 'text-on-surface-variant'}`}>
                {prefixTooLong
                  ? 'Too little left to randomise.'
                  : `${form.prefix.trim().toUpperCase() || ''}${'•'.repeat(Math.max(0, randomChars))} — ${randomChars} random characters`}
              </p>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Hand to agent</label>
              <select className={INPUT_CLS} value={form.agentId} onChange={(e) => set({ agentId: e.target.value })}>
                <option value="">Keep at head office</option>
                {agents.filter((a) => a.active).map((a) => (
                  <option key={a.id} value={a.id}>{a.fullName} ({a.code})</option>
                ))}
              </select>
            </div>
            <div>
              <label className={LABEL_CLS}>Note</label>
              <input className={INPUT_CLS} placeholder="e.g. Weekend stock" value={form.note}
                onChange={(e) => set({ note: e.target.value })} />
            </div>
          </div>

          {error && <p className="text-sm text-error">{error}</p>}

          <div className="flex justify-end gap-3 pt-2">
            <button type="button" onClick={onClose}
              className="px-5 py-2.5 rounded-lg border border-outline-variant text-sm font-semibold cursor-pointer hover:bg-surface-container-high">
              Cancel
            </button>
            <PrimaryButton type="submit" disabled={busy || prefixTooLong}>
              {busy ? 'Generating…' : `Generate ${form.count || 0}`}
            </PrimaryButton>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function VouchersPage({ auth }) {
  const [vouchers, setVouchers] = useState(null)
  const [summary, setSummary] = useState(null)
  const [plans, setPlans] = useState([])
  const [agents, setAgents] = useState([])
  const [business, setBusiness] = useState('SPA WiFi')
  const [tab, setTab] = useState('UNUSED')
  const [search, setSearch] = useState('')
  const [planFilter, setPlanFilter] = useState('')
  const [picked, setPicked] = useState([])
  const [showGenerate, setShowGenerate] = useState(false)
  const [menuFor, setMenuFor] = useState(null)
  const [printOpen, setPrintOpen] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => {
    api('/admin/vouchers', { auth }).then(setVouchers).catch(() => setVouchers([]))
    api('/admin/vouchers/summary', { auth }).then(setSummary).catch(() => {})
  }

  useEffect(() => {
    load()
    api('/admin/plans', { auth }).then(setPlans).catch(() => {})
    api('/admin/agents', { auth }).then(setAgents).catch(() => setAgents([]))
    api('/portal-settings').then((s) => s?.businessName && setBusiness(s.businessName)).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const rows = useMemo(() => {
    const needle = search.trim().toUpperCase()
    return (vouchers || [])
      .filter((v) => tab === 'ALL' || v.status === tab)
      .filter((v) => !planFilter || String(v.plan?.id) === String(planFilter))
      .filter((v) => !needle || v.code.includes(needle))
  }, [vouchers, tab, planFilter, search])

  const counts = useMemo(() => {
    const out = { ALL: vouchers?.length || 0 }
    TABS.forEach((t) => {
      if (t.key !== 'ALL') out[t.key] = (vouchers || []).filter((v) => v.status === t.key).length
    })
    return out
  }, [vouchers])

  // Selection is kept as ids so it survives a reload of the list.
  const pickedRows = (vouchers || []).filter((v) => picked.includes(v.id))
  const allShownPicked = rows.length > 0 && rows.every((v) => picked.includes(v.id))

  const toggleAll = () =>
    setPicked(allShownPicked
      ? picked.filter((id) => !rows.some((v) => v.id === id))
      : [...new Set([...picked, ...rows.map((v) => v.id)])])

  async function bulk(action) {
    const label = action === 'bulk-revoke' ? 'disable' : 'delete'
    if (!confirm(`${label === 'delete' ? 'Delete' : 'Disable'} ${picked.length} voucher(s)?`)) return
    try {
      const res = await api(`/admin/vouchers/${action}`, { method: 'POST', auth, body: { ids: picked } })
      const done = res.revoked || res.deleted || []
      const skipped = Object.entries(res.skipped || {})
      setMsg({
        ok: done.length > 0,
        text: `${done.length} voucher(s) ${label === 'delete' ? 'deleted' : 'disabled'}.`
          + (skipped.length ? ` Skipped ${skipped.length}: ${skipped.map(([c, why]) => `${c} (${why})`).join(', ')}` : ''),
      })
      setPicked([])
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  async function rowAction(v, action) {
    setMenuFor(null)
    try {
      if (action === 'delete') {
        await api(`/admin/vouchers/${v.id}`, { method: 'DELETE', auth })
      } else {
        await api(`/admin/vouchers/${v.id}/${action}`, { method: 'PATCH', auth })
      }
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  function exportCsv() {
    // Hits the CSV endpoint with credentials, then saves the blob — a plain
    // link would not carry the Basic auth header.
    const status = tab === 'ALL' ? '' : `?status=${tab}`
    fetch(`/api/admin/vouchers/export${status}`, { headers: { Authorization: auth } })
      .then((r) => {
        if (!r.ok) throw new Error('Export failed')
        return r.blob()
      })
      .then((blob) => {
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = `vouchers-${tab.toLowerCase()}-${new Date().toISOString().slice(0, 10)}.csv`
        a.click()
        URL.revokeObjectURL(url)
      })
      .catch((err) => setMsg({ ok: false, text: err.message }))
  }

  function doPrint(template) {
    setPrintOpen(false)
    const target = picked.length > 0 ? pickedRows : rows
    if (target.length === 0) {
      setMsg({ ok: false, text: 'Nothing to print — the list is empty.' })
      return
    }
    printVouchers(target, template, business)
  }

  if (vouchers === null) return <Skeleton className="h-64" />

  return (
    <div onClick={() => { setMenuFor(null); setPrintOpen(false) }}>
      <PageHeader title="Voucher codes" subtitle="Prepaid codes for hotspot top-ups.">
        <PrimaryButton onClick={() => setShowGenerate(true)}>
          <Icon name="add" /> Generate vouchers
        </PrimaryButton>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Total" value={summary?.total ?? '—'} hint="codes generated" />
        <StatCard
          label="Redeemed"
          value={summary?.redeemed ?? '—'}
          hint={summary ? `${summary.redemptionPercent}% redemption rate` : ''}
          accent="border-l-primary"
        />
        <StatCard label="Unused" value={summary?.unused ?? '—'} hint="still redeemable" />
        <StatCard label="Batches" value={summary?.batches ?? '—'} hint="grouped print-runs" />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      <div className="flex flex-wrap gap-2 items-center mb-4">
        {TABS.map((t) => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
              tab === t.key
                ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {t.label} <span className="opacity-60">{counts[t.key] ?? 0}</span>
          </button>
        ))}

        <div className="ml-auto flex flex-wrap gap-2 items-center">
          <select
            className="bg-surface border border-outline-variant rounded-lg px-3 py-2 text-sm cursor-pointer"
            value={planFilter}
            onChange={(e) => setPlanFilter(e.target.value)}
            aria-label="Filter by package"
          >
            <option value="">All packages</option>
            {plans.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>

          <div className="relative" onClick={(e) => e.stopPropagation()}>
            <button onClick={() => setPrintOpen(!printOpen)}
              className="flex items-center gap-1.5 px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
              <Icon name="print" className="text-[18px]!" /> Print
              <Icon name="expand_more" className="text-[16px]!" />
            </button>
            {printOpen && (
              <div className="absolute right-0 top-full mt-1 w-64 bg-surface-container-lowest border border-outline-variant rounded-lg shadow-[0_8px_16px_rgba(15,23,42,0.12)] z-40 overflow-hidden">
                <p className="px-3 py-2 text-xs text-on-surface-variant border-b border-outline-variant/50">
                  {picked.length > 0 ? `${picked.length} selected` : `${rows.length} shown`} will print
                </p>
                {TEMPLATES.map((t) => (
                  <button key={t.key} onClick={() => doPrint(t.key)}
                    className="w-full text-left px-3 py-2.5 hover:bg-surface-container-high cursor-pointer">
                    <span className="text-sm font-medium block">{t.label}</span>
                    <span className="text-xs text-on-surface-variant">{t.hint}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <button onClick={exportCsv}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
            <Icon name="download" className="text-[18px]!" /> Export
          </button>

          {/* Explicit width rather than INPUT_CLS, whose w-full would push
              this onto its own row. */}
          <input
            className="w-52 bg-surface border border-outline-variant rounded-lg px-3 py-2 text-sm text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none transition-all"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by code…"
            aria-label="Search by code"
          />
        </div>
      </div>

      {picked.length > 0 && (
        <div className="mb-4 p-3 rounded-lg bg-primary-container/20 border border-primary/20 flex flex-wrap items-center gap-3">
          <span className="text-sm font-semibold">{picked.length} selected</span>
          <button onClick={() => bulk('bulk-revoke')}
            className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high">
            Disable
          </button>
          <button onClick={() => bulk('bulk-delete')}
            className="px-3 py-1.5 rounded-lg border border-error/40 text-error text-xs font-semibold cursor-pointer hover:bg-error-container">
            Delete
          </button>
          <button onClick={() => setPicked([])}
            className="ml-auto text-xs text-on-surface-variant hover:text-on-surface cursor-pointer">
            Clear selection
          </button>
        </div>
      )}

      {rows.length === 0 ? (
        <div className="p-10 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
          <Icon name="confirmation_number" className="text-[40px]! text-on-surface-variant/40" />
          <p className="mt-2 text-on-surface-variant">
            {vouchers.length === 0 ? 'No vouchers yet — generate your first batch.' : 'Nothing matches these filters.'}
          </p>
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr>
                  <th className="w-10">
                    <input type="checkbox" checked={allShownPicked} onChange={toggleAll}
                      aria-label="Select all shown" className="cursor-pointer" />
                  </th>
                  <th>Code</th>
                  <th>Package</th>
                  <th>Status</th>
                  <th>Time left</th>
                  <th>Expires</th>
                  <th>Created</th>
                  <th>Redeemed</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.id}>
                    <td>
                      <input
                        type="checkbox"
                        className="cursor-pointer"
                        checked={picked.includes(v.id)}
                        onChange={() => setPicked(picked.includes(v.id)
                          ? picked.filter((id) => id !== v.id)
                          : [...picked, v.id])}
                        aria-label={`Select ${v.code}`}
                      />
                    </td>
                    <td>
                      <button
                        onClick={() => navigator.clipboard?.writeText(v.code)}
                        title="Copy code"
                        className="font-mono font-bold tracking-wider cursor-pointer hover:text-primary"
                      >
                        {v.code}
                      </button>
                      {v.boundMac && (
                        <p className="text-[10px] text-on-surface-variant font-mono">locked to {v.boundMac}</p>
                      )}
                    </td>
                    <td>
                      <p className="font-semibold">{v.plan?.name || 'Custom'}</p>
                      <p className="text-xs text-on-surface-variant">{packageLine(v)}</p>
                    </td>
                    <td>
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_STYLES[v.status]}`}>
                        {v.status === 'UNUSED' ? 'Unused' : v.status === 'ACTIVE' ? 'In use' : 'Finished'}
                      </span>
                    </td>
                    <td className="text-xs">
                      {v.status === 'ACTIVE' ? (
                        <span className={v.remainingSeconds <= 300 ? 'font-semibold text-warning' : 'text-on-surface'}>
                          {fmtLeft(v.remainingSeconds)} left
                        </span>
                      ) : v.status === 'UNUSED' ? (
                        <span className="text-on-surface-variant">full pass</span>
                      ) : (
                        <span className="text-on-surface-variant">spent</span>
                      )}
                    </td>
                    <td className="text-xs">
                      {v.expiresAt
                        ? <span>{fmtDate(v.expiresAt)}<br />{fmtTime(v.expiresAt)}</span>
                        : <span className="text-on-surface-variant">never</span>}
                    </td>
                    <td className="text-xs">
                      {fmtDate(v.createdAt)}
                      {v.createdBy && <p className="text-on-surface-variant">by {v.createdBy}</p>}
                    </td>
                    <td className="text-xs">
                      {v.activatedAt
                        ? <span>{fmtDate(v.activatedAt)}<br />{fmtTime(v.activatedAt)}</span>
                        : <span className="text-on-surface-variant">—</span>}
                    </td>
                    <td className="text-right">
                      <div className="relative inline-block" onClick={(e) => e.stopPropagation()}>
                        <button
                          onClick={() => setMenuFor(menuFor === v.id ? null : v.id)}
                          aria-label={`Actions for ${v.code}`}
                          className="px-2 py-1 rounded-lg border border-outline-variant cursor-pointer hover:bg-surface-container-high"
                        >
                          <Icon name="more_horiz" className="text-[18px]!" />
                        </button>
                        {menuFor === v.id && (
                          <div className="absolute right-0 top-full mt-1 w-48 bg-surface-container-lowest border border-outline-variant rounded-lg shadow-[0_8px_16px_rgba(15,23,42,0.12)] z-40 overflow-hidden text-left">
                            <button onClick={() => { setMenuFor(null); printVouchers([v], 'card', business) }}
                              className="w-full text-left px-3 py-2 text-sm hover:bg-surface-container-high cursor-pointer">
                              Print this card
                            </button>
                            {v.status === 'ACTIVE' && (
                              <button onClick={() => rowAction(v, 'revoke')}
                                className="w-full text-left px-3 py-2 text-sm hover:bg-surface-container-high cursor-pointer">
                                Disable now
                              </button>
                            )}
                            {v.boundMac && (
                              <button onClick={() => rowAction(v, 'unbind')}
                                className="w-full text-left px-3 py-2 text-sm hover:bg-surface-container-high cursor-pointer">
                                Unlock from device
                              </button>
                            )}
                            {v.status === 'UNUSED' && (
                              <button onClick={() => rowAction(v, 'delete')}
                                className="w-full text-left px-3 py-2 text-sm text-error hover:bg-error-container cursor-pointer">
                                Delete
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {showGenerate && (
        <GenerateModal
          auth={auth}
          plans={plans}
          agents={agents}
          onClose={() => setShowGenerate(false)}
          onDone={(batch) => {
            setShowGenerate(false)
            setMsg({ ok: true, text: `${batch.reference} created with ${batch.count} code(s).` })
            setTab('UNUSED')
            load()
          }}
        />
      )}
    </div>
  )
}
