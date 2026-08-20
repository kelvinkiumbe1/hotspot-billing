import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PrimaryButton, StatCard, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * How much light each customer's fibre is getting, and who to send a van to.
 *
 * Sorted worst first, always. A list of two thousand ONUs ordered by serial
 * number is a list nobody opens; the same list with the four failing drops at the
 * top is a morning's work.
 *
 * The number is shown next to the verdict rather than instead of it. "-27.4 dBm"
 * means nothing to most of the people who will read this screen; "past the
 * budget, expect drops" is what gets somebody into a van.
 */

const HEALTH = {
  DOWN: { label: 'No light', cls: 'bg-error-container text-on-error-container', dot: 'bg-error' },
  BAD: { label: 'Past the budget', cls: 'bg-error-container/60 text-on-error-container', dot: 'bg-error' },
  TOO_HOT: { label: 'Too strong', cls: 'bg-[#fde68a] text-[#78350f]', dot: 'bg-[#d97706]' },
  MARGINAL: { label: 'Getting marginal', cls: 'bg-[#fef3c7] text-[#78350f]', dot: 'bg-[#d97706]' },
  GOOD: { label: 'Fine', cls: 'bg-secondary-container text-on-secondary-container', dot: 'bg-secondary' },
  UNKNOWN: { label: 'No reading', cls: 'bg-surface-container-high text-on-surface-variant', dot: 'bg-outline' },
}

function HealthPill({ health }) {
  const h = HEALTH[health] || HEALTH.UNKNOWN
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${h.cls}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${h.dot}`}></span>{h.label}
    </span>
  )
}

/** The drop since the last reading, which is the thing that means something. */
function Change({ now, before }) {
  if (now === null || now === undefined || before === null || before === undefined) return null
  const delta = now - before
  if (Math.abs(delta) < 0.5) return null
  const worse = delta < 0
  return (
    <span className={`text-xs ${worse ? 'text-error' : 'text-secondary'}`}>
      {worse ? '▼' : '▲'} {Math.abs(delta).toFixed(1)} dB
      {/* 3 dB is half the light. Worth calling out rather than leaving as arithmetic. */}
      {worse && Math.abs(delta) >= 3 && ' — half the light gone'}
    </span>
  )
}

export default function FiberLight({ auth }) {
  const [olts, setOlts] = useState(null)
  const [selected, setSelected] = useState('attention')
  const [rows, setRows] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [filter, setFilter] = useState('')

  useEffect(() => {
    api('/admin/devices', { auth })
      .then((d) => setOlts((d.devices || []).filter((x) => x.kind === 'OLT')))
      .catch(() => setOlts([]))
  }, [auth])

  const load = () => {
    const url = selected === 'attention'
      ? '/admin/devices/onus/attention'
      : `/admin/devices/${selected}/onus`
    api(url, { auth }).then((d) => setRows(d.onus || [])).catch(() => setRows([]))
  }
  useEffect(() => { setRows(null); load() }, [selected, auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function checkNow() {
    if (selected === 'attention') return
    setBusy(true); setMsg(null)
    try {
      const r = await api(`/admin/devices/${selected}/onus/check`, { method: 'POST', auth })
      setMsg({
        ok: !r.error,
        // The message says which of the two it was, because a wrong OID and an
        // OLT with no ONUs look identical over SNMP.
        text: r.error || `Read ${r.onusSeen} ONU(s)${r.alerted ? `, ${r.alerted} worth attention` : ''}.`,
      })
      load()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  const shown = useMemo(() => {
    if (!rows) return null
    const needle = filter.trim().toLowerCase()
    if (!needle) return rows
    return rows.filter((r) => (r.serial || '').toLowerCase().includes(needle)
      || (r.description || '').toLowerCase().includes(needle))
  }, [rows, filter])

  if (olts === null) return <Skeleton className="h-64" />

  if (olts.length === 0) {
    return (
      <div className="rounded-lg border border-outline-variant p-6 text-center">
        <Icon name="settings_input_antenna" className="text-[32px]! text-on-surface-variant" />
        <p className="text-base font-semibold mt-2">No OLT is set up yet</p>
        <p className="text-sm text-on-surface-variant mt-1">
          Add one under Devices with its type set to OLT, choose the vendor, and the light
          readings appear here within fifteen minutes.
        </p>
      </div>
    )
  }

  const counts = (rows || []).reduce((acc, r) => {
    acc[r.health] = (acc[r.health] || 0) + 1
    return acc
  }, {})

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label className={LABEL_CLS}>Showing</label>
          <select className={INPUT_CLS} value={selected}
            onChange={(e) => setSelected(e.target.value)}>
            <option value="attention">Everything needing attention</option>
            {olts.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
          </select>
        </div>
        <div className="flex-1 min-w-[12rem]">
          <label className={LABEL_CLS}>Find</label>
          <input className={INPUT_CLS} value={filter} placeholder="serial, or the name on it"
            onChange={(e) => setFilter(e.target.value)} />
        </div>
        {selected !== 'attention' && (
          <PrimaryButton disabled={busy} onClick={checkNow}>
            {busy ? 'Reading…' : 'Read the OLT now'}
          </PrimaryButton>
        )}
      </div>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}

      {rows && rows.length > 0 && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="No light" value={counts.DOWN || 0} />
          <StatCard label="Past the budget" value={counts.BAD || 0} />
          <StatCard label="Getting marginal" value={counts.MARGINAL || 0} />
          <StatCard label="Fine" value={counts.GOOD || 0} />
        </div>
      )}

      {shown === null ? <Skeleton className="h-48" /> : shown.length === 0 ? (
        <p className="text-sm text-on-surface-variant">
          {filter ? `Nothing matches “${filter}”.`
            : selected === 'attention'
              ? 'Every fibre reading is healthy.'
              : 'No readings yet. Press “Read the OLT now”, or wait for the next sweep.'}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-outline-variant">
          <table className="w-full text-sm">
            <thead className="bg-surface-container-low text-on-surface-variant">
              <tr>
                <th className="text-left font-medium px-3 py-2">ONU</th>
                <th className="text-left font-medium px-3 py-2">Receiving</th>
                <th className="text-left font-medium px-3 py-2">Verdict</th>
                <th className="text-left font-medium px-3 py-2">Last read</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/40">
              {shown.map((r) => (
                <tr key={r.id} className="hover:bg-surface-container-low">
                  <td className="px-3 py-2">
                    <p className="font-medium">{r.description || r.serial}</p>
                    {r.description && (
                      <p className="text-xs font-mono text-on-surface-variant">{r.serial}</p>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <p className="font-mono">
                      {r.rxDbm === null || r.rxDbm === undefined ? '—' : `${r.rxDbm.toFixed(1)} dBm`}
                    </p>
                    <Change now={r.rxDbm} before={r.previousRxDbm} />
                  </td>
                  <td className="px-3 py-2">
                    <HealthPill health={r.health} />
                    {/* The sentence the backend produced, so the screen and the
                        alert an operator got by SMS say the same thing. */}
                    <p className="text-xs text-on-surface-variant mt-0.5">{r.verdict}</p>
                  </td>
                  <td className="px-3 py-2 text-on-surface-variant">
                    {r.lastSeenAt ? relativeTime(r.lastSeenAt) : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="text-xs text-on-surface-variant flex items-start gap-2">
        <Icon name="info" className="text-[16px]! mt-0.5" />
        Read automatically every fifteen minutes. A fibre that drops by 3 dB or falls past the
        budget also sends you a message, at most once every six hours per ONU.
      </p>
    </div>
  )
}
