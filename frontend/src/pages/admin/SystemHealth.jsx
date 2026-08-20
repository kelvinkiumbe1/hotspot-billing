import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, Toggle,
  fmtDate, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const SEVERITY_STYLES = {
  CRITICAL: 'bg-error-container text-on-error-container',
  WARNING: 'bg-warning/15 text-warning',
}

const DOT_STYLES = {
  ok: ['bg-secondary', 'running'],
  stale: ['bg-error', 'not running'],
  unknown: ['bg-outline-variant', 'not seen yet'],
}

function Dot({ status = 'unknown' }) {
  const [colour, label] = DOT_STYLES[status] || DOT_STYLES.unknown
  return <span className={`inline-block w-2 h-2 rounded-full shrink-0 ${colour}`} aria-label={label} />
}

function SettingsPanel({ auth, settings, onSaved }) {
  const [form, setForm] = useState(settings)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save() {
    setBusy(true)
    try {
      onSaved(await api('/admin/ops/settings', { method: 'PUT', auth, body: form }))
      setMsg({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="mb-5 p-4 rounded-lg bg-surface-container-low/60 border border-outline-variant space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <label className="flex items-start gap-3">
          <Toggle checked={form.healthWatchEnabled} onChange={(e) => set({ healthWatchEnabled: e.target.checked })} />
          <span>
            <span className="text-sm font-semibold">Watch the system</span>
            <span className="block text-xs text-on-surface-variant">Payment silence, stopped jobs, failed messages.</span>
          </span>
        </label>
        <label className="flex items-start gap-3">
          <Toggle checked={form.backupWatchEnabled} onChange={(e) => set({ backupWatchEnabled: e.target.checked })} />
          <span>
            <span className="text-sm font-semibold">Watch the backups</span>
            <span className="block text-xs text-on-surface-variant">Alert when a night is missed or a dump is unusable.</span>
          </span>
        </label>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <div>
          <label className={LABEL_CLS}>Alert after no payment for (hours)</label>
          <input className={INPUT_CLS} type="number" min="1" max="72" value={form.callbackSilenceHours}
            onChange={(e) => set({ callbackSilenceHours: Number(e.target.value) })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Quiet hours from</label>
          <input className={INPUT_CLS} type="number" min="0" max="23" value={form.quietFromHour}
            onChange={(e) => set({ quietFromHour: Number(e.target.value) })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Quiet hours until</label>
          <input className={INPUT_CLS} type="number" min="0" max="23" value={form.quietToHour}
            onChange={(e) => set({ quietToHour: Number(e.target.value) })} />
        </div>
      </div>
      <p className="text-xs text-on-surface-variant -mt-2">
        Overnight, no sales is just the middle of the night — no alarm is raised inside that window.
      </p>

      <div>
        <label className={LABEL_CLS}>External watchdog URL</label>
        <input className={INPUT_CLS} placeholder="https://hc-ping.com/your-uuid"
          value={form.heartbeatUrl || ''}
          onChange={(e) => set({ heartbeatUrl: e.target.value })} />
        <p className="mt-1 text-xs text-on-surface-variant">
          Pinged every ten minutes. The one failure this page can never report is the system being
          down — if the pings stop, that service raises the alarm instead. healthchecks.io is free
          and takes a minute to set up.
        </p>
      </div>

      <div>
        <label className={LABEL_CLS}>Expect a backup at least every (hours)</label>
        <input className={`${INPUT_CLS} max-w-[10rem]`} type="number" min="1" max="720"
          value={form.backupExpectedHours}
          onChange={(e) => set({ backupExpectedHours: Number(e.target.value) })} />
      </div>

      {msg && <p className={`text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}
      <PrimaryButton onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save settings'}</PrimaryButton>
    </div>
  )
}

/**
 * System Health — what the system knows about itself, as opposed to about the
 * network. Everything here is a failure that is otherwise silent.
 */
export default function SystemHealthPage({ auth }) {
  const [data, setData] = useState(null)
  const [checking, setChecking] = useState(false)
  const [showSettings, setShowSettings] = useState(false)

  const [incidents, setIncidents] = useState(null)

  const load = () => api('/admin/ops/health', { auth }).then(setData).catch(() => setData({ open: [], jobs: [] }))
  useEffect(() => {
    load()
    api('/admin/incidents', { auth }).then(setIncidents).catch(() => setIncidents(null))
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function checkNow() {
    setChecking(true)
    try { setData(await api('/admin/ops/health/check', { method: 'POST', auth })) } finally { setChecking(false) }
  }

  if (!data) return <Skeleton className="h-64" />

  const open = data.open || []
  const jobs = data.jobs || []
  const backups = data.backups || {}
  const critical = open.filter((a) => a.severity === 'CRITICAL').length

  return (
    <div>
      <PageHeader
        title="System health"
        subtitle="The failures that make no noise: payments that stopped arriving, jobs that stopped running, backups that stopped happening."
      >
        <div className="flex gap-2">
          <button onClick={() => setShowSettings(!showSettings)}
            className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high flex items-center gap-1.5">
            <Icon name="tune" className="text-[18px]!" /> Settings
          </button>
          <PrimaryButton onClick={checkNow} disabled={checking}>
            <Icon name="monitor_heart" /> {checking ? 'Checking…' : 'Check now'}
          </PrimaryButton>
        </div>
      </PageHeader>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Open Problems" value={open.length}
          hint={open.length ? `${critical} need attention now` : 'nothing wrong'}
          accent={open.length ? 'border-l-error' : ''} />
        <StatCard label="Background Jobs" value={`${jobs.filter((j) => j.status === 'ok').length}/${jobs.length}`}
          hint="running as expected"
          accent={jobs.some((j) => j.status === 'stale') ? 'border-l-error' : ''} />
        <StatCard label="Last Good Backup"
          value={backups.lastGoodAt ? relativeTime(backups.lastGoodAt) : 'Never'}
          hint={backups.lastVerified ? 'restore-verified' : 'not verified'}
          accent={backups.healthy ? '' : 'border-l-error'} />
        <StatCard label="Off-site Copy" value={backups.lastOffsite ? 'Yes' : 'No'}
          hint={backups.lastOffsite ? 'a copy left the machine' : 'only on this machine'}
          accent={backups.lastOffsite ? '' : 'border-l-error'} />
      </div>

      {showSettings && data.settings && (
        <SettingsPanel auth={auth} settings={data.settings}
          onSaved={(s) => setData({ ...data, settings: s })} />
      )}

      {open.length === 0 ? (
        <div className="p-8 text-center rounded-lg bg-surface-container-lowest border border-outline-variant">
          <Icon name="monitor_heart" className="text-[36px]! text-secondary/60" />
          <p className="mt-2 font-semibold">Everything is running.</p>
          <p className="mt-1 text-sm text-on-surface-variant">
            Payments are arriving, every background job is on schedule, and messages are going out.
          </p>
        </div>
      ) : (
        <div className="bg-surface-container-lowest rounded-lg border border-outline-variant divide-y divide-outline-variant">
          {open.map((a) => (
            <div key={a.id} className="p-4">
              <div className="flex items-start gap-3">
                <span className={`px-2 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap ${SEVERITY_STYLES[a.severity] || ''}`}>
                  {a.severity === 'CRITICAL' ? 'Act now' : 'Watch'}
                </span>
                <div className="min-w-0">
                  <p className="font-semibold">{a.title}</p>
                  <p className="text-sm text-on-surface-variant mt-0.5">{a.detail}</p>
                  <p className="text-xs text-on-surface-variant mt-1">Since {relativeTime(a.firstSeenAt)}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mt-6">
        <section>
          <h3 className="text-sm font-semibold mb-2 flex items-center gap-1.5">
            <Icon name="dns" className="text-[18px]! text-on-surface-variant" /> Background jobs
          </h3>
          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant divide-y divide-outline-variant">
            {jobs.map((j) => (
              <div key={j.name} className="px-4 py-2.5 flex items-center gap-2.5">
                <Dot status={j.status} />
                <span className="text-sm flex-1 capitalize">{j.label}</span>
                <span className="text-xs text-on-surface-variant whitespace-nowrap">
                  {j.lastRunAt ? relativeTime(j.lastRunAt) : 'not seen yet'}
                </span>
              </div>
            ))}
            {jobs.length === 0 && (
              <p className="px-4 py-3 text-sm text-on-surface-variant">Nothing recorded yet.</p>
            )}
          </div>
        </section>

        <section>
          <h3 className="text-sm font-semibold mb-2 flex items-center gap-1.5">
            <Icon name="backup" className="text-[18px]! text-on-surface-variant" /> Recent backups
          </h3>
          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant divide-y divide-outline-variant">
            {(backups.recent || []).slice(0, 8).map((b) => (
              <div key={b.id} className="px-4 py-2.5 flex items-center gap-2.5">
                <Dot status={b.ok ? 'ok' : 'stale'} />
                <span className="text-sm flex-1 min-w-0">
                  {b.tenant}
                  <span className="block text-xs text-on-surface-variant truncate">
                    {b.ok
                      ? `${(b.bytes / 1048576).toFixed(1)} MB`
                        + (b.verified ? ' · restore-verified' : ' · not verified')
                        + (b.offsite ? ' · off-site' : ' · on this machine only')
                      : b.error || 'failed'}
                  </span>
                </span>
                <span className="text-xs text-on-surface-variant whitespace-nowrap">{fmtDate(b.reportedAt)}</span>
              </div>
            ))}
            {(backups.recent || []).length === 0 && (
              <p className="px-4 py-3 text-sm text-on-surface-variant">
                No backup has reported in yet. Set <span className="font-mono text-xs">BACKUP_REPORT_TOKEN</span> in
                the tenant's env file so <span className="font-mono text-xs">deploy/backup.sh</span> can report each run.
              </p>
            )}
          </div>
        </section>
      </div>

      {incidents && (incidents.recent || []).length > 0 && (
        <section className="mt-6">
          <h3 className="text-sm font-semibold mb-2 flex items-center gap-1.5">
            <Icon name="wifi_off" className="text-[18px]! text-on-surface-variant" /> Network outages
          </h3>
          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant divide-y divide-outline-variant">
            {incidents.recent.slice(0, 10).map((i) => (
              <div key={i.id} className="px-4 py-2.5 flex flex-wrap items-baseline gap-x-3">
                <Dot status={i.status === 'RESOLVED' ? 'ok' : 'stale'} />
                <span className="text-sm font-medium">{i.title}</span>
                <span className="text-xs text-on-surface-variant flex-1 min-w-40">
                  {fmtDate(i.startedAt)}
                  {i.notifiedCount > 0 ? ` · ${i.notifiedCount} customer(s) told` : ' · nobody told'}
                  {i.compensatedCount > 0 ? ` · ${i.compensatedCount} credited ${i.compensatedMinutes} min` : ''}
                </span>
                <span className={`text-xs font-semibold ${i.status === 'RESOLVED' ? 'text-secondary' : 'text-error'}`}>
                  {i.status === 'RESOLVED' ? 'Resolved' : 'Ongoing'}
                </span>
              </div>
            ))}
          </div>
          <p className="mt-2 text-xs text-on-surface-variant">
            Customers on the affected routers are told once, then given the all-clear and their time back
            when it recovers. The same list is published at <span className="font-mono">/status</span>.
          </p>
        </section>
      )}

      {(data.recent || []).length > 0 && (
        <div className="mt-6">
          <h3 className="text-sm font-semibold mb-2">Recently cleared</h3>
          <div className="bg-surface-container-lowest rounded-lg border border-outline-variant divide-y divide-outline-variant">
            {data.recent.map((a) => (
              <div key={a.id} className="px-4 py-2.5 flex flex-wrap items-baseline gap-x-2">
                <span className="text-xs font-semibold">{a.title}</span>
                <span className="text-xs text-on-surface-variant flex-1 min-w-40">{a.detail}</span>
                <span className="text-xs text-on-surface-variant whitespace-nowrap">
                  cleared {a.resolvedAt ? relativeTime(a.resolvedAt) : ''}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
