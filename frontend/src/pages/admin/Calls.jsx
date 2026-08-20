import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard, Toggle, relativeTime, fmtKES,
  INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * The support phone line.
 *
 * Three things on one page because they are one job: who is on the rota, who is
 * on the phone right now, and what was said on every call before this one.
 *
 * The live-call banner is the reason this page is worth having open all day. A
 * customer rings the business number, and before anybody picks up their name,
 * their package and whether they are paid up are already on screen -- which is
 * the difference between "hello, can I take your name and account number" and
 * "hello Mary, I can see your line went down this morning".
 *
 * Two things are stated in plain words wherever they surface, because both are
 * surprising and both make people think the feature is broken: pressing Call
 * rings YOUR phone first, and a recorded call is personal data.
 */

function secs(n) {
  if (n === null || n === undefined) return '—'
  if (n < 60) return `${n}s`
  return `${Math.floor(n / 60)}m ${String(n % 60).padStart(2, '0')}s`
}

const CALL_STATUS = {
  RINGING: ['ringing', 'bg-primary-fixed/40 text-primary', 'bg-primary animate-pulse'],
  ANSWERED: ['on the call', 'bg-secondary-container text-on-secondary-container', 'bg-secondary animate-pulse'],
  COMPLETED: ['done', 'bg-surface-container-high text-on-surface-variant', 'bg-outline'],
  MISSED: ['missed', 'bg-error-container text-on-error-container', 'bg-error'],
  FAILED: ['failed', 'bg-[#fef3c7] text-[#78350f]', 'bg-[#d97706]'],
}

