import { useEffect, useMemo, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, StatCard,
  fmtDate, relativeTime, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

const ROLES = [
  {
    key: 'OWNER',
    label: 'Owner',
    blurb: 'Everything, including creating logins and changing M-Pesa or SMS credentials.',
  },
  {
    key: 'MANAGER',
    label: 'Manager',
    blurb: 'Runs the business day to day. Cannot create logins or touch gateway credentials.',
  },
  {
    key: 'ACCOUNTANT',
    label: 'Accountant',
    blurb: 'Billing, payments, invoices, expenses and analytics. Nothing else.',
  },
  {
    key: 'SUPPORT',
    label: 'Support',
    blurb: 'Tickets, subscribers and issuing vouchers. Cannot set prices or see money.',
  },
]

// Plain words for the permission codes the API returns.
const PERMISSION_LABELS = {
  STAFF: 'Manage staff',
  SETTINGS: 'Settings & credentials',
  FINANCE: 'Money',
  CUSTOMERS: 'Customers & tickets',
  NETWORK: 'Routers & plant',
  OUTREACH: 'Campaigns',
  SELL: 'Vouchers',
  PRICING: 'Set prices',
}

const ROLE_STYLES = {
  OWNER: 'bg-primary-container text-on-primary-container',
  MANAGER: 'bg-secondary-container text-on-secondary-container',
  ACCOUNTANT: 'bg-[#f59e0b]/10 text-[#b45309] border border-[#f59e0b]/20',
  SUPPORT: 'bg-surface-container-high text-on-surface-variant',
}

function AddStaffForm({ auth, onCancel, onCreated }) {
  const [form, setForm] = useState({
    fullName: '', username: '', password: '', phoneNumber: '', email: '', role: 'SUPPORT',
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  // Suggest a username from the name, but let it be overridden.
  const suggested = form.fullName.trim().toLowerCase().split(/\s+/)[0]?.replace(/[^a-z0-9]/g, '') || ''

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api('/admin/staff', {
        method: 'POST',
        auth,
        body: {
          ...form,
          username: (form.username || suggested).toLowerCase(),
          phoneNumber: form.phoneNumber.replace(/\D/g, '') || null,
          email: form.email || null,
        },
      })
      onCreated()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mb-6 p-5 rounded-lg bg-surface-container-lowest border border-outline-variant space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div>
          <label className={LABEL_CLS}>Full name</label>
          <input className={INPUT_CLS} required value={form.fullName}
            onChange={(e) => set({ fullName: e.target.value })} />
        </div>
        <div>
          <label className={LABEL_CLS}>Username</label>
          <input className={INPUT_CLS} pattern="[a-z0-9._-]{3,20}" placeholder={suggested || 'e.g. grace'}
            value={form.username} onChange={(e) => set({ username: e.target.value.toLowerCase() })} />
          <p className="text-xs text-on-surface-variant mt-1">Lowercase, 3–20 characters.</p>
        </div>
        <div>
          <label className={LABEL_CLS}>Password</label>
          <input className={INPUT_CLS} required minLength={8} type="text" value={form.password}
            onChange={(e) => set({ password: e.target.value })} />
          <p className="text-xs text-on-surface-variant mt-1">At least 8 characters — write it down for them.</p>
        </div>
        <div>
          <label className={LABEL_CLS}>Phone</label>
          <input className={INPUT_CLS} placeholder="0712345678" value={form.phoneNumber}
            onChange={(e) => set({ phoneNumber: e.target.value })} />
        </div>
      </div>

      <div>
        <label className={LABEL_CLS}>Role</label>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
          {ROLES.map((r) => (
            <label key={r.key}
              className={`flex items-start gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                form.role === r.key
                  ? 'border-primary bg-primary-container/15'
                  : 'border-outline-variant hover:bg-surface-container-high'
              }`}>
              <input type="radio" name="role" className="mt-1" checked={form.role === r.key}
                onChange={() => set({ role: r.key })} />
              <span>
                <span className="text-sm font-semibold block">{r.label}</span>
                <span className="text-xs text-on-surface-variant">{r.blurb}</span>
              </span>
            </label>
          ))}
        </div>
      </div>

      {error && <p className="text-sm text-error">{error}</p>}

      <div className="flex gap-2">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create login'}</PrimaryButton>
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
    </form>
  )
}

export default function StaffPage({ auth, me }) {
  const [rows, setRows] = useState(null)
  const [showAdd, setShowAdd] = useState(false)
  const [msg, setMsg] = useState(null)
  const [resetFor, setResetFor] = useState(null)
  const [newPassword, setNewPassword] = useState('')

  const load = () => api('/admin/staff', { auth }).then(setRows).catch(() => setRows([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const owners = useMemo(
    () => (rows || []).filter((r) => r.active && r.role === 'OWNER').length,
    [rows]
  )

  async function act(id, path, body, method = 'PATCH') {
    try {
      await api(`/admin/staff/${id}${path}`, { method, auth, body })
      setMsg(null)
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  async function resetPassword(id) {
    if (newPassword.length < 8) {
      setMsg({ ok: false, text: 'A password needs at least 8 characters.' })
      return
    }
    try {
      const res = await api(`/admin/staff/${id}/password`, {
        method: 'PATCH', auth, body: { password: newPassword },
      })
      setMsg({ ok: true, text: res.message })
      setResetFor(null)
      setNewPassword('')
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  async function remove(row) {
    if (!confirm(`Remove ${row.fullName}'s login? They will not be able to sign in again.`)) return
    await act(row.id, '', null, 'DELETE')
  }

  if (rows === null) return <Skeleton className="h-64" />

  return (
    <div>
      <PageHeader title="Staff" subtitle="Who can sign in to the office side, and what each of them may do.">
        <PrimaryButton onClick={() => setShowAdd(!showAdd)}>
          <Icon name="person_add" /> Add Login
        </PrimaryButton>
      </PageHeader>

      {me?.breakGlass && (
        <div className="mb-6 p-4 rounded-lg bg-[#f59e0b]/10 border border-[#f59e0b]/30">
          <p className="text-sm text-[#b45309]">
            <strong>You are signed in with the fallback account from the config file.</strong> It works, but
            everything you do is recorded as "{me.username}" rather than a person. Create a named Owner login
            below and use that from now on — the fallback stays available in case you ever get locked out.
          </p>
        </div>
      )}

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-2.5 mb-4">
        <StatCard label="Logins" value={rows.length} hint={`${rows.filter((r) => r.active).length} active`} accent="border-l-primary" />
        <StatCard label="Owners" value={owners} hint="can manage staff" />
        <StatCard
          label="Accountants"
          value={rows.filter((r) => r.role === 'ACCOUNTANT').length}
          hint="money only"
        />
        <StatCard
          label="Support"
          value={rows.filter((r) => r.role === 'SUPPORT').length}
          hint="customers only"
        />
      </div>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {showAdd && (
        <AddStaffForm
          auth={auth}
          onCancel={() => setShowAdd(false)}
          onCreated={() => { setShowAdd(false); setMsg({ ok: true, text: 'Login created.' }); load() }}
        />
      )}

      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        <div className="overflow-x-auto table-scroll">
          <table className="data-table w-full">
            <thead>
              <tr>
                <th>Person</th>
                <th>Role</th>
                <th>Can reach</th>
                <th>Last signed in</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const isMe = r.username === me?.username
                return (
                  <tr key={r.id} className={r.active ? '' : 'opacity-60'}>
                    <td>
                      <p className="font-semibold">
                        {r.fullName}
                        {isMe && <span className="ml-2 text-xs text-primary font-normal">you</span>}
                      </p>
                      <p className="text-xs text-on-surface-variant font-mono">{r.username}</p>
                      {r.seeded && (
                        <p className="text-[10px] text-on-surface-variant">from the config file</p>
                      )}
                      {resetFor === r.id && (
                        <div className="mt-2 flex gap-2 items-center">
                          <input
                            className="bg-surface border border-outline-variant rounded-lg px-2 py-1 text-sm w-40"
                            placeholder="New password"
                            value={newPassword}
                            onChange={(e) => setNewPassword(e.target.value)}
                          />
                          <button onClick={() => resetPassword(r.id)}
                            className="px-2.5 py-1 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer">
                            Set
                          </button>
                          <button onClick={() => { setResetFor(null); setNewPassword('') }}
                            className="text-xs text-on-surface-variant cursor-pointer hover:text-on-surface">
                            Cancel
                          </button>
                        </div>
                      )}
                    </td>
                    <td>
                      <select
                        value={r.role}
                        onChange={(e) => act(r.id, '/role', { role: e.target.value })}
                        aria-label={`Role for ${r.fullName}`}
                        className={`px-2 py-1 rounded-full text-xs font-semibold cursor-pointer ${ROLE_STYLES[r.role]}`}
                      >
                        {ROLES.map((x) => <option key={x.key} value={x.key}>{x.label}</option>)}
                      </select>
                    </td>
                    <td>
                      <div className="flex flex-wrap gap-1">
                        {r.permissions.map((p) => (
                          <span key={p} className="px-2 py-0.5 rounded-full bg-surface-container-high text-[10px] font-semibold">
                            {PERMISSION_LABELS[p] || p}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="text-xs">
                      {r.lastLoginAt
                        ? <span>{fmtDate(r.lastLoginAt)}<br /><span className="text-on-surface-variant">{relativeTime(r.lastLoginAt)}</span></span>
                        : <span className="text-on-surface-variant">never</span>}
                    </td>
                    <td className="text-right">
                      <div className="flex gap-1.5 justify-end">
                        <button onClick={() => setResetFor(resetFor === r.id ? null : r.id)}
                          className="px-2.5 py-1.5 rounded-lg border border-outline-variant text-xs cursor-pointer hover:bg-surface-container-high">
                          Password
                        </button>
                        <button
                          onClick={() => act(r.id, '/toggle')}
                          disabled={isMe}
                          title={isMe ? 'You cannot disable your own account' : undefined}
                          className="px-2.5 py-1.5 rounded-lg border border-outline-variant text-xs cursor-pointer hover:bg-surface-container-high disabled:opacity-40 disabled:cursor-default"
                        >
                          {r.active ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          onClick={() => remove(r)}
                          disabled={isMe}
                          aria-label={`Remove ${r.fullName}`}
                          className="px-2 py-1.5 rounded-lg border border-outline-variant text-on-surface-variant cursor-pointer hover:bg-error-container hover:text-on-error-container disabled:opacity-40 disabled:cursor-default"
                        >
                          <Icon name="delete" className="text-[16px]!" />
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>

      <p className="mt-3 text-xs text-on-surface-variant">
        The last active owner cannot be demoted, disabled or removed — otherwise one click could leave nobody
        able to manage logins.
      </p>
    </div>
  )
}
