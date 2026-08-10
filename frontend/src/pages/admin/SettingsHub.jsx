import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, INPUT_CLS, LABEL_CLS, Toggle,
} from '../../components/ui.jsx'
import PaymentGatewaysPage from './PaymentGateways.jsx'
import TaxSettingsPage from './TaxSettings.jsx'
import BrandingPage from './Branding.jsx'

/**
 * The settings sections, grouped the way an operator thinks about them
 * rather than the way the code is organised. `need` mirrors the server's
 * permission so nothing is shown that the API would refuse.
 */
const SECTIONS = [
  {
    group: 'General',
    items: [
      { key: 'branding', label: 'Branding', hint: 'Business name, logo, portal wording', icon: 'palette', need: 'OUTREACH' },
      { key: 'hotspot', label: 'Hotspot', hint: 'Post-purchase redirect, voucher expiry', icon: 'wifi', need: 'SETTINGS' },
    ],
  },
  {
    group: 'Network',
    items: [
      { key: 'mikrotik', label: 'MikroTik', hint: 'Router API and hotspot profile', icon: 'router', need: 'SETTINGS' },
    ],
  },
  {
    group: 'Billing & messaging',
    items: [
      { key: 'payments', label: 'Payment gateways', hint: 'How customers pay you', icon: 'credit_card', need: 'SETTINGS' },
      { key: 'vat', label: 'VAT', hint: 'Tax rate, KRA PIN, invoice numbering', icon: 'percent', need: 'SETTINGS' },
      { key: 'messaging', label: 'SMS & WhatsApp', hint: 'Your own gateway credentials', icon: 'chat', need: 'SETTINGS' },
    ],
  },
  {
    group: 'Developer',
    items: [
      { key: 'developer', label: 'API tokens', hint: 'Programmatic access to the API', icon: 'key', need: 'SETTINGS' },
    ],
  },
  {
    group: 'Account',
    items: [
      { key: 'security', label: 'Security', hint: 'Passkeys, sessions, sign-in lockout', icon: 'lock', need: 'SETTINGS' },
      { key: 'profile', label: 'Your profile', hint: 'Your name, contact and password', icon: 'account_circle' },
    ],
  },
]

