import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, relativeTime,
} from '../../components/ui.jsx'

/**
 * Copies of what is on each router.
 *
 * The screen is built around one question -- "if this router died right now,
 * could I put it back?" -- so the thing shown largest is the age of the last
 * successful copy, and a router nobody has managed to reach is called out rather
 * than left looking the same as one that simply has not changed.
 *
 * The distinction between the two capture methods is carried all the way to the
 * download button. One of them produces a file a RouterOS box will import; the
 * other is a record of the configuration and nothing more. Discovering which on
 * the night it matters would be the worst possible time.
 */

function bytes(n) {
  if (!n) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

/** How healthy one router's backup situation is. */
function Freshness({ row }) {
  if (row.error) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-error-container text-on-error-container">
        <span className="w-1.5 h-1.5 rounded-full bg-error"></span>
        {row.lastBackupAt ? `failing since ${relativeTime(row.lastBackupAt)}` : 'never succeeded'}
      </span>
    )
  }
  if (!row.lastBackupAt) {
    return (
      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium bg-surface-container-high text-on-surface-variant">
        <span className="w-1.5 h-1.5 rounded-full bg-outline"></span>
        never tried
      </span>
    )
  }
  // Two days, not one: the job is nightly, so a single missed night is a blip
  // and calling it a failure trains people to ignore the colour.
  const stale = Date.now() - new Date(row.lastBackupAt).getTime() > 2 * 86400000
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${
      stale ? 'bg-warning-container text-on-warning-container' : 'bg-secondary-container text-on-secondary-container'}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${stale ? 'bg-warning' : 'bg-secondary'}`}></span>
      {relativeTime(row.lastBackupAt)}
    </span>
  )
}

function DiffView({ lines }) {
  return (
    <pre className="text-xs font-mono bg-surface-container-high rounded-lg p-3 overflow-x-auto max-h-96 overflow-y-auto">
      {lines.map((l, i) => (
        <div key={i} className={
          l.mark === '+' ? 'text-secondary bg-secondary-container/30'
            : l.mark === '-' ? 'text-error bg-error-container/30'
              : l.mark === 'note' ? 'text-on-surface-variant italic'
                : 'text-on-surface-variant'}>
          {l.mark === 'note' ? l.text : `${l.mark} ${l.text}`}
        </div>
      ))}
    </pre>
  )
}

function RouterHistory({ auth, router, onClose, onChanged }) {
  const [versions, setVersions] = useState(null)
  const [viewing, setViewing] = useState(null)
  const [diff, setDiff] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api(`/admin/router-backups/${router.routerId}/versions`, { auth })
    .then((d) => setVersions(d.versions || []))
    .catch(() => setVersions([]))
  useEffect(() => { load() }, [router.routerId]) // eslint-disable-line react-hooks/exhaustive-deps

  async function runNow() {
    setBusy(true); setMsg(null); setDiff(null); setViewing(null)
    try {
      const r = await api(`/admin/router-backups/${router.routerId}/run`, { method: 'POST', auth })
      setMsg({ ok: r.ok, text: r.message })
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  async function show(v) {
    setDiff(null)
    const d = await api(`/admin/router-backups/version/${v.id}`, { auth })
    setViewing(d)
  }

  async function compare(newer, older) {
    setViewing(null)
    const d = await api(`/admin/router-backups/diff?from=${older.id}&to=${newer.id}`, { auth })
    setDiff({ ...d, newer, older })
  }

  /**
   * Fetched rather than linked, because the endpoint needs the Authorization
   * header and a plain anchor cannot carry one. The name comes from the server's
   * Content-Disposition so the extension keeps telling the truth about whether
   * this file can be restored.
   */
  async function download(v) {
    setMsg(null)
    try {
      const res = await fetch(`/api/admin/router-backups/version/${v.id}/download`,
        { headers: { Authorization: auth } })
      if (!res.ok) throw new Error(`Download failed (${res.status})`)
      const named = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') || '')
      const blob = await res.blob()
      const href = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = href
      a.download = named ? named[1] : `router-${v.id}.txt`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(href)
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-start justify-center p-4 z-50 overflow-y-auto">
      <div className="bg-surface rounded-xl w-full max-w-3xl my-8 p-5 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-lg font-semibold truncate">{router.name}</p>
            <p className="text-xs font-mono text-on-surface-variant">{router.host}</p>
          </div>
          <button type="button" onClick={onClose} className="cursor-pointer"><Icon name="close" /></button>
        </div>

        {router.error && (
          <div className="rounded-lg border border-error/40 bg-error-container/30 p-3">
            <p className="text-sm text-on-error-container">
              <strong>The last attempt failed.</strong> {router.error}
            </p>
          </div>
        )}

        <PrimaryButton disabled={busy} onClick={runNow}>
          {busy ? 'Reading the router…' : 'Back it up now'}
        </PrimaryButton>
        {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}

        {versions === null ? <Skeleton className="h-32" /> : versions.length === 0 ? (
          <p className="text-sm text-on-surface-variant">
            Nothing saved for this router yet.
          </p>
        ) : (
          <div className="space-y-2">
            <p className="text-sm font-semibold">
              {versions.length} version{versions.length === 1 ? '' : 's'} saved
            </p>
            {/* A version per change, so this list is the router's history of
                changes rather than a pile of identical nightly copies. */}
            <ul className="divide-y divide-outline-variant/40 rounded-lg border border-outline-variant">
              {versions.map((v, i) => (
                <li key={v.id} className="p-3 flex items-start justify-between gap-3 flex-wrap">
                  <div className="min-w-0">
                    <p className="text-sm font-medium">
                      {i === 0 ? 'Running now' : 'Was running'} &middot; from {relativeTime(v.firstSeenAt)}
                    </p>
                    <p className="text-xs text-on-surface-variant">
                      {v.lineCount} lines &middot; {bytes(v.byteCount)} &middot;{' '}
                      {v.method === 'EXPORT'
                        ? 'full export — can be restored'
                        : 'section read — a record, not a restore file'}
                      {i === 0 && ` · last confirmed ${relativeTime(v.lastSeenAt)}`}
                    </p>
                  </div>
                  <div className="flex gap-2 shrink-0">
                    {i < versions.length - 1 && (
                      <button type="button" onClick={() => compare(v, versions[i + 1])}
                        className="text-primary text-sm cursor-pointer hover:underline">
                        What changed
                      </button>
                    )}
                    <button type="button" onClick={() => show(v)}
                      className="text-primary text-sm cursor-pointer hover:underline">
                      View
                    </button>
                    <button type="button" onClick={() => download(v)}
                      className="text-primary text-sm cursor-pointer hover:underline">
                      Download
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}

        {diff && (
          <div className="space-y-2">
            <p className="text-sm font-semibold">
              What changed on {new Date(diff.newer.firstSeenAt).toLocaleString()}
              <span className="font-normal text-on-surface-variant">
                {' '}&mdash; {diff.added} added, {diff.removed} removed
              </span>
            </p>
            <DiffView lines={diff.lines} />
          </div>
        )}

        {viewing && (
          <div className="space-y-2">
            <p className="text-sm font-semibold">
              The configuration as of {new Date(viewing.firstSeenAt).toLocaleString()}
            </p>
            <pre className="text-xs font-mono bg-surface-container-high rounded-lg p-3 overflow-x-auto max-h-96 overflow-y-auto whitespace-pre">
              {viewing.content}
            </pre>
          </div>
        )}
      </div>
    </div>
  )
}

export default function RouterBackupsPage({ auth }) {
  const [data, setData] = useState(null)
  const [open, setOpen] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/router-backups', { auth })
    .then(setData).catch(() => setData({ routers: [] }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function runAll() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/router-backups/run', { method: 'POST', auth })
      setMsg({
        ok: r.failed.length === 0,
        text: `${r.backedUp} reached, ${r.changed} changed`
          + (r.failed.length ? `. Could not reach: ${r.failed.join(', ')}.` : '.'),
      })
      load()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  if (!data) return <Skeleton className="h-64" />

  const rows = data.routers || []
  const failing = rows.filter((r) => r.error).length
  const never = rows.filter((r) => !r.lastBackupAt && !r.error).length

  return (
    <>
      <PageHeader title="Router backups"
        subtitle="A copy of each router's configuration, kept off the router.">
        <PrimaryButton disabled={busy} onClick={runAll}>
          {busy ? 'Reading…' : 'Back up all now'}
        </PrimaryButton>
      </PageHeader>

      {rows.length === 0 ? (
        <div className="rounded-lg border border-outline-variant p-6 text-center">
          <Icon name="backup" className="text-[32px]! text-on-surface-variant" />
          <p className="text-base font-semibold mt-2">No routers to back up</p>
          <p className="text-sm text-on-surface-variant mt-1">Add one under Routers first.</p>
        </div>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <StatCard label="Routers" value={rows.length} />
            <StatCard label="Backups failing" value={failing}
              accent={failing > 0 ? 'border-t-error' : undefined} />
            <StatCard label="Never backed up" value={never} />
          </div>

          {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}

          <div className="overflow-x-auto rounded-lg border border-outline-variant">
            <table className="w-full text-sm">
              <thead className="bg-surface-container-low text-on-surface-variant">
                <tr>
                  <th className="text-left font-medium px-3 py-2">Router</th>
                  <th className="text-left font-medium px-3 py-2">Last copy</th>
                  <th className="text-left font-medium px-3 py-2">Config unchanged since</th>
                  <th className="text-left font-medium px-3 py-2">Versions</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-outline-variant/40">
                {rows.map((r) => (
                  <tr key={r.routerId} className="hover:bg-surface-container-low">
                    <td className="px-3 py-2">
                      <p className="font-medium">{r.name}</p>
                      <p className="text-xs font-mono text-on-surface-variant">{r.host}</p>
                    </td>
                    <td className="px-3 py-2"><Freshness row={r} /></td>
                    <td className="px-3 py-2 text-on-surface-variant">
                      {r.currentSince ? relativeTime(r.currentSince) : '—'}
                    </td>
                    <td className="px-3 py-2 text-on-surface-variant">{r.versions || 0}</td>
                    <td className="px-3 py-2 text-right">
                      <button type="button" onClick={() => setOpen(r)}
                        className="text-primary text-sm cursor-pointer hover:underline">
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="text-xs text-on-surface-variant flex items-start gap-2">
            <Icon name="info" className="text-[16px]! mt-0.5" />
            Taken nightly at 02:40. Only changes are stored, so a router nobody has
            touched keeps one version and the list stays a history of changes. Passwords
            are left out on purpose &mdash; Zidi already holds the customer side and can
            write it back, and what cannot be regenerated is the firewall, queues and
            routes somebody built by hand.
          </p>
        </div>
      )}

      {open && <RouterHistory auth={auth} router={open}
        onClose={() => setOpen(null)} onChanged={load} />}
    </>
  )
}