function StatusPill({ status }) {
  const [label, cls, dot] = CALL_STATUS[status] || ['—', 'bg-surface-container-high text-on-surface-variant', 'bg-outline']
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${cls}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${dot}`}></span>{label}
    </span>
  )
}

/** Whoever is on the phone right now, and what an agent needs to know about them. */
function LiveCall({ call }) {
  const overdue = call.paidUntil && new Date(call.paidUntil) < new Date()
  return (
    <div className="rounded-lg border-2 border-primary bg-primary-fixed/10 p-4">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Icon name={call.direction === 'INBOUND' ? 'call_received' : 'call_made'}
              className="text-primary" />
            <StatusPill status={call.status} />
            <span className="text-xs text-on-surface-variant">
              {relativeTime(call.startedAt)}
            </span>
          </div>
          <p className="text-xl font-bold mt-1.5">
            {call.customer || call.callerNumber || 'Unknown caller'}
          </p>
          {call.customer ? (
            <p className="text-sm text-on-surface-variant">
              <span className="font-mono">{call.pppoeUsername}</span>
              {' · '}
              {/* The two facts that decide the next sentence out of the agent's
                  mouth. Anything else can be looked up while they talk. */}
              <span className={overdue ? 'text-error font-semibold' : ''}>
                {call.accountStatus === 'SUSPENDED' ? 'suspended'
                  : overdue ? 'payment overdue' : 'paid up'}
              </span>
            </p>
          ) : (
            <p className="text-sm text-on-surface-variant">
              Not a number we recognise &mdash; ask who is calling.
            </p>
          )}
        </div>
        <p className="font-mono text-sm text-on-surface-variant">{call.callerNumber}</p>
      </div>
    </div>
  )
}

function Settings({ auth, onSaved }) {
  const [cfg, setCfg] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [copied, setCopied] = useState(false)

  const load = () => api('/admin/calls/settings', { auth }).then(setCfg).catch(() => {})
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!cfg) return <Skeleton className="h-48" />
  const set = (patch) => setCfg({ ...cfg, ...patch })

  async function save() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/calls/settings', {
        method: 'PUT', auth,
        body: {
          enabled: cfg.enabled,
          virtualNumber: cfg.virtualNumber || null,
          voiceBaseUrl: cfg.voiceBaseUrl,
          greeting: cfg.greeting || null,
          noAnswerMessage: cfg.noAnswerMessage || null,
          recordCalls: cfg.recordCalls,
          ringSeconds: Number(cfg.ringSeconds) || 25,
        },
      })
      setCfg(r)
      setMsg({ ok: true, text: 'Saved.' })
      onSaved()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  async function rotate() {
    if (!window.confirm('This changes the webhook address. Incoming calls stop reaching '
      + 'anybody until you paste the new one into your provider dashboard. Continue?')) return
    const r = await api('/admin/calls/settings/rotate-token', { method: 'POST', auth })
    setCfg(r)
    setMsg({ ok: false, text: r.message })
  }

  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-outline-variant p-4 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm font-semibold">The phone line</p>
            <p className="text-xs text-on-surface-variant mt-1">
              Uses the same username and API key as your SMS provider, so there is no
              second key to enter. You do need to rent a voice number from them.
            </p>
          </div>
          <Toggle checked={cfg.enabled} onChange={(v) => set({ enabled: v })} />
        </div>

        {cfg.whyNotUsable && (
          <p className="text-sm text-[#b45309] flex items-start gap-2">
            <Icon name="warning" className="text-[18px]! mt-0.5" />
            {cfg.whyNotUsable}
          </p>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Your voice number</label>
            <input className={INPUT_CLS} value={cfg.virtualNumber || ''} placeholder="+254203000000"
              onChange={(e) => set({ virtualNumber: e.target.value })} />
            <p className="text-xs text-on-surface-variant mt-1">
              The number customers ring, and the number they see when you ring them.
            </p>
          </div>
          <div>
            <label className={LABEL_CLS}>Ring each agent for (seconds)</label>
            <input className={INPUT_CLS} type="number" min="10" max="120" value={cfg.ringSeconds}
              onChange={(e) => set({ ringSeconds: e.target.value })} />
          </div>
          <div className="sm:col-span-2">
            <label className={LABEL_CLS}>What a caller hears first</label>
            <input className={INPUT_CLS} value={cfg.greeting || ''}
              onChange={(e) => set({ greeting: e.target.value })} />
            <p className="text-xs text-on-surface-variant mt-1">
              Without this a caller hears silence while the phones ring, and many hang up.
            </p>
          </div>
          <div className="sm:col-span-2">
            <label className={LABEL_CLS}>What they hear when nobody is free</label>
            <input className={INPUT_CLS} value={cfg.noAnswerMessage || ''}
              onChange={(e) => set({ noAnswerMessage: e.target.value })} />
          </div>
          <div className="sm:col-span-2">
            <label className={LABEL_CLS}>Provider API address</label>
            <input className={`${INPUT_CLS} font-mono text-xs`} value={cfg.voiceBaseUrl}
              onChange={(e) => set({ voiceBaseUrl: e.target.value })} />
          </div>
        </div>

        <div className="rounded-lg border border-outline-variant p-3 space-y-2">
          <div className="flex items-start justify-between gap-4">
            <div>
              <p className="text-sm font-semibold">Record calls</p>
              {/* Not a shrug. Somebody has to decide this rather than inherit it. */}
              <p className="text-xs text-on-surface-variant mt-1">
                Off by default on purpose. A recording is personal data: you need somewhere
                to keep it, a reason to keep it, and in most places you must tell the caller
                you are recording &mdash; put that in the greeting above.
              </p>
            </div>
            <Toggle checked={cfg.recordCalls} onChange={(v) => set({ recordCalls: v })} />
          </div>
        </div>

        <PrimaryButton disabled={busy} onClick={save}>
          {busy ? 'Saving…' : 'Save'}
        </PrimaryButton>
        {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      </div>

      <div className="rounded-lg border border-outline-variant p-4 space-y-2">
        <p className="text-sm font-semibold">Paste this into your provider dashboard</p>
        <p className="text-xs text-on-surface-variant">
          Set it as the voice callback URL for your number. Until you do, incoming calls
          reach nobody. The address contains a secret, so treat it like a password.
        </p>
        <div className="flex gap-2 items-center">
          <code className="flex-1 min-w-0 text-xs font-mono bg-surface-container-high rounded-lg px-3 py-2 break-all">
            {cfg.callbackUrl}
          </code>
          <button type="button"
            onClick={() => {
              navigator.clipboard?.writeText(cfg.callbackUrl)
              setCopied(true)
              setTimeout(() => setCopied(false), 1500)
            }}
            className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high whitespace-nowrap">
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
        <button type="button" onClick={rotate}
          className="text-sm text-error cursor-pointer hover:underline">
          Change the secret
        </button>
      </div>
    </div>
  )
}

function Rota({ auth, onChanged }) {
  const [agents, setAgents] = useState(null)
  const [editing, setEditing] = useState(null)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/calls/agents', { auth })
    .then((d) => setAgents(d.agents || [])).catch(() => setAgents([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save(agent) {
    setMsg(null)
    try {
      const r = await api('/admin/calls/agents', { method: 'POST', auth, body: agent })
      setMsg({ ok: true, text: r.message })
      setEditing(null)
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  async function act(id, what) {
    try {
      await api(`/admin/calls/agents/${id}${what}`,
        { method: what === '' ? 'DELETE' : 'POST', auth })
      load(); onChanged()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    }
  }

  if (!agents) return <Skeleton className="h-32" />

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-sm font-semibold">Who answers the phone</p>
          <p className="text-xs text-on-surface-variant mt-1">
            Rung in this order, one after another &mdash; not all at once, so nobody picks
            up a dead line. They do not need a login to be on the rota.
          </p>
        </div>
        <PrimaryButton onClick={() => setEditing({ name: '', phoneNumber: '', priority: 10, active: true })}>
          Add
        </PrimaryButton>
      </div>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}

      {editing && (
        <div className="rounded-lg border border-primary p-3 grid grid-cols-1 sm:grid-cols-4 gap-3 items-end">
          <div>
            <label className={LABEL_CLS}>Name</label>
            <input className={INPUT_CLS} value={editing.name}
              onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Phone</label>
            <input className={INPUT_CLS} value={editing.phoneNumber} placeholder="0700000001"
              onChange={(e) => setEditing({ ...editing, phoneNumber: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Ring order</label>
            <input className={INPUT_CLS} type="number" min="1" value={editing.priority}
              onChange={(e) => setEditing({ ...editing, priority: Number(e.target.value) })} />
          </div>
          <div className="flex gap-2">
            <PrimaryButton disabled={!editing.name || !editing.phoneNumber}
              onClick={() => save(editing)}>Save</PrimaryButton>
            <button type="button" onClick={() => setEditing(null)}
              className="px-3 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer">
              Cancel
            </button>
          </div>
        </div>
      )}

      {agents.length === 0 ? (
        <p className="text-sm text-on-surface-variant">
          Nobody on the rota yet, so an incoming call has nowhere to go.
        </p>
      ) : (
        <ul className="divide-y divide-outline-variant/40 rounded-lg border border-outline-variant">
          {agents.map((a) => (
            <li key={a.id} className="p-3 flex items-center justify-between gap-3 flex-wrap">
              <div className="min-w-0">
                <p className="font-medium flex items-center gap-2">
                  <span className="text-xs text-on-surface-variant font-mono">#{a.priority}</span>
                  {a.name}
                  {!a.active && (
                    <span className="text-xs px-1.5 py-0.5 rounded-full bg-surface-container-high text-on-surface-variant">
                      off the rota
                    </span>
                  )}
                  {a.active && !a.available && (
                    <span className="text-xs px-1.5 py-0.5 rounded-full bg-secondary-container text-on-secondary-container">
                      on a call
                    </span>
                  )}
                </p>
                <p className="text-xs font-mono text-on-surface-variant">{a.phoneNumber}</p>
              </div>
              <div className="flex gap-3 items-center shrink-0">
                {a.active && !a.available && (
                  // A callback that never arrived leaves somebody stuck marked
                  // busy. This is the way out that does not need a restart.
                  <button type="button" onClick={() => act(a.id, '/free')}
                    className="text-primary text-sm cursor-pointer hover:underline">
                    Put back on
                  </button>
                )}
                <button type="button" onClick={() => setEditing(a)}
                  className="text-primary text-sm cursor-pointer hover:underline">Edit</button>
                <button type="button"
                  onClick={() => window.confirm(`Remove ${a.name} from the rota?`) && act(a.id, '')}
                  className="text-error text-sm cursor-pointer hover:underline">Remove</button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function DialPanel({ auth, agents, customers, onDialled }) {
  const [agentId, setAgentId] = useState('')
  const [subscriberId, setSubscriberId] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const free = agents.filter((a) => a.active && a.available)

  async function dial() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/calls/dial', {
        method: 'POST', auth,
        body: { agentId: Number(agentId), subscriberId: Number(subscriberId) },
      })
      setMsg({ ok: r.ok, text: r.message })
      if (r.ok) onDialled()
    } catch (e) {
      setMsg({ ok: false, text: e.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="rounded-lg border border-outline-variant p-4 space-y-3">
      <div>
        <p className="text-sm font-semibold">Call a customer</p>
        {/* The single most surprising thing about this feature. Said before the
            button, not after. */}
        <p className="text-xs text-on-surface-variant mt-1">
          Your phone rings first. Answer it, and you will be connected to the customer
          &mdash; who sees your business number, not your mobile.
        </p>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 items-end">
        <div>
          <label className={LABEL_CLS}>Who is calling</label>
          <select className={INPUT_CLS} value={agentId} onChange={(e) => setAgentId(e.target.value)}>
            <option value="">Choose an agent</option>
            {free.map((a) => <option key={a.id} value={String(a.id)}>{a.name}</option>)}
          </select>
        </div>
        <div>
          <label className={LABEL_CLS}>Customer</label>
          <select className={INPUT_CLS} value={subscriberId}
            onChange={(e) => setSubscriberId(e.target.value)}>
            <option value="">Choose a customer</option>
            {customers.filter((c) => c.phoneNumber).map((c) => (
              <option key={c.id} value={String(c.id)}>{c.fullName} — {c.phoneNumber}</option>
            ))}
          </select>
        </div>
        <PrimaryButton disabled={busy || !agentId || !subscriberId} onClick={dial}>
          {busy ? 'Ringing…' : 'Call'}
        </PrimaryButton>
      </div>
      {free.length === 0 && agents.length > 0 && (
        <p className="text-xs text-on-surface-variant">
          Everybody on the rota is either off duty or already on a call.
        </p>
      )}
      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
    </div>
  )
}

function CallLog({ auth, calls, onSaved }) {
  const [editing, setEditing] = useState(null)
  const [notes, setNotes] = useState('')

  async function save(id) {
    await api(`/admin/calls/${id}/notes`, { method: 'POST', auth, body: { notes } })
    setEditing(null)
    onSaved()
  }

  if (calls.length === 0) {
    return (
      <div className="rounded-lg border border-outline-variant p-6 text-center">
        <Icon name="phone_in_talk" className="text-[32px]! text-on-surface-variant" />
        <p className="text-base font-semibold mt-2">No calls yet</p>
        <p className="text-sm text-on-surface-variant mt-1">
          Calls appear here once the line is set up and somebody rings it.
        </p>
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-outline-variant">
      <table className="w-full text-sm">
        <thead className="bg-surface-container-low text-on-surface-variant">
          <tr>
            <th className="text-left font-medium px-3 py-2">When</th>
            <th className="text-left font-medium px-3 py-2">Who</th>
            <th className="text-left font-medium px-3 py-2">How it went</th>
            <th className="text-left font-medium px-3 py-2">Length</th>
            <th className="text-left font-medium px-3 py-2">Notes</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-outline-variant/40">
          {calls.map((c) => (
            <tr key={c.id} className="hover:bg-surface-container-low align-top">
              <td className="px-3 py-2 whitespace-nowrap">
                <span className="flex items-center gap-1.5">
                  <Icon name={c.direction === 'INBOUND' ? 'call_received' : 'call_made'}
                    className="text-[16px]! text-on-surface-variant" />
                  {relativeTime(c.startedAt)}
                </span>
              </td>
              <td className="px-3 py-2">
                <p className="font-medium">{c.customer || 'Unknown'}</p>
                <p className="text-xs font-mono text-on-surface-variant">
                  {c.direction === 'INBOUND' ? c.callerNumber : c.destinationNumber}
                </p>
              </td>
              <td className="px-3 py-2">
                <StatusPill status={c.status} />
                {/* The provider's own words. A lossy translation of "why did
                    this fail" helps nobody. */}
                {c.hangupCause && (
                  <p className="text-xs text-on-surface-variant mt-0.5">{c.hangupCause}</p>
                )}
              </td>
              <td className="px-3 py-2 whitespace-nowrap">
                {secs(c.durationSeconds)}
                {c.cost != null && (
                  <span className="block text-xs text-on-surface-variant">
                    {c.currency} {c.cost}
                  </span>
                )}
                {c.recordingUrl && (
                  <a href={c.recordingUrl} target="_blank" rel="noreferrer"
                    className="block text-xs text-primary hover:underline">listen</a>
                )}
              </td>
              <td className="px-3 py-2 max-w-xs">
                {editing === c.id ? (
                  <div className="flex gap-2">
                    <input className={INPUT_CLS} value={notes} autoFocus
                      onChange={(e) => setNotes(e.target.value)} />
                    <button type="button" onClick={() => save(c.id)}
                      className="text-primary text-sm cursor-pointer">Save</button>
                  </div>
                ) : (
                  <button type="button"
                    onClick={() => { setEditing(c.id); setNotes(c.notes || '') }}
                    className="text-left text-sm cursor-pointer hover:underline w-full">
                    {c.notes || <span className="text-on-surface-variant">add a note</span>}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const TABS = [['log', 'Calls', 'phone_in_talk'], ['rota', 'Rota', 'group'], ['setup', 'Setup', 'settings']]

export default function CallsPage({ auth }) {
  const [tab, setTab] = useState('log')
  const [data, setData] = useState(null)
  const [agents, setAgents] = useState([])
  const [customers, setCustomers] = useState([])
  const [live, setLive] = useState([])

  const load = () => {
    api('/admin/calls', { auth }).then(setData).catch(() => setData({ calls: [] }))
    api('/admin/calls/agents', { auth }).then((d) => setAgents(d.agents || [])).catch(() => {})
  }
  useEffect(() => {
    load()
    api('/admin/subscribers', { auth }).then((d) => setCustomers(d || [])).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  // Polled rather than pushed. Five seconds is fast enough that the account is
  // up before an agent finishes saying hello, and this is the cheapest endpoint
  // in the admin.
  useEffect(() => {
    const tick = () => api('/admin/calls/live', { auth })
      .then((d) => setLive(d.calls || [])).catch(() => {})
    tick()
    const t = setInterval(tick, 5000)
    return () => clearInterval(t)
  }, [auth])

  const missed = useMemo(
    () => (data?.calls || []).filter((c) => c.status === 'MISSED').length, [data])

  if (!data) return <Skeleton className="h-64" />

  return (
    <>
      <PageHeader title="Phone line"
        subtitle="One number for support, and a record of every call on it." />

      <div className="space-y-4">
        {live.length > 0 && (
          <div className="space-y-2">
            {live.map((c) => <LiveCall key={c.id} call={c} />)}
          </div>
        )}

        {!data.usable && (
          <div className="rounded-lg border border-[#d97706]/40 bg-[#fffbeb] p-3">
            <p className="text-sm text-[#78350f] flex items-start gap-2">
              <Icon name="info" className="text-[18px]! mt-0.5" />
              <span>
                <strong>The line is not live yet.</strong> {data.whyNotUsable}{' '}
                <button type="button" onClick={() => setTab('setup')}
                  className="underline cursor-pointer">Open setup</button>.
              </span>
            </p>
          </div>
        )}

        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <StatCard label="On the phone now" value={data.live} />
          <StatCard label="Missed today" value={data.missedToday}
            accent={data.missedToday > 0 ? 'border-t-error' : undefined} />
          <StatCard label="Handled today" value={data.completedToday} />
          <StatCard label="Agents free"
            value={`${data.agentsFree} of ${data.agentsOnRota}`} />
        </div>

        <div className="flex gap-1 border-b border-outline-variant">
          {TABS.map(([key, label, icon]) => (
            <button key={key} type="button" onClick={() => setTab(key)}
              className={`px-4 py-2 text-sm font-medium cursor-pointer flex items-center gap-2 border-b-2 ${
                tab === key ? 'border-primary text-primary'
                  : 'border-transparent text-on-surface-variant hover:text-on-surface'}`}>
              <Icon name={icon} className="text-[18px]!" />{label}
              {key === 'log' && missed > 0 && (
                <span className="ml-1 px-1.5 rounded-full bg-error-container text-on-error-container text-xs">
                  {missed}
                </span>
              )}
            </button>
          ))}
        </div>

        {tab === 'log' && (
          <div className="space-y-4">
            {data.usable && (
              <DialPanel auth={auth} agents={agents} customers={customers} onDialled={load} />
            )}
            <CallLog auth={auth} calls={data.calls} onSaved={load} />
          </div>
        )}
        {tab === 'rota' && <Rota auth={auth} onChanged={load} />}
        {tab === 'setup' && <Settings auth={auth} onSaved={load} />}
      </div>
    </>
  )
}