/** Developer — personal access tokens for the REST API. Shown in full once. */
function DeveloperSection({ auth }) {
  const [tokens, setTokens] = useState(null)
  const [name, setName] = useState('')
  const [created, setCreated] = useState(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)

  const load = () => api('/admin/api-tokens', { auth }).then(setTokens).catch(() => setTokens([]))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function create(e) {
    e.preventDefault()
    if (!name.trim()) return
    setBusy(true)
    setCopied(false)
    try {
      const c = await api('/admin/api-tokens', { method: 'POST', auth, body: { name: name.trim() } })
      setCreated(c)
      setName('')
      load()
    } finally {
      setBusy(false)
    }
  }

  async function revoke(id) {
    await api(`/admin/api-tokens/${id}`, { method: 'DELETE', auth }).catch(() => {})
    load()
  }

  return (
    <div className="space-y-6 max-w-3xl">
      <form onSubmit={create} className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
        <label className={LABEL_CLS}>Create a token</label>
        <p className="text-sm text-on-surface-variant mt-1 mb-3">
          Use it as <code className="font-mono">Authorization: Bearer &lt;token&gt;</code> against the API. It carries your role and permissions. Treat it like a password.
        </p>
        <div className="flex gap-2 flex-wrap">
          <input className={`${INPUT_CLS} flex-1 min-w-[200px]`} placeholder="e.g. mpesa-reconciler"
            value={name} onChange={(e) => setName(e.target.value)} />
          <PrimaryButton disabled={busy}>{busy ? 'Creating…' : 'Create token'}</PrimaryButton>
        </div>
      </form>

      {created && (
        <div className="rounded-lg p-4 border border-primary/50 bg-primary/5">
          <p className="text-sm font-semibold text-on-surface">Copy this now — it won't be shown again.</p>
          <div className="mt-2 flex items-center gap-2">
            <code className="font-mono text-sm break-all bg-surface-container rounded px-3 py-2 flex-1">{created.token}</code>
            <button type="button"
              onClick={() => { navigator.clipboard?.writeText(created.token); setCopied(true) }}
              className="h-9 px-3 rounded-md border border-outline-variant text-sm font-semibold hover:bg-surface-container-high cursor-pointer">
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
        </div>
      )}

      <div className="bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
        {tokens === null ? (
          <div className="p-4"><Skeleton className="h-20" /></div>
        ) : tokens.length === 0 ? (
          <p className="p-4 text-sm text-on-surface-variant">No API tokens yet.</p>
        ) : (
          <table className="data-table w-full text-left">
            <thead><tr><th>Name</th><th>Token</th><th>Created</th><th>Last used</th><th className="text-right">Actions</th></tr></thead>
            <tbody>
              {tokens.map((t) => (
                <tr key={t.id}>
                  <td className="font-medium">{t.name}</td>
                  <td className="font-mono text-xs">{t.masked}</td>
                  <td className="text-xs">{t.createdAt ? new Date(t.createdAt).toLocaleDateString('en-KE') : ''}<span className="text-on-surface-variant">{t.createdBy ? ` · ${t.createdBy}` : ''}</span></td>
                  <td className="text-xs text-on-surface-variant">{t.lastUsedAt ? new Date(t.lastUsedAt).toLocaleDateString('en-KE') : 'never'}</td>
                  <td className="text-right">
                    <button onClick={() => revoke(t.id)}
                      className="text-sm font-semibold text-error hover:underline cursor-pointer">Revoke</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

/** Hotspot lifecycle — where buyers go next, and cleanup of stale vouchers. */
function HotspotSection({ auth }) {
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api('/admin/settings/hotspot', { auth }).then(setForm).catch(() => {})
  }, [auth])

  if (!form) return <Skeleton className="h-56" />

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setSaved(false)
    try {
      const res = await api('/admin/settings/hotspot', { method: 'PUT', auth, body: form })
      setForm(res)
      setSaved(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <div>
        <label className={LABEL_CLS}>Post-purchase redirect</label>
        <input className={INPUT_CLS} placeholder="https://your-site.co.ke (leave blank to stay)"
          value={form.postPurchaseRedirect || ''}
          onChange={(e) => setForm({ ...form, postPurchaseRedirect: e.target.value })} />
        <p className="text-xs text-on-surface-variant mt-1">
          Where to send a customer after a successful purchase. Leave blank to keep them on the success page.
        </p>
      </div>
      <div>
        <label className={LABEL_CLS}>Unused voucher expiry (days)</label>
        <input type="number" min="0" max="3650" className={`${INPUT_CLS} max-w-xs`}
          value={form.unusedVoucherExpiryDays}
          onChange={(e) => setForm({ ...form, unusedVoucherExpiryDays: Number(e.target.value) })} />
        <p className="text-xs text-on-surface-variant mt-1">
          Auto-invalidate a voucher that was printed but never used after this many days. 0 = never.
        </p>
      </div>
      <div className="flex items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        {saved && <span className="text-sm text-secondary">Saved.</span>}
      </div>
    </form>
  )
}

/** Passkey enforcement, session length and lockout — the policy behind the
 *  auth flow, editable instead of living in env files. */
function SecuritySection({ auth }) {
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api('/admin/settings/security', { auth }).then(setForm).catch(() => {})
  }, [auth])

  if (!form) return <Skeleton className="h-64" />

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setSaved(false)
    try {
      const res = await api('/admin/settings/security', { method: 'PUT', auth, body: form })
      setForm(res)
      setSaved(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant flex items-start justify-between gap-4">
        <div>
          <label className={LABEL_CLS}>Require passkeys</label>
          <p className="text-sm text-on-surface-variant mt-1">
            Staff without a passkey must set one up on their next sign-in. Turn this on only once the
            site is on HTTPS — passkeys can't be created over plain http.
          </p>
        </div>
        <Toggle checked={form.requirePasskeys}
          onChange={(e) => setForm({ ...form, requirePasskeys: e.target.checked })} />
      </section>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className={LABEL_CLS}>Session timeout (hours)</label>
          <input type="number" min="1" max="720" className={INPUT_CLS} value={form.sessionTimeoutHours}
            onChange={(e) => setForm({ ...form, sessionTimeoutHours: Number(e.target.value) })} />
          <p className="text-xs text-on-surface-variant mt-1">How long a signed-in session lasts before it must sign in again.</p>
        </div>
        <div>
          <label className={LABEL_CLS}>Lock account after (failed attempts)</label>
          <input type="number" min="3" max="20" className={INPUT_CLS} value={form.maxLoginAttempts}
            onChange={(e) => setForm({ ...form, maxLoginAttempts: Number(e.target.value) })} />
          <p className="text-xs text-on-surface-variant mt-1">After this many wrong sign-ins, an owner must reset the password.</p>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        {saved && <span className="text-sm text-secondary">Saved.</span>}
      </div>
    </form>
  )
}

function MessagingSection({ auth }) {
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(null)
  const [testPhone, setTestPhone] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () =>
    api('/admin/settings/messaging', { auth }).then((d) => {
      setSaved(d)
      setForm({
        smsEnabled: d.smsEnabled,
        smsUsername: d.smsUsername || '',
        smsApiKey: '',
        smsSenderId: d.smsSenderId || '',
        whatsappEnabled: d.whatsappEnabled,
        whatsappPhoneNumberId: d.whatsappPhoneNumberId || '',
        whatsappAccessToken: '',
        alertPhone: d.alertPhone || '',
      })
    }).catch((e) => setMsg({ ok: false, text: e.message }))

  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/settings/messaging', { method: 'PUT', auth, body: form })
      setMsg({ ok: true, text: 'Saved. Messages will use these from the next send.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function sendTest() {
    setBusy(true)
    setMsg(null)
    try {
      const r = await api('/admin/settings/messaging/test', {
        method: 'POST', auth, body: { phoneNumber: testPhone.replace(/\D/g, '') },
      })
      setMsg({ ok: true, text: r.message })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <form onSubmit={save} className="space-y-6 max-w-3xl">
      {saved?.usingEnvironmentFallback && (
        <p className="p-3 rounded-lg bg-[#f59e0b]/10 border border-[#f59e0b]/30 text-sm text-[#b45309]">
          Messaging currently works because credentials were set when this system was installed. Fill them in
          here to manage them yourself — until you do, the fields below look empty even though sending works.
        </p>
      )}

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <label className="flex items-start gap-3 cursor-pointer">
          <input type="checkbox" className="mt-1" checked={form.smsEnabled}
            onChange={(e) => set({ smsEnabled: e.target.checked })} />
          <span>
            <span className="text-base font-semibold block">
              SMS <span className="font-normal text-on-surface-variant">via Africa's Talking</span>
            </span>
            <span className="text-sm text-on-surface-variant">
              Used for vouchers, expiry reminders and campaigns.
            </span>
          </span>
        </label>
        {form.smsEnabled && (
          <div className="mt-4 grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <label className={LABEL_CLS}>Username</label>
              <input className={INPUT_CLS} value={form.smsUsername}
                onChange={(e) => set({ smsUsername: e.target.value })} placeholder="sandbox or your username" />
            </div>
            <div>
              <label className={LABEL_CLS}>API key</label>
              <input className={INPUT_CLS} type="password" value={form.smsApiKey}
                onChange={(e) => set({ smsApiKey: e.target.value })}
                placeholder={saved?.smsApiKey || 'from Africa’s Talking'} />
            </div>
            <div>
              <label className={LABEL_CLS}>Sender ID</label>
              <input className={INPUT_CLS} value={form.smsSenderId}
                onChange={(e) => set({ smsSenderId: e.target.value })} placeholder="optional" />
              <p className="text-xs text-on-surface-variant mt-1">Must be registered with them first.</p>
            </div>
          </div>
        )}
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <label className="flex items-start gap-3 cursor-pointer">
          <input type="checkbox" className="mt-1" checked={form.whatsappEnabled}
            onChange={(e) => set({ whatsappEnabled: e.target.checked })} />
          <span>
            <span className="text-base font-semibold block">
              WhatsApp <span className="font-normal text-on-surface-variant">via Meta Cloud API</span>
            </span>
            <span className="text-sm text-on-surface-variant">
              Tried before SMS when it is on, because it costs less and carries more.
            </span>
          </span>
        </label>
        {form.whatsappEnabled && (
          <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Phone number ID</label>
              <input className={INPUT_CLS} value={form.whatsappPhoneNumberId}
                onChange={(e) => set({ whatsappPhoneNumberId: e.target.value })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Access token</label>
              <input className={INPUT_CLS} type="password" value={form.whatsappAccessToken}
                onChange={(e) => set({ whatsappAccessToken: e.target.value })}
                placeholder={saved?.whatsappAccessToken || 'from Meta'} />
            </div>
          </div>
        )}
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <h3 className="text-base font-bold">Operator alerts</h3>
        <p className="text-sm text-on-surface-variant mt-0.5 mb-3">
          Where to text you when a router goes offline or comes back.
        </p>
        <input className={`${INPUT_CLS} max-w-xs`} value={form.alertPhone}
          onChange={(e) => set({ alertPhone: e.target.value })} placeholder="254712345678" />
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      <div className="flex flex-wrap items-end gap-3">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</PrimaryButton>
        <div className="flex items-end gap-2">
          <div>
            <label className={LABEL_CLS}>Send a test to</label>
            <input className={`${INPUT_CLS} w-44`} value={testPhone}
              onChange={(e) => setTestPhone(e.target.value)} placeholder="254712345678" />
          </div>
          <button type="button" onClick={sendTest} disabled={busy || !testPhone}
            className="px-4 py-2 rounded-lg border border-primary text-primary text-sm font-semibold cursor-pointer hover:bg-primary/5 disabled:opacity-50">
            Send test
          </button>
        </div>
      </div>
      <p className="text-xs text-on-surface-variant">
        A test sends one real message and lands in the Outbox like any other, so you can see whether the
        gateway accepted it.
      </p>
    </form>
  )
}

function ProfileSection({ auth, me }) {
  const [profile, setProfile] = useState({ fullName: '', phoneNumber: '', email: '' })
  const [pw, setPw] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => {
    if (me) setProfile((p) => ({ ...p, fullName: me.fullName || me.username || '' }))
  }, [me])

  async function saveProfile(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      await api('/admin/staff/me', { method: 'PATCH', auth, body: profile })
      setMsg({ ok: true, text: 'Details saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function changePassword(e) {
    e.preventDefault()
    if (pw.newPassword !== pw.confirm) {
      setMsg({ ok: false, text: 'The two new passwords do not match.' })
      return
    }
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/staff/me/password', {
        method: 'PATCH', auth,
        body: { currentPassword: pw.currentPassword, newPassword: pw.newPassword },
      })
      setMsg({ ok: true, text: `${r.message} You will need to sign in again.` })
      setPw({ currentPassword: '', newPassword: '', confirm: '' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  return (
    <div className="space-y-6 max-w-2xl">
      {me?.breakGlass && (
        <p className="p-3 rounded-lg bg-[#f59e0b]/10 border border-[#f59e0b]/30 text-sm text-[#b45309]">
          You are signed in with the fallback account from the config file. It has no profile or password to
          change here — create a named login under Organisation → Staff Logins and use that.
        </p>
      )}

      <form onSubmit={saveProfile} className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <h3 className="text-base font-bold">Your details</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div>
            <label className={LABEL_CLS}>Full name</label>
            <input className={INPUT_CLS} required value={profile.fullName}
              onChange={(e) => setProfile({ ...profile, fullName: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Phone</label>
            <input className={INPUT_CLS} value={profile.phoneNumber}
              onChange={(e) => setProfile({ ...profile, phoneNumber: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Email</label>
            <input className={INPUT_CLS} type="email" value={profile.email}
              onChange={(e) => setProfile({ ...profile, email: e.target.value })} />
          </div>
        </div>
        <PrimaryButton type="submit" disabled={busy || me?.breakGlass}>Save details</PrimaryButton>
      </form>

      <form onSubmit={changePassword} className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <h3 className="text-base font-bold">Change your password</h3>
        <p className="text-sm text-on-surface-variant">
          If an owner set your password up for you, change it here — then only you know it.
        </p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div>
            <label className={LABEL_CLS}>Current password</label>
            <input className={INPUT_CLS} type="password" required value={pw.currentPassword}
              onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>New password</label>
            <input className={INPUT_CLS} type="password" required minLength={8} value={pw.newPassword}
              onChange={(e) => setPw({ ...pw, newPassword: e.target.value })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Repeat it</label>
            <input className={INPUT_CLS} type="password" required value={pw.confirm}
              onChange={(e) => setPw({ ...pw, confirm: e.target.value })} />
          </div>
        </div>
        <PrimaryButton type="submit" disabled={busy || me?.breakGlass}>Change password</PrimaryButton>
      </form>

      {msg && <p className={`text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}
    </div>
  )
}

export default function SettingsHub({ auth, me, mikrotikSection }) {
  // The open section goes in the path too, so a specific setting can be
  // linked to — /admin/settings/vat — rather than only the settings area.
  const navigate = useNavigate()
  const location = useLocation()
  const fromUrl = location.pathname.replace(/^\/admin\/settings\/?/, '').split('/')[0]
  const active = fromUrl || 'payments'
  const setActive = (key) => navigate(`/admin/settings/${key}`)
  const [search, setSearch] = useState('')

  const permissions = me?.permissions
  const groups = useMemo(() => SECTIONS
    .map((g) => ({
      ...g,
      items: g.items
        .filter((i) => !i.need || !permissions || permissions.includes(i.need))
        .filter((i) => {
          const q = search.trim().toLowerCase()
          return !q || i.label.toLowerCase().includes(q) || i.hint.toLowerCase().includes(q)
        }),
    }))
    .filter((g) => g.items.length > 0), [permissions, search])

  // If a filter or a role hides the open section, fall back to the first
  // one still visible rather than showing an empty pane.
  const visible = groups.flatMap((g) => g.items).map((i) => i.key)
  const current = visible.includes(active) ? active : visible[0]

  return (
    <div>
      <PageHeader title="Settings" subtitle="How this system behaves, and who it behaves as." />

      <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-6 items-start">
        <aside className="bg-surface-container-lowest rounded-lg border border-outline-variant p-3 lg:sticky lg:top-4">
          <input
            className="w-full mb-3 bg-surface border border-outline-variant rounded-lg px-3 py-2 text-sm focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Find a setting…"
            aria-label="Find a setting"
          />
          {groups.length === 0 && (
            <p className="text-sm text-on-surface-variant p-2">Nothing matches that.</p>
          )}
          {groups.map((g) => (
            <div key={g.group} className="mb-3">
              <p className="px-2 mb-1 text-[10px] font-bold tracking-[0.12em] uppercase text-on-surface-variant/60">
                {g.group}
              </p>
              <ul className="space-y-0.5">
                {g.items.map((i) => (
                  <li key={i.key}>
                    <button
                      onClick={() => setActive(i.key)}
                      aria-current={current === i.key ? 'page' : undefined}
                      className={`w-full text-left flex items-start gap-2.5 px-2.5 py-2 rounded-lg cursor-pointer transition-colors ${
                        current === i.key
                          ? 'bg-primary-container text-on-primary-container'
                          : 'hover:bg-surface-container-high'
                      }`}
                    >
                      <Icon name={i.icon} className="text-[18px]! mt-0.5" />
                      <span className="min-w-0">
                        <span className="text-sm font-medium block">{i.label}</span>
                        <span className={`text-xs block ${current === i.key ? 'opacity-80' : 'text-on-surface-variant'}`}>
                          {i.hint}
                        </span>
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </aside>

        <div className="min-w-0">
          {current === 'payments' && <PaymentGatewaysPage auth={auth} />}
          {current === 'vat' && <TaxSettingsPage auth={auth} />}
          {current === 'branding' && <BrandingPage auth={auth} />}
          {current === 'hotspot' && (
            <>
              <PageHeader title="Hotspot" subtitle="What happens after a purchase, and voucher cleanup." />
              <HotspotSection auth={auth} />
            </>
          )}
          {current === 'messaging' && (
            <>
              <PageHeader title="SMS & WhatsApp" subtitle="Your own gateway accounts, so message costs land on you." />
              <MessagingSection auth={auth} />
            </>
          )}
          {current === 'security' && (
            <>
              <PageHeader title="Security" subtitle="Passkeys, session length and sign-in lockout." />
              <SecuritySection auth={auth} />
            </>
          )}
          {current === 'developer' && (
            <>
              <PageHeader title="API tokens" subtitle="Personal access tokens for the REST API." />
              <DeveloperSection auth={auth} />
            </>
          )}
          {current === 'profile' && (
            <>
              <PageHeader title="Your profile" subtitle="Your details and your password." />
              <ProfileSection auth={auth} me={me} />
            </>
          )}
          {current === 'mikrotik' && mikrotikSection}
        </div>
      </div>
    </div>
  )
}
