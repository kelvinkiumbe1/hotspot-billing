import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard,
  fmtKES, fmtDate, fmtTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const CHANNELS = [
  { key: 'all', label: 'All' },
  { key: 'whatsapp', label: 'WhatsApp' },
  { key: 'sms', label: 'SMS' },
]

const AUDIENCES = [
  { key: 'specific', label: 'Specific people' },
  { key: 'segments', label: 'Segments' },
  { key: 'routers', label: 'Routers' },
  { key: 'everyone', label: 'Everyone' },
]

/** An SMS is billed per 160 characters, so the count matters commercially. */
const SMS_SEGMENT = 160

function StatusBadge({ status, error }) {
  const failed = status === 'FAILED'
  return (
    <span
      title={error || undefined}
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold ${
        failed ? 'bg-error-container text-on-error-container' : 'bg-secondary-container text-on-secondary-container'
      }`}
    >
      <Icon name={failed ? 'error' : 'check_circle'} className="text-[14px]!" />
      {failed ? 'Failed' : 'Sent'}
    </span>
  )
}

function Composer({ auth, options, onCancel, onSent }) {
  const [audience, setAudience] = useState('segments')
  const [segments, setSegments] = useState([])
  const [routerIds, setRouterIds] = useState([])
  const [phoneInput, setPhoneInput] = useState('')
  const [phones, setPhones] = useState([])
  const [body, setBody] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [preview, setPreview] = useState(null)

  const segmentCount = Math.max(1, Math.ceil(body.length / SMS_SEGMENT))

  // What the picked audience adds up to, before contacting the server.
  const reach = useMemo(() => {
    if (audience === 'everyone') return options.everyoneCount
    if (audience === 'specific') return phones.length
    if (audience === 'segments') {
      // Segments overlap, so this is an upper bound; the server dedupes.
      return segments.reduce((sum, key) =>
        sum + (options.segments.find((s) => s.key === key)?.count || 0), 0)
    }
    return null
  }, [audience, phones, segments, options])

  function addPhone() {
    const raw = phoneInput.trim()
    if (!raw) return
    if (!phones.includes(raw)) setPhones([...phones, raw])
    setPhoneInput('')
  }

  function insertVariable(name) {
    setBody((b) => (b ? `${b} @${name}` : `@${name}`))
  }

  async function submit(dryRun) {
    setBusy(true)
    setError(null)
    setPreview(null)
    try {
      const res = await api('/admin/comms/send', {
        method: 'POST',
        auth,
        body: { audience, segments, routerIds: routerIds.map(Number), phones, body, dryRun },
      })
      if (dryRun) {
        setPreview(res)
      } else {
        onSent(res)
      }
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  const ready = body.trim().length > 0 && (
    audience === 'everyone' ||
    (audience === 'specific' && phones.length > 0) ||
    (audience === 'segments' && segments.length > 0) ||
    (audience === 'routers' && routerIds.length > 0)
  )

  return (
    // Padding at the foot so content can scroll clear of the sticky send bar.
    <div className="space-y-6 pb-4">
      <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <h3 className="text-lg font-bold">Recipients</h3>
        <p className="text-sm text-on-surface-variant mt-0.5 mb-4">Who should receive this message?</p>

        <div className="flex flex-wrap gap-2 mb-5">
          {AUDIENCES.map((a) => (
            <button
              key={a.key}
              onClick={() => setAudience(a.key)}
              aria-pressed={audience === a.key}
              className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
                audience === a.key
                  ? 'bg-inverse-surface text-primary-fixed font-semibold'
                  : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
              }`}
            >
              {a.label}
            </button>
          ))}
        </div>

        {audience === 'specific' && (
          <div>
            <label className={LABEL_CLS}>Add numbers</label>
            <div className="flex gap-2">
              <input
                className={INPUT_CLS}
                value={phoneInput}
                onChange={(e) => setPhoneInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addPhone() } }}
                placeholder="0712345678 — press Enter to add"
              />
              <button type="button" onClick={addPhone}
                className="px-4 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high whitespace-nowrap">
                Add
              </button>
            </div>
            {phones.length > 0 && (
              <div className="flex flex-wrap gap-2 mt-3">
                {phones.map((p) => (
                  <span key={p} className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-surface-container-high text-sm">
                    {p}
                    <button onClick={() => setPhones(phones.filter((x) => x !== p))}
                      aria-label={`Remove ${p}`} className="cursor-pointer hover:text-error">
                      <Icon name="close" className="text-[14px]!" />
                    </button>
                  </span>
                ))}
              </div>
            )}
            <p className="text-xs text-on-surface-variant mt-2">
              07…, +2547… and 2547… all work — they are normalised before sending.
            </p>
          </div>
        )}

        {audience === 'segments' && (
          <div>
            <p className="text-sm font-semibold mb-1">Live audiences</p>
            <p className="text-xs text-on-surface-variant mb-3">Worked out from current subscriber details each time.</p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
              {options.segments.map((s) => {
                const on = segments.includes(s.key)
                return (
                  <label key={s.key}
                    className={`flex items-center gap-2.5 p-3 rounded-lg border cursor-pointer transition-colors ${
                      on ? 'border-primary bg-primary-container/15' : 'border-outline-variant hover:bg-surface-container-high'
                    }`}>
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() => setSegments(on ? segments.filter((k) => k !== s.key) : [...segments, s.key])}
                    />
                    <span className="text-sm flex-1">{s.label}</span>
                    <span className={`text-xs font-semibold ${s.count === 0 ? 'text-on-surface-variant' : ''}`}>
                      {s.count}
                    </span>
                  </label>
                )
              })}
            </div>
          </div>
        )}

        {audience === 'routers' && (
          options.routers.length === 0 ? (
            <p className="text-sm text-on-surface-variant">No routers configured yet.</p>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
              {options.routers.map((r) => {
                const on = routerIds.map(String).includes(String(r.id))
                return (
                  <label key={r.id}
                    className={`flex items-center gap-2.5 p-3 rounded-lg border cursor-pointer transition-colors ${
                      on ? 'border-primary bg-primary-container/15' : 'border-outline-variant hover:bg-surface-container-high'
                    }`}>
                    <input
                      type="checkbox"
                      checked={on}
                      onChange={() => setRouterIds(on
                        ? routerIds.filter((id) => String(id) !== String(r.id))
                        : [...routerIds, r.id])}
                    />
                    <span className="text-sm">{r.name}</span>
                  </label>
                )
              })}
            </div>
          )
        )}

        {audience === 'everyone' && (
          <p className="text-sm">
            Every subscriber and past hotspot customer — <strong>{options.everyoneCount}</strong> number
            {options.everyoneCount === 1 ? '' : 's'}.
          </p>
        )}
      </div>

      {!options.whatsappEnabled && !options.smsEnabled && (
        <div className="p-4 rounded-lg bg-warning/10 border border-warning/30">
          <p className="text-sm text-warning">
            No gateway is configured, so nothing can actually be delivered yet. You can still preview the
            audience and wording — add WhatsApp or SMS credentials under Settings to send for real.
          </p>
        </div>
      )}

      <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <h3 className="text-lg font-bold">Message</h3>
        <p className="text-sm text-on-surface-variant mt-0.5 mb-4">
          Use @variables to personalise each delivery.
        </p>

        <textarea
          className={`${INPUT_CLS} min-h-[130px] font-normal`}
          value={body}
          onChange={(e) => setBody(e.target.value)}
          placeholder="Dear @first_name, your package expires on @expiry_date."
          maxLength={1600}
        />

        <div className="flex justify-between items-center mt-2 text-xs text-on-surface-variant">
          <span>{body.length} / 1600</span>
          <span>{segmentCount} SMS segment{segmentCount > 1 ? 's' : ''} per recipient</span>
        </div>

        <div className="flex flex-wrap gap-1.5 mt-4">
          {options.variables.map((v) => (
            <button key={v} type="button" onClick={() => insertVariable(v)}
              className="px-2.5 py-1 rounded-md bg-surface-container-high font-mono text-xs cursor-pointer hover:bg-primary-container hover:text-on-primary-container">
              @{v}
            </button>
          ))}
        </div>
      </div>

      {error && <p className="text-sm text-error">{error}</p>}

      {preview && (
        <div className="bg-surface-container-lowest rounded-lg p-4 border border-primary/30">
          <p className="text-sm font-semibold mb-1">
            Would reach {preview.recipients} recipient{preview.recipients === 1 ? '' : 's'}
          </p>
          <p className="text-xs text-on-surface-variant mb-3">First few, exactly as they would arrive:</p>
          <ul className="space-y-2">
            {preview.preview.map((p, i) => (
              <li key={i} className="p-3 rounded-lg bg-surface-container-low text-sm">
                <span className="font-mono text-xs text-on-surface-variant">
                  {p.phone}{p.name ? ` · ${p.name}` : ''}
                </span>
                <p className="mt-1">{p.body}</p>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="sticky bottom-4 bg-surface-container-lowest border border-outline-variant rounded-lg p-4 flex flex-wrap items-center gap-3 ">
        <span className="text-sm flex items-center gap-2">
          <Icon name={ready ? 'check_circle' : 'info'} className={`text-[18px]! ${ready ? 'text-secondary' : 'text-on-surface-variant'}`} />
          {ready
            ? `Ready — about ${reach ?? '?'} recipient${reach === 1 ? '' : 's'}`
            : 'Pick an audience and write the message'}
        </span>
        <div className="ml-auto flex gap-2">
          <button onClick={onCancel}
            className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
            Cancel
          </button>
          <button onClick={() => submit(true)} disabled={!ready || busy}
            className="px-4 py-2 rounded-lg border border-primary text-primary text-sm font-semibold cursor-pointer hover:bg-primary/5 disabled:opacity-50">
            Preview
          </button>
          <PrimaryButton onClick={() => submit(false)} disabled={!ready || busy}>
            {busy ? 'Sending…' : 'Send message'}
          </PrimaryButton>
        </div>
      </div>
    </div>
  )
}

export default function CommunicationsPage({ auth }) {
  const [tab, setTab] = useState('all')
  const [rows, setRows] = useState(null)
  const [stats, setStats] = useState(null)
  const [options, setOptions] = useState(null)
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [composing, setComposing] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => {
    api(`/admin/comms/outbox?channel=${tab}`, { auth }).then(setRows).catch(() => setRows([]))
    api(`/admin/comms/outbox/stats?channel=${tab}`, { auth }).then(setStats).catch(() => {})
  }

  useEffect(() => { load() }, [auth, tab]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    api('/admin/comms/options', { auth }).then(setOptions).catch(() => {})
  }, [auth])

  const shown = useMemo(() => {
    const needle = search.trim().toLowerCase()
    return (rows || [])
      .filter((r) => statusFilter === 'ALL' || r.status === statusFilter)
      .filter((r) => !needle
        || r.recipient.includes(needle)
        || (r.recipientName || '').toLowerCase().includes(needle)
        || r.body.toLowerCase().includes(needle))
  }, [rows, statusFilter, search])

  const counts = useMemo(() => ({
    ALL: rows?.length || 0,
    SENT: (rows || []).filter((r) => r.status === 'SENT').length,
    FAILED: (rows || []).filter((r) => r.status === 'FAILED').length,
  }), [rows])

  async function resend(id) {
    try {
      await api(`/admin/comms/outbox/${id}/resend`, { method: 'POST', auth })
      setMsg({ ok: true, text: 'Retried — the result is at the top of the outbox.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (composing && options) {
    return (
      <div>
        <PageHeader
          title="Compose a message"
          subtitle="Pick your audience, write the body, and send through your gateway."
        />
        <Composer
          auth={auth}
          options={options}
          onCancel={() => setComposing(false)}
          onSent={(res) => {
            setComposing(false)
            setMsg({ ok: true, text: `${res.campaignRef} sent to ${res.sent} recipient(s).` })
            load()
          }}
        />
      </div>
    )
  }

  if (rows === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Outbox" subtitle="Every message sent to customers, automatic ones included.">
        <PrimaryButton onClick={() => setComposing(true)} disabled={!options}>
          <Icon name="add" /> New Message
        </PrimaryButton>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Sent Today" value={stats?.today ?? '—'} hint="in the last 24 hours" />
        <StatCard
          label="Accepted"
          value={stats?.sent ?? '—'}
          hint={stats?.successPercent != null ? `${stats.successPercent}% accepted by the gateway` : 'nothing sent yet'}
          accent="border-l-primary"
        />
        <StatCard
          label="Failed"
          value={stats?.failed ?? '—'}
          hint="rejected or not configured"
          accent={stats?.failed > 0 ? 'border-l-error' : ''}
        />
        <StatCard label="Spend · 30d" value={stats ? fmtKES(stats.spend30d) : '—'} hint="what the gateway reported" />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      <div className="flex flex-wrap gap-2 items-center mb-4">
        {CHANNELS.map((c) => (
          <button key={c.key} onClick={() => setTab(c.key)}
            className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
              tab === c.key
                ? 'bg-inverse-surface text-primary-fixed font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {c.label}
          </button>
        ))}

        <span className="w-px h-6 bg-outline-variant mx-1" />

        {['ALL', 'SENT', 'FAILED'].map((s) => (
          <button key={s} onClick={() => setStatusFilter(s)}
            className={`px-3 py-1.5 rounded-full text-sm cursor-pointer transition-colors ${
              statusFilter === s
                ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {s === 'ALL' ? 'All' : s === 'SENT' ? 'Sent' : 'Failed'} <span className="opacity-60">{counts[s]}</span>
          </button>
        ))}

        <input
          className="ml-auto w-64 bg-surface border border-outline-variant rounded-lg px-3 py-2 text-sm text-on-surface focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by phone or message…"
          aria-label="Search the outbox"
        />
      </div>

      {shown.length === 0 ? (
        <div className="p-10 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
          <Icon name="outbox" className="text-[40px]! text-on-surface-variant/40" />
          <p className="mt-2 text-on-surface-variant">
            {rows.length === 0
              ? 'Nothing sent yet. Expiry reminders and campaigns will both appear here.'
              : 'Nothing matches these filters.'}
          </p>
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr>
                  <th>Recipient</th>
                  <th>Message</th>
                  <th>Channel</th>
                  <th>Status</th>
                  <th>Sent</th>
                  <th className="text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <p className="font-mono text-sm">{r.recipient}</p>
                      {r.recipientName && <p className="text-xs text-on-surface-variant">{r.recipientName}</p>}
                    </td>
                    <td className="max-w-md">
                      <p className="text-sm">{r.body}</p>
                      {r.campaignRef && (
                        <p className="text-[10px] text-on-surface-variant font-mono mt-0.5">
                          {r.campaignRef}{r.sentBy ? ` · ${r.sentBy}` : ''}
                        </p>
                      )}
                    </td>
                    <td>
                      <span className="text-xs font-semibold">
                        {r.channel === 'WHATSAPP' ? 'WhatsApp' : 'SMS'}
                      </span>
                    </td>
                    <td><StatusBadge status={r.status} error={r.error} /></td>
                    <td className="text-xs">
                      {fmtDate(r.createdAt)}<br />
                      <span className="text-on-surface-variant">{fmtTime(r.createdAt)}</span>
                    </td>
                    <td className="text-right">
                      {r.status === 'FAILED' ? (
                        <button onClick={() => resend(r.id)} title="Try again"
                          className="px-2 py-1.5 rounded-lg border border-outline-variant cursor-pointer hover:bg-surface-container-high">
                          <Icon name="refresh" className="text-[16px]!" />
                        </button>
                      ) : (
                        <span className="text-on-surface-variant">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {rows.length > 0 && (
        <p className="mt-3 text-xs text-on-surface-variant">
          Showing the most recent {rows.length}. "Accepted" means the gateway took the message — a real
          delivery receipt needs a webhook from the provider, so we do not claim delivery we cannot see.
        </p>
      )}
    </div>
  )
}
