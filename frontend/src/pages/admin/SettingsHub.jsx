import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, INPUT_CLS, LABEL_CLS, Toggle,
} from '../../components/ui.jsx'
import QRCode from 'qrcode'
import PaymentGatewaysPage from './PaymentGateways.jsx'
import TaxSettingsPage from './TaxSettings.jsx'
import BrandingPage from './Branding.jsx'
import { PORTAL_DESIGNS, normalizeDesignKey } from '../../portalDesigns.js'
import { enrollPasskey, passkeySupported } from '../../passkey.js'

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
      { key: 'loyalty', label: 'Loyalty & rewards', hint: 'Points earned on spend, redeemed for free time', icon: 'loyalty', need: 'SETTINGS' },
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
      { key: 'paybill', label: 'Zero-touch PayBill', hint: 'Turn a plain paybill payment into a pass, no prompt needed', icon: 'account_balance', need: 'SETTINGS' },
      { key: 'credit', label: 'Pay later (Lipa Baadaye)', hint: 'Trusted customers get online now and settle next time', icon: 'schedule', need: 'SETTINGS' },
      { key: 'vat', label: 'VAT', hint: 'Tax rate, KRA PIN, invoice numbering', icon: 'percent', need: 'SETTINGS' },
      { key: 'messaging', label: 'SMS & WhatsApp', hint: 'Your own gateway credentials', icon: 'chat', need: 'SETTINGS' },
      { key: 'email', label: 'Email (SMTP)', hint: 'Receipts, resets and reports', icon: 'mail', need: 'SETTINGS' },
      { key: 'alerts', label: 'Alerts & briefing', hint: 'Outage alerts, compensation, your daily briefing', icon: 'notifications_active', need: 'SETTINGS' },
    ],
  },
  {
    group: 'Automation',
    items: [
      { key: 'field', label: 'Field jobs', hint: 'Technicians work jobs from WhatsApp; quiet jobs get chased', icon: 'engineering', need: 'SETTINGS' },
      { key: 'agentpay', label: 'Agent payouts', hint: 'Commission paid out on a schedule over M-Pesa', icon: 'payments', need: 'SETTINGS' },
      { key: 'offpeak', label: 'Off-peak offers', hint: 'Sell the hours the link is idle at a night rate', icon: 'bedtime', need: 'SETTINGS' },
      { key: 'capacity', label: 'Capacity planning', hint: 'Busy hour per site, and how long before it fills', icon: 'speed', need: 'SETTINGS' },
    ],
  },
  {
    group: 'Developer',
    items: [
      { key: 'developer', label: 'API tokens', hint: 'Programmatic access to the API', icon: 'key', need: 'SETTINGS' },
      { key: 'ai', label: 'AI assistant', hint: 'Ask questions about your business (Groq)', icon: 'smart_toy', need: 'SETTINGS' },
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

/* ------------------------------------------------------------------ */
/* Portal-design gallery: a miniature phone mockup per design, drawn   */
/* from the same palette registry the live portal uses.                */
/* ------------------------------------------------------------------ */

function Bar({ w = '100%', c, h = 4, r = 2, style = {} }) {
  return <div style={{ width: w, height: h, background: c, borderRadius: r, ...style }} />
}

function DesignMockBody({ d }) {
  const pv = d.preview
  switch (d.key) {
    case 'BREEZE':
      return (
        <div className="space-y-1">
          <div style={{ background: pv.surface, border: `1px solid ${pv.outline}`, borderRadius: 10, padding: 6, textAlign: 'center' }}>
            <div style={{ width: 12, height: 12, borderRadius: 999, background: `${pv.accent}33`, margin: '0 auto 3px' }} />
            <Bar w="70%" c={pv.text} h={4} style={{ margin: '0 auto' }} />
            <Bar w="50%" c={pv.muted} h={3} style={{ margin: '3px auto 0' }} />
          </div>
          <div className="flex gap-1">
            <div style={{ background: pv.accent, borderRadius: 999, height: 8, width: 22 }} />
            <div style={{ border: `1px solid ${pv.outline}`, background: pv.surface, borderRadius: 999, height: 8, width: 22 }} />
            <div style={{ border: `1px solid ${pv.outline}`, background: pv.surface, borderRadius: 999, height: 8, width: 22 }} />
          </div>
          {[0, 1].map((i) => (
            <div key={i} className="flex items-center gap-1.5" style={{ background: pv.surface, border: `1px solid ${pv.outline}`, borderRadius: 9, padding: 5 }}>
              <div style={{ width: 10, height: 10, borderRadius: 4, background: `${pv.accent}33` }} />
              <div className="flex-1"><Bar w="80%" c={pv.text} h={3.5} /></div>
              <div style={{ background: pv.accent, borderRadius: 999, height: 8, width: 16 }} />
            </div>
          ))}
        </div>
      )
    case 'POSTER':
      return (
        <div className="space-y-1.5" style={{ padding: 2 }}>
          <div style={{ borderTop: `2px solid ${pv.text}`, borderBottom: `2px solid ${pv.text}`, padding: '5px 2px', textAlign: 'center' }}>
            <div style={{ fontFamily: 'Georgia, serif', fontWeight: 700, fontSize: 11, color: pv.text, lineHeight: 1.1 }}>Get Online</div>
            <div style={{ fontSize: 5, letterSpacing: 2, color: pv.muted, textTransform: 'uppercase', marginTop: 2 }}>internet by the hour</div>
          </div>
          <div className="grid grid-cols-2 gap-1.5">
            {[-0.8, 0.8, 0.8, -0.8].map((r, i) => (
              <div key={i} style={{ background: pv.surface, border: `1.5px solid ${pv.text}`, boxShadow: `2px 2px 0 ${pv.accent}`, transform: `rotate(${r}deg)`, padding: 4, textAlign: 'center' }}>
                <div style={{ fontFamily: 'Georgia, serif', fontWeight: 700, fontSize: 9, color: pv.accent }}>50</div>
                <Bar w="60%" c={pv.muted} h={2.5} style={{ margin: '2px auto 0' }} />
              </div>
            ))}
          </div>
        </div>
      )
    case 'MATRIX':
      return (
        <div className="space-y-1">
          <div className="flex items-center justify-between" style={{ background: pv.surface, borderRadius: 6, padding: 4 }}>
            <Bar w="30%" c={pv.accent} h={4} />
            <div style={{ border: `1px solid ${pv.outline}`, borderRadius: 999, width: 18, height: 6 }} />
          </div>
          <div className="grid grid-cols-3 gap-1">
            {Array.from({ length: 9 }).map((_, i) => (
              <div key={i} style={{ background: pv.surface2, border: `1px solid ${pv.outline}`, borderRadius: 5, padding: 3, textAlign: 'center' }}>
                <Bar w="80%" c={pv.text} h={3} style={{ margin: '0 auto' }} />
                <div style={{ color: pv.accent, fontSize: 5.5, fontFamily: 'monospace', marginTop: 2 }}>KES</div>
              </div>
            ))}
          </div>
        </div>
      )
    case 'STEPS':
      return (
        <div className="space-y-1">
          <div style={{ background: pv.surface, border: `1px solid ${pv.outline}`, borderRadius: 8, padding: 5 }}>
            {[1, 2, 3].map((n) => (
              <div key={n} className="flex items-center gap-1.5" style={{ marginBottom: n < 3 ? 3 : 0 }}>
                <div style={{ width: 8, height: 8, borderRadius: 999, background: `${pv.accent}26`, color: pv.accent, fontSize: 5, fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{n}</div>
                <Bar w={`${75 - n * 8}%`} c={pv.muted} h={3} />
              </div>
            ))}
          </div>
          {[0, 1].map((i) => (
            <div key={i} className="flex items-center justify-between" style={{ background: pv.surface, border: `1px solid ${pv.outline}`, borderRadius: 8, padding: 5 }}>
              <Bar w="45%" c={pv.text} h={3.5} />
              <div style={{ border: `1.5px solid ${pv.accent}`, borderRadius: 4, width: 18, height: 9 }} />
            </div>
          ))}
        </div>
      )
    case 'NEON':
      return (
        <div style={{ fontFamily: 'monospace' }} className="space-y-1">
          <div className="flex items-center gap-1" style={{ background: pv.surface, borderRadius: 5, padding: 4 }}>
            {[0.9, 0.5, 1].map((o, i) => (
              <div key={i} style={{ width: 4, height: 4, borderRadius: 999, background: pv.accent, opacity: o }} />
            ))}
          </div>
          <div style={{ color: pv.accent, fontSize: 6 }}>&gt; network: online_</div>
          <div style={{ border: `1px solid ${pv.outline}`, borderRadius: 6, background: pv.surface }}>
            {[0, 1, 2].map((i) => (
              <div key={i} className="flex items-center justify-between" style={{ padding: 4, borderBottom: i < 2 ? `1px dashed ${pv.outline}` : 'none' }}>
                <Bar w="40%" c={pv.text} h={3} />
                <span style={{ color: pv.accent, fontSize: 5.5 }}>[KES 50]</span>
              </div>
            ))}
          </div>
        </div>
      )
    default: // CLASSIC
      return (
        <div className="space-y-1">
          <div style={{ background: `linear-gradient(120deg, #1c1c1c, ${pv.accent}33)`, borderRadius: 8, padding: 6 }}>
            <div style={{ width: 14, height: 14, borderRadius: 999, background: '#ffffff22', marginBottom: 4 }} />
            <Bar w="70%" c="#ffffff" h={5} />
            <Bar w="50%" c="#ffffff88" h={3} style={{ marginTop: 3 }} />
          </div>
          {[0, 1].map((i) => (
            <div key={i} style={{ background: pv.surface2, borderRadius: 8, padding: 5 }}>
              <div className="flex items-center justify-between">
                <Bar w="40%" c={pv.muted} h={4} />
                <span style={{ color: pv.accent, fontSize: 7, fontFamily: 'monospace', fontWeight: 700 }}>KES 50</span>
              </div>
              <div style={{ background: pv.accent, borderRadius: 6, height: 9, marginTop: 4 }} />
            </div>
          ))}
        </div>
      )
  }
}

function DesignPhoneMock({ d }) {
  return (
    <div className="rounded-[16px] border-[3px] border-[#2c2c2c] overflow-hidden w-full shadow-md" style={{ background: d.preview.bg }}>
      <div className="h-3 flex items-center justify-center">
        <div className="w-9 h-1 rounded-full" style={{ background: 'rgba(128,128,128,0.4)' }}></div>
      </div>
      <div className="h-40 overflow-hidden px-1.5 pb-1.5">
        <DesignMockBody d={d} />
      </div>
    </div>
  )
}

/** Developer — personal access tokens for the REST API. Shown in full once. */
function DeveloperSection({ auth }) {
  const [tokens, setTokens] = useState(null)
  const [name, setName] = useState('')
  const [created, setCreated] = useState(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(false)

  // Webhooks
  const [hooks, setHooks] = useState(null)
  const [events, setEvents] = useState([])
  const [wform, setWform] = useState({ label: '', url: '', events: [] })
  const [whCreated, setWhCreated] = useState(null)
  const [wbusy, setWbusy] = useState(false)

  const load = () => api('/admin/api-tokens', { auth }).then(setTokens).catch(() => setTokens([]))
  const loadHooks = () => api('/admin/webhooks', { auth }).then(setHooks).catch(() => setHooks([]))
  useEffect(() => {
    load()
    loadHooks()
    api('/admin/webhooks/events', { auth }).then(setEvents).catch(() => {})
  }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

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

  function toggleEvent(ev) {
    setWform((f) => ({
      ...f,
      events: f.events.includes(ev) ? f.events.filter((x) => x !== ev) : [...f.events, ev],
    }))
  }

  async function createHook(e) {
    e.preventDefault()
    if (!wform.label.trim() || !wform.url.trim() || wform.events.length === 0) return
    setWbusy(true)
    try {
      const c = await api('/admin/webhooks', { method: 'POST', auth, body: wform })
      setWhCreated(c)
      setWform({ label: '', url: '', events: [] })
      loadHooks()
    } catch (err) {
      alert(err.message)
    } finally {
      setWbusy(false)
    }
  }

  async function testHook(id) {
    await api(`/admin/webhooks/${id}/test`, { method: 'POST', auth }).catch(() => {})
    setTimeout(loadHooks, 1200)
  }

  async function deleteHook(id) {
    await api(`/admin/webhooks/${id}`, { method: 'DELETE', auth }).catch(() => {})
    loadHooks()
  }

  return (
    <div className="space-y-8 max-w-3xl">
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

      {/* Webhooks */}
      <div>
        <h3 className="text-base font-semibold text-on-surface mb-1">Webhooks</h3>
        <p className="text-sm text-on-surface-variant mb-3">
          Forward events to your own endpoint. Every request is signed{' '}
          <code className="font-mono">X-Zidi-Signature: sha256=HMAC(secret, body)</code>.
        </p>

        <form onSubmit={createHook} className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant space-y-3">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <input className={INPUT_CLS} placeholder="Label (e.g. Slack notifier)"
              value={wform.label} onChange={(e) => setWform({ ...wform, label: e.target.value })} />
            <input className={INPUT_CLS} placeholder="https://example.com/webhooks/zidi"
              value={wform.url} onChange={(e) => setWform({ ...wform, url: e.target.value })} />
          </div>
          <div>
            <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Events</p>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
              {events.map((ev) => (
                <label key={ev} className="flex items-center gap-2 text-sm cursor-pointer">
                  <input type="checkbox" className="accent-[#fdbf2d]"
                    checked={wform.events.includes(ev)} onChange={() => toggleEvent(ev)} />
                  <span className="font-mono text-xs">{ev}</span>
                </label>
              ))}
            </div>
          </div>
          <PrimaryButton disabled={wbusy}>{wbusy ? 'Adding…' : 'Add webhook'}</PrimaryButton>
        </form>

        {whCreated && (
          <div className="mt-3 rounded-lg p-4 border border-primary/50 bg-primary/5">
            <p className="text-sm font-semibold text-on-surface">Signing secret — copy it now, shown once.</p>
            <code className="font-mono text-sm break-all block mt-2 bg-surface-container rounded px-3 py-2">{whCreated.secret}</code>
          </div>
        )}

        <div className="mt-3 bg-surface-container-lowest rounded-lg border border-outline-variant overflow-hidden">
          {hooks === null ? (
            <div className="p-4"><Skeleton className="h-16" /></div>
          ) : hooks.length === 0 ? (
            <p className="p-4 text-sm text-on-surface-variant">No webhooks configured.</p>
          ) : (
            <table className="data-table w-full text-left">
              <thead><tr><th>Label</th><th>URL</th><th>Events</th><th>Last</th><th className="text-right">Actions</th></tr></thead>
              <tbody>
                {hooks.map((h) => (
                  <tr key={h.id}>
                    <td className="font-medium">{h.label}</td>
                    <td className="font-mono text-xs break-all max-w-[220px]">{h.url}</td>
                    <td className="text-xs text-on-surface-variant">{h.events.length}</td>
                    <td className="text-xs">
                      {h.lastStatus === '' ? <span className="text-on-surface-variant">—</span>
                        : <span className={Number(h.lastStatus) >= 200 && Number(h.lastStatus) < 300 ? 'text-secondary' : 'text-error'}>{h.lastStatus}</span>}
                    </td>
                    <td className="text-right whitespace-nowrap">
                      <button onClick={() => testHook(h.id)} className="text-sm font-semibold text-primary hover:underline cursor-pointer mr-3">Test</button>
                      <button onClick={() => deleteHook(h.id)} className="text-sm font-semibold text-error hover:underline cursor-pointer">Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
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
        <label className={LABEL_CLS}>Portal design</label>
        <p className="text-xs text-on-surface-variant mb-3">
          Each design is a completely different portal — its own layout, colours and type.
          Tap a phone to select it, or <span className="font-semibold">Preview</span> to open it live in a new tab.
        </p>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
          {PORTAL_DESIGNS.map((d) => {
            const active = (normalizeDesignKey(form.portalTemplate) || 'CLASSIC') === d.key
            return (
              <div key={d.key} className="flex flex-col">
                <button type="button"
                  onClick={() => setForm({ ...form, portalTemplate: d.key })}
                  title={d.desc}
                  className={`rounded-2xl border-2 p-2 transition-all cursor-pointer ${
                    active ? 'border-primary ring-2 ring-primary/40 bg-primary/5' : 'border-outline-variant hover:border-outline'
                  }`}>
                  <DesignPhoneMock d={d} />
                </button>
                <div className="flex items-center justify-between mt-1.5 px-1">
                  <span className="text-xs font-semibold text-on-surface flex items-center gap-1">
                    {d.name}
                    {active && <Icon name="check_circle" className="text-primary text-[14px]!" />}
                  </span>
                  <a href={`/?design=${d.key}`} target="_blank" rel="noreferrer"
                    className="text-[11px] font-semibold text-primary hover:underline">
                    Preview
                  </a>
                </div>
                <p className="text-[11px] text-on-surface-variant px-1 mt-0.5 line-clamp-2">{d.desc}</p>
              </div>
            )
          })}
        </div>
      </div>
      <div>
        <label className={LABEL_CLS}>Default portal language</label>
        <p className="text-xs text-on-surface-variant mb-2">Customers can still switch language themselves on the portal.</p>
        <div className="inline-flex rounded-lg border border-outline-variant overflow-hidden">
          {[['EN', 'English'], ['SW', 'Kiswahili']].map(([code, name]) => {
            const active = (form.defaultLanguage || 'EN') === code
            return (
              <button type="button" key={code}
                onClick={() => setForm({ ...form, defaultLanguage: code })}
                className={`px-4 py-2 text-sm font-medium cursor-pointer transition-colors ${
                  active ? 'bg-primary text-on-primary' : 'bg-surface text-on-surface-variant hover:bg-surface-container'
                }`}>
                {name}
              </button>
            )
          })}
        </div>
      </div>
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

/** The signed-in owner's own protections: an authenticator app (2FA) and a
 *  biometric passkey (fingerprint / face). Distinct from SecuritySection, which
 *  sets the policy for everyone. */
function PersonalSecuritySection({ auth, me }) {
  const [twoFactor, setTwoFactor] = useState(!!me?.twoFactor)
  const [hasPasskey, setHasPasskey] = useState(!!me?.hasPasskeys)
  const [setup, setSetup] = useState(null) // { secret, qr }
  const [code, setCode] = useState('')
  const [disabling, setDisabling] = useState(false)
  const [pw, setPw] = useState('')
  const [dcode, setDcode] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [pkMsg, setPkMsg] = useState(null)

  async function start2fa() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/auth/2fa/start', { method: 'POST', auth })
      const qr = await QRCode.toDataURL(r.uri, { margin: 1, width: 208 })
      setSetup({ secret: r.secret, qr })
    } catch (e) { setMsg({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  async function confirm2fa(e) {
    e.preventDefault(); setBusy(true); setMsg(null)
    try {
      await api('/auth/2fa/confirm', { method: 'POST', auth, body: { code } })
      setTwoFactor(true); setSetup(null); setCode(''); setMsg({ ok: true, text: 'Two-factor is on.' })
    } catch (e) { setMsg({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  async function disable2fa(e) {
    e.preventDefault(); setBusy(true); setMsg(null)
    try {
      await api('/auth/2fa/disable', { method: 'POST', auth, body: { password: pw, code: dcode } })
      setTwoFactor(false); setDisabling(false); setPw(''); setDcode(''); setMsg({ ok: true, text: 'Two-factor is off.' })
    } catch (e) { setMsg({ ok: false, text: e.message }) } finally { setBusy(false) }
  }
  async function addPasskey() {
    setPkMsg(null)
    try {
      await enrollPasskey(auth, 'Passkey')
      setHasPasskey(true); setPkMsg({ ok: true, text: 'Passkey added — sign in with fingerprint or face next time.' })
    } catch (e) {
      if (e.name === 'NotAllowedError' || e.name === 'AbortError') setPkMsg({ ok: false, text: 'Passkey setup was cancelled.' })
      else setPkMsg({ ok: false, text: e.message || 'Could not add a passkey.' })
    }
  }

  return (
    <div className="space-y-4 mb-4">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
        <div className="flex items-center gap-2 mb-1"><Icon name="qr_code_2" className="text-primary" /><h3 className="text-sm font-semibold text-on-surface">Two-factor (authenticator app)</h3></div>
        <p className="text-xs text-on-surface-variant mb-3">Scan the code with Google Authenticator, Authy or similar, then enter a 6-digit code to add it on top of your password.</p>
        {twoFactor ? (
          disabling ? (
            <form onSubmit={disable2fa} className="space-y-2 max-w-xs">
              <input type="password" className={INPUT_CLS} placeholder="Your password" value={pw} onChange={(e) => setPw(e.target.value)} />
              <input className={INPUT_CLS} placeholder="6-digit code" value={dcode} onChange={(e) => setDcode(e.target.value.replace(/\D/g, ''))} maxLength={6} />
              <div className="flex gap-2 items-center"><PrimaryButton type="submit" disabled={busy}>Turn off</PrimaryButton><button type="button" onClick={() => setDisabling(false)} className="text-sm text-on-surface-variant cursor-pointer">Cancel</button></div>
            </form>
          ) : (
            <div className="flex items-center gap-3">
              <span className="text-sm text-secondary flex items-center gap-1"><Icon name="check_circle" className="text-[16px]!" /> On</span>
              <button onClick={() => setDisabling(true)} className="text-sm text-error hover:underline cursor-pointer">Turn off</button>
            </div>
          )
        ) : setup ? (
          <form onSubmit={confirm2fa} className="space-y-3">
            <img src={setup.qr} alt="Scan in your authenticator app" className="w-44 h-44 rounded-lg bg-white p-1.5" />
            <p className="text-xs text-on-surface-variant">Can't scan? Enter this key: <code className="text-on-surface break-all">{setup.secret}</code></p>
            <input className={`${INPUT_CLS} max-w-[200px] font-mono tracking-[0.3em]`} placeholder="6-digit code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} maxLength={6} autoFocus />
            <div><PrimaryButton type="submit" disabled={busy || code.length < 6}>Turn on two-factor</PrimaryButton></div>
          </form>
        ) : (
          <PrimaryButton onClick={start2fa} disabled={busy}>Set up two-factor</PrimaryButton>
        )}
        {msg && <p className={`text-sm mt-2 ${msg.ok ? 'text-secondary' : 'text-error'}`}>{msg.text}</p>}
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant">
        <div className="flex items-center gap-2 mb-1"><Icon name="fingerprint" className="text-primary" /><h3 className="text-sm font-semibold text-on-surface">Biometric sign-in (passkey)</h3></div>
        <p className="text-xs text-on-surface-variant mb-3">Sign in with your fingerprint or face using a passkey on this device — no password to type.</p>
        {passkeySupported() ? (
          <div className="flex items-center gap-3">
            {hasPasskey && <span className="text-sm text-secondary flex items-center gap-1"><Icon name="check_circle" className="text-[16px]!" /> Set up</span>}
            <PrimaryButton onClick={addPasskey}>{hasPasskey ? 'Add another' : 'Add a passkey'}</PrimaryButton>
          </div>
        ) : (
          <p className="text-sm text-on-surface-variant">This browser doesn't support passkeys.</p>
        )}
        {pkMsg && <p className={`text-sm mt-2 ${pkMsg.ok ? 'text-secondary' : 'text-error'}`}>{pkMsg.text}</p>}
      </section>
    </div>
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

/* WhatsApp self-service assistant: how to connect Meta's webhook, plus a live
   preview so the operator can chat as a customer and see the bot reply. */
function WhatsappAssistantPanel({ auth }) {
  const [cfg, setCfg] = useState(null)
  const [msgs, setMsgs] = useState([])
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const scroller = useRef(null)
  // Who the preview is pretending to be. Editable, because half the menu
  // answers from the caller's own records — status, renew, resend a code,
  // referrals — and against an invented number every one of those correctly
  // replies "we can't find you", which reads as the bot being broken.
  // A made-up number is only the starting point.
  const [phone, setPhone] = useState(
    () => '2547' + Math.floor(10000000 + Math.random() * 89999999))

  useEffect(() => { api('/admin/whatsapp/config', { auth }).then(setCfg).catch(() => {}) }, [auth])
  useEffect(() => { if (scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight }, [msgs])

  /**
   * Messages the system pushes at this number arrive here on their own — the
   * access code seconds after a payment confirms, an expiry warning, a
   * receipt. On a real handset those simply appear in the thread; this panel
   * is request/response and could never show them, which made the automatic
   * delivery look broken when it was only invisible.
   *
   * Polls the outbox, so it shows what was actually sent — and, when no
   * gateway is configured, what would have been.
   */
  useEffect(() => {
    let since = Date.now()
    let stopped = false
    const tick = async () => {
      try {
        const pushed = await api(
          `/admin/comms/outbox/for?phone=${encodeURIComponent(phone)}&sinceEpochMs=${since}`,
          { auth })
        if (stopped || !pushed.length) return
        since = Math.max(since, ...pushed.map((m) => new Date(m.createdAt).getTime() + 1))
        setMsgs((m) => [...m, ...pushed.map((p) => ({
          who: 'bot', text: p.body, pushed: true, failed: p.status === 'FAILED', error: p.error,
        }))])
      } catch { /* the panel is a convenience; a failed poll is not worth showing */ }
    }
    const timer = setInterval(tick, 3000)
    return () => { stopped = true; clearInterval(timer) }
  }, [auth, phone])

  const webhookUrl = cfg ? window.location.origin + cfg.webhookPath : ''
  const copy = (v) => { try { navigator.clipboard.writeText(v) } catch { /* ignore */ } }

  async function send() {
    const q = text.trim()
    if (!q || busy) return
    setText('')
    setMsgs((m) => [...m, { who: 'you', text: q }])
    setBusy(true)
    try {
      const r = await api('/admin/whatsapp/simulate', { method: 'POST', auth, body: { phone, text: q } })
      setMsgs((m) => [...m, { who: 'bot', text: r.reply }])
    } catch (e) {
      setMsgs((m) => [...m, { who: 'bot', text: '(error: ' + e.message + ')' }])
    } finally { setBusy(false) }
  }

  const Field = ({ label, value }) => (
    <div>
      <p className="text-[11px] font-semibold tracking-wider uppercase text-on-surface-variant mb-1">{label}</p>
      <div className="flex items-center gap-2">
        <code className="flex-1 min-w-0 truncate bg-surface border border-outline-variant rounded-md px-3 py-2 text-xs text-on-surface font-mono">{value || '—'}</code>
        <button onClick={() => copy(value)} className="text-xs font-semibold px-3 h-9 rounded-md border border-outline-variant text-on-surface hover:bg-surface-container cursor-pointer">Copy</button>
      </div>
    </div>
  )

  return (
    <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant mt-4">
      <div className="flex items-center gap-2 mb-1">
        <Icon name="chat" filled className="text-primary text-[18px]!" />
        <span className="text-base font-semibold">WhatsApp self-service assistant</span>
      </div>
      <p className="text-xs text-on-surface-variant mb-4">
        Customers buy, check status, renew, resend their code or reach support in a WhatsApp chat — in English or Kiswahili, paying by M-Pesa.
      </p>

      <div className="grid gap-3 sm:grid-cols-2 mb-4">
        <Field label="Webhook URL (paste in Meta)" value={webhookUrl} />
        <Field label="Verify token" value={cfg?.verifyToken} />
      </div>
      <ol className="text-xs text-on-surface-variant list-decimal ml-4 space-y-1 mb-5">
        <li>In Meta → WhatsApp → Configuration, set the <b>Callback URL</b> and <b>Verify token</b> above, then subscribe to the <b>messages</b> field.</li>
        <li>Make sure your WhatsApp number credentials are filled in above (they power both sending and the bot).</li>
      </ol>

      <p className="text-[11px] font-semibold tracking-wider uppercase text-on-surface-variant mb-2">Try it — chat as a customer</p>
      <div className="flex flex-wrap items-end gap-2 mb-2">
        <div className="flex-1 min-w-[12rem]">
          <label className={LABEL_CLS}>Chatting as</label>
          <input
            className={INPUT_CLS}
            value={phone}
            placeholder="2547XXXXXXXX"
            onChange={(e) => { setPhone(e.target.value.replace(/\D/g, '')); setMsgs([]) }}
          />
        </div>
        <p className="text-xs text-on-surface-variant flex-1 min-w-[14rem] pb-2">
          Put a real customer's number here to try <b>status</b>, <b>renew</b> or <b>resend my code</b> —
          those answer from that number's own records. Changing it starts a fresh conversation.
        </p>
      </div>
      <div ref={scroller} className="h-56 overflow-y-auto rounded-lg border border-outline-variant bg-surface p-3 space-y-2">
        {msgs.length === 0 && <p className="text-xs text-on-surface-variant">Type <b>hi</b> to start. Try <b>1</b> to buy, <b>2</b> for status, <b>sw</b> for Kiswahili.</p>}
        {msgs.map((m, i) => (
          <div key={i} className={`flex ${m.who === 'you' ? 'justify-end' : 'justify-start'}`}>
            <span className={`max-w-[80%] whitespace-pre-line rounded-2xl px-3 py-2 text-sm ${
              m.who === 'you' ? 'bg-primary text-on-primary'
                : m.pushed ? 'bg-secondary-container text-on-secondary-container border border-secondary/30'
                  : 'bg-surface-container text-on-surface'}`}>
              {/* Marked, because an operator has to be able to tell a reply to
                  something they typed from a message the system sent by itself. */}
              {m.pushed && (
                <span className="block text-[10px] font-bold uppercase tracking-wider opacity-70 mb-1">
                  {m.failed ? '⚠ sent automatically — delivery failed' : '✦ sent automatically'}
                </span>
              )}
              {m.text}
              {m.failed && m.error && (
                <span className="block text-[10px] mt-1 opacity-80">{m.error}</span>
              )}
            </span>
          </div>
        ))}
      </div>
      <div className="flex gap-2 mt-2">
        <input
          className={`${INPUT_CLS} flex-1`}
          placeholder="Type a message…"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') send() }}
        />
        <button onClick={send} disabled={busy} className="bg-primary text-on-primary text-sm font-semibold px-4 h-11 rounded-md disabled:opacity-60 cursor-pointer">Send</button>
      </div>
    </section>
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
        smsProvider: d.smsProvider || 'AFRICASTALKING',
        smsUsername: d.smsUsername || '',
        smsApiKey: '',
        smsSenderId: d.smsSenderId || '',
        whatsappEnabled: d.whatsappEnabled,
        whatsappPhoneNumberId: d.whatsappPhoneNumberId || '',
        whatsappAccessToken: '',
        whatsappAppSecret: '',
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
            <span className="text-base font-semibold block">SMS gateway</span>
            <span className="text-sm text-on-surface-variant">
              Used for vouchers, expiry reminders and campaigns.
            </span>
          </span>
        </label>
        {form.smsEnabled && (() => {
          const twilio = form.smsProvider === 'TWILIO'
          const L = twilio
            ? { user: 'Account SID', key: 'Auth token', sender: 'From number', userPh: 'ACxxxxxxxx', keyPh: 'Twilio auth token', senderPh: '+1508…', senderHint: 'Your Twilio phone number.' }
            : { user: 'Username', key: 'API key', sender: 'Sender ID', userPh: 'sandbox or your username', keyPh: 'from Africa’s Talking', senderPh: 'optional', senderHint: 'Must be registered with them first.' }
          return (
            <div className="mt-4 space-y-4">
              <div className="max-w-xs">
                <label className={LABEL_CLS}>Provider</label>
                <select className={INPUT_CLS} value={form.smsProvider}
                  onChange={(e) => set({ smsProvider: e.target.value })}>
                  <option value="AFRICASTALKING">Africa's Talking</option>
                  <option value="TWILIO">Twilio</option>
                </select>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div>
                  <label className={LABEL_CLS}>{L.user}</label>
                  <input className={INPUT_CLS} value={form.smsUsername}
                    onChange={(e) => set({ smsUsername: e.target.value })} placeholder={L.userPh} />
                </div>
                <div>
                  <label className={LABEL_CLS}>{L.key}</label>
                  <input className={INPUT_CLS} type="password" value={form.smsApiKey}
                    onChange={(e) => set({ smsApiKey: e.target.value })}
                    placeholder={saved?.smsApiKey || L.keyPh} />
                </div>
                <div>
                  <label className={LABEL_CLS}>{L.sender}</label>
                  <input className={INPUT_CLS} value={form.smsSenderId}
                    onChange={(e) => set({ smsSenderId: e.target.value })} placeholder={L.senderPh} />
                  <p className="text-xs text-on-surface-variant mt-1">{L.senderHint}</p>
                </div>
              </div>
            </div>
          )
        })()}
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
            <div className="md:col-span-2">
              <label className={LABEL_CLS}>App secret</label>
              <input className={INPUT_CLS} type="password" value={form.whatsappAppSecret}
                onChange={(e) => set({ whatsappAppSecret: e.target.value })}
                placeholder={saved?.whatsappAppSecret || 'from Meta — App settings → Basic'} />
              {saved?.whatsappInboundVerified ? (
                <p className="text-xs text-secondary mt-1 flex items-start gap-1.5">
                  <Icon name="verified_user" className="text-[15px]! mt-0.5" />
                  Incoming messages are checked against Meta's signature.
                </p>
              ) : (
                <p className="text-xs text-[#b91c1c] mt-1 flex items-start gap-1.5">
                  <Icon name="gpp_maybe" className="text-[15px]! mt-0.5" />
                  Without this, an incoming message's sender is only a claim. Anyone who knows your
                  webhook URL could pose as a customer and read their access code, or pose as a
                  technician and read your whole job queue. Set it before going live.
                </p>
              )}
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

function LoyaltySection({ auth }) {
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => {
    api('/admin/settings/loyalty', { auth }).then(setForm).catch((e) => setMsg({ ok: false, text: e.message }))
  }, [auth])
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      const res = await api('/admin/settings/loyalty', { method: 'PUT', auth, body: form })
      setForm(res)
      setMsg({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  const exampleSpend = 100
  const exampleEarn = form.pointsPerHundredKes
  const exampleMinutes = form.minRedeemMinutes
  const exampleCost = form.minRedeemMinutes * form.pointsPerMinute

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Loyalty programme</span>
            <span className="text-sm text-on-surface-variant">Customers earn points automatically as they pay, and redeem them for free minutes.</span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
      </section>

      {form.enabled && (
        <>
          <section className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Points earned per KES 100 spent</label>
              <input type="number" min="0" max="1000" className={INPUT_CLS} value={form.pointsPerHundredKes}
                onChange={(e) => set({ pointsPerHundredKes: Number(e.target.value) })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Points to redeem 1 free minute</label>
              <input type="number" min="1" max="1000" className={INPUT_CLS} value={form.pointsPerMinute}
                onChange={(e) => set({ pointsPerMinute: Number(e.target.value) })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Smallest redemption (minutes)</label>
              <input type="number" min="1" max="10080" className={INPUT_CLS} value={form.minRedeemMinutes}
                onChange={(e) => set({ minRedeemMinutes: Number(e.target.value) })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Largest redemption (minutes)</label>
              <input type="number" min="1" max="10080" className={INPUT_CLS} value={form.maxRedeemMinutes}
                onChange={(e) => set({ maxRedeemMinutes: Number(e.target.value) })} />
            </div>
          </section>
          <div className="rounded-lg bg-surface-container/60 border border-outline-variant/40 p-3 text-sm text-on-surface-variant">
            <span className="font-medium text-on-surface">In practice:</span> spending KES {exampleSpend} earns{' '}
            <span className="font-mono">{exampleEarn}</span> point(s). A {exampleMinutes}-minute reward costs{' '}
            <span className="font-mono">{exampleCost}</span> point(s), delivered by SMS.
          </div>
        </>
      )}

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
    </form>
  )
}

function AiSection({ auth }) {
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [question, setQuestion] = useState('')
  const [thread, setThread] = useState([]) // { role: 'you'|'ai', text }
  const [asking, setAsking] = useState(false)

  const load = () => api('/admin/settings/ai', { auth }).then((d) => {
    setSaved(d)
    setForm({
      enabled: d.enabled,
      model: d.model || 'llama-3.3-70b-versatile',
      apiKey: '',
      draftTicketReplies: !!d.draftTicketReplies,
    })
  }).catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      await api('/admin/settings/ai', { method: 'PUT', auth, body: form })
      setMsg({ ok: true, text: 'Saved.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function ask(e) {
    e.preventDefault()
    const q = question.trim()
    if (!q) return
    setThread((t) => [...t, { role: 'you', text: q }])
    setQuestion('')
    setAsking(true)
    try {
      const r = await api('/admin/ai/ask', { method: 'POST', auth, body: { question: q } })
      setThread((t) => [...t, { role: 'ai', text: r.answer }])
    } catch (err) {
      setThread((t) => [...t, { role: 'ai', text: 'Sorry — ' + err.message }])
    } finally { setAsking(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  const SUGGESTIONS = ['How much did I sell today?', 'How many vouchers are still unsold?', 'How many routers are online?']

  return (
    <div className="space-y-6 max-w-2xl">
      <form onSubmit={save} className="space-y-4">
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <span className="text-base font-semibold block">Enable the assistant</span>
              <span className="text-sm text-on-surface-variant">Answers questions about your own data using your Groq account.</span>
            </div>
            <Toggle checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
          </div>
          {form.enabled && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Groq API key</label>
                <input type="password" className={INPUT_CLS} value={form.apiKey} autoComplete="new-password"
                  onChange={(e) => setForm({ ...form, apiKey: e.target.value })}
                  placeholder={saved?.apiKey || 'gsk_…'} />
              </div>
              <div>
                <label className={LABEL_CLS}>Model</label>
                <input className={INPUT_CLS} value={form.model}
                  onChange={(e) => setForm({ ...form, model: e.target.value })} placeholder="llama-3.3-70b-versatile" />
              </div>
            </div>
          )}
          <p className="text-xs text-on-surface-variant flex items-start gap-2">
            <Icon name="info" className="text-[16px]! mt-0.5" />
            Your question and a snapshot of your current figures are sent to Groq under your API key. It only reads data — it can't change anything.
          </p>
        </section>

        {form.enabled && (
          <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-3">
            <div className="flex items-start justify-between gap-4">
              <div>
                <span className="text-base font-semibold block">Draft replies to support tickets</span>
                <span className="text-sm text-on-surface-variant">
                  Every new ticket gets a suggested first reply waiting on it, written from that
                  customer's own account, the network's state right now, and what closed similar
                  tickets before.
                </span>
              </div>
              <Toggle checked={form.draftTicketReplies}
                onChange={(e) => setForm({ ...form, draftTicketReplies: e.target.checked })} />
            </div>
            <p className="text-xs text-on-surface-variant flex items-start gap-2">
              <Icon name="lock" className="text-[16px]! mt-0.5" />
              Nothing is ever sent on its own. A person reads the draft and presses Send, edits it,
              or ignores it — and the facts behind it are shown alongside so it can be checked.
            </p>
          </section>
        )}
        {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
      </form>

      {saved?.working && (
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
          <h3 className="text-sm font-semibold mb-3">Ask a question</h3>
          <div className="space-y-3 mb-3 max-h-96 overflow-y-auto">
            {thread.length === 0 && (
              <div className="flex flex-wrap gap-2">
                {SUGGESTIONS.map((s) => (
                  <button key={s} type="button" onClick={() => setQuestion(s)}
                    className="text-xs bg-surface-container hover:bg-primary/10 border border-outline-variant/60 px-2.5 py-1.5 rounded-full cursor-pointer">
                    {s}
                  </button>
                ))}
              </div>
            )}
            {thread.map((m, i) => (
              <div key={i} className={`flex ${m.role === 'you' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-lg px-3 py-2 text-sm whitespace-pre-wrap ${
                  m.role === 'you' ? 'bg-primary text-on-primary' : 'bg-surface-container text-on-surface'
                }`}>{m.text}</div>
              </div>
            ))}
            {asking && <p className="text-sm text-on-surface-variant">Thinking…</p>}
          </div>
          <form onSubmit={ask} className="flex gap-2">
            <input className={INPUT_CLS} value={question} onChange={(e) => setQuestion(e.target.value)}
              placeholder="e.g. What were my best-selling plans today?" />
            <button type="submit" disabled={asking || !question.trim()}
              className="px-4 py-2 rounded-lg bg-primary text-on-primary text-sm font-semibold disabled:opacity-40 cursor-pointer">
              Ask
            </button>
          </form>
        </section>
      )}
    </div>
  )
}

function AlertsSection({ auth }) {
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [preview, setPreview] = useState(null)

  const load = () => api('/admin/settings/alerts', { auth }).then(setForm).catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      const res = await api('/admin/settings/alerts', { method: 'PUT', auth, body: form })
      setForm(res)
      setMsg({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function testDigest() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/settings/alerts/digest/test', { method: 'POST', auth })
      setMsg({ ok: true, text: r.message })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  /** Builds the briefing and shows it here, without messaging anyone. */
  async function showPreview() {
    setBusy(true); setMsg(null)
    try {
      setPreview((await api('/admin/settings/alerts/digest/preview', { auth })).preview)
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Router-down alerts</span>
            <span className="text-sm text-on-surface-variant">Text your alert phone when a router goes offline, and again when it recovers.</span>
          </div>
          <Toggle checked={form.routerOfflineAlert} onChange={(e) => set({ routerOfflineAlert: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant">Alerts go to the phone set under SMS &amp; WhatsApp.</p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Outage compensation</span>
            <span className="text-sm text-on-surface-variant">When the network recovers, push every active subscriber's expiry back by the downtime.</span>
          </div>
          <Toggle checked={form.outageCompensationEnabled} onChange={(e) => set({ outageCompensationEnabled: e.target.checked })} />
        </div>
        {form.outageCompensationEnabled && (
          <div className="max-w-xs">
            <label className={LABEL_CLS}>Only compensate outages longer than (minutes)</label>
            <input type="number" min="0" max="1440" className={INPUT_CLS} value={form.minOutageMinutes}
              onChange={(e) => set({ minOutageMinutes: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">Short blips are ignored. Applies to home (PPPoE) subscriptions.</p>
          </div>
        )}
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Tell customers about outages</span>
            <span className="text-sm text-on-surface-variant">
              When routers go down together, text the customers on those routers once — with an
              estimate — instead of leaving them to find out and ring you.
            </span>
          </div>
          <Toggle checked={form.customerOutageNotice} onChange={(e) => set({ customerOutageNotice: e.target.checked })} />
        </div>
        {form.customerOutageNotice && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className={LABEL_CLS}>Wait before telling anyone (minutes)</label>
              <input type="number" min="1" max="240" className={INPUT_CLS} value={form.outageNotifyAfterMinutes}
                onChange={(e) => set({ outageNotifyAfterMinutes: Number(e.target.value) })} />
              <p className="text-xs text-on-surface-variant mt-1">Most blips fix themselves; a message about one is worse than none.</p>
            </div>
            <div>
              <label className={LABEL_CLS}>Estimate to give them (minutes)</label>
              <input type="number" min="5" max="1440" className={INPUT_CLS} value={form.outageEtaMinutes}
                onChange={(e) => set({ outageEtaMinutes: Number(e.target.value) })} />
            </div>
          </div>
        )}
        <div className="flex items-start justify-between gap-4 pt-1">
          <div>
            <span className="text-base font-semibold block">Public status page</span>
            <span className="text-sm text-on-surface-variant">
              Publish current and recent outages at <span className="font-mono text-xs">/status</span> — areas and
              times only, no customer detail.
            </span>
          </div>
          <Toggle checked={form.statusPageEnabled} onChange={(e) => set({ statusPageEnabled: e.target.checked })} />
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Daily briefing</span>
            <span className="text-sm text-on-surface-variant">
              One message a day with everything worth knowing: takings against last week, who joined
              and who lapsed, jobs nobody has taken, what the revenue audit found, and the state of
              the network. Sections with nothing to report are left out.
            </span>
          </div>
          <Toggle checked={form.salesDigestEnabled} onChange={(e) => set({ salesDigestEnabled: e.target.checked })} />
        </div>
        {form.salesDigestEnabled && (
          <div className="max-w-xs">
            <label className={LABEL_CLS}>Send at (hour, 0–23)</label>
            <input type="number" min="0" max="23" className={INPUT_CLS} value={form.salesDigestHour}
              onChange={(e) => set({ salesDigestHour: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">Server time. Goes to your alert phone and SMTP from-address.</p>
          </div>
        )}
        {preview && (
          <pre className="text-xs whitespace-pre-wrap bg-surface-container rounded-lg p-3">{preview}</pre>
        )}
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}

      <div className="flex flex-wrap items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        <button type="button" disabled={busy}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-medium disabled:opacity-40"
          onClick={showPreview}>See today's briefing</button>
        <button type="button" disabled={busy}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-medium disabled:opacity-40"
          onClick={testDigest}>Send it to me now</button>
      </div>
    </form>
  )
}

function CreditSection({ auth }) {
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/credit', { auth })
    .then((d) => { setData(d); setForm(d.settings) })
    .catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      setForm(await api('/admin/settings/credit', { method: 'PUT', auth, body: form }))
      setMsg({ ok: true, text: 'Saved.' })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function writeOff(id) {
    try {
      await api(`/admin/credit/${id}/write-off`, { method: 'POST', auth })
      await load()
    } catch (err) { setMsg({ ok: false, text: err.message }) }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Offer WiFi on credit</span>
            <span className="text-sm text-on-surface-variant">
              A customer whose money hasn't landed yet gets online now, and the amount is added to their
              next M-Pesa purchase.
            </span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant">
          This is your money at risk, so it starts switched off. Nobody is chased for a debt — the worst
          case per customer is one small pass given away, and they take no more credit afterwards.
        </p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-3">Who qualifies</p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Passes they must have paid for</label>
            <input type="number" min="1" max="50" className={INPUT_CLS} value={form.minPurchases}
              onChange={(e) => set({ minPurchases: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Days they must have been buying</label>
            <input type="number" min="0" max="365" className={INPUT_CLS} value={form.minDaysKnown}
              onChange={(e) => set({ minDaysKnown: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Dearest package lendable (KES)</label>
            <input type="number" min="1" step="10" className={INPUT_CLS} value={form.maxAdvance}
              onChange={(e) => set({ maxAdvance: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Missed repayments before cut off</label>
            <input type="number" min="1" max="10" className={INPUT_CLS} value={form.maxDefaults}
              onChange={(e) => set({ maxDefaults: Number(e.target.value) })} />
          </div>
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-3">Terms</p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Service fee (%)</label>
            <input type="number" min="0" max="50" className={INPUT_CLS} value={form.feePercent}
              onChange={(e) => set({ feePercent: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">Zero is a fair default — the point is the customer coming back.</p>
          </div>
          <div>
            <label className={LABEL_CLS}>Settle within (hours)</label>
            <input type="number" min="1" max="720" className={INPUT_CLS} value={form.repayWithinHours}
              onChange={(e) => set({ repayWithinHours: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">After this a reminder goes out; a cycle later it's written off.</p>
          </div>
        </div>
      </section>

      {data && (
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
          <p className="text-sm font-semibold mb-1">
            Out on loan: KES {Number(data.outstandingTotal || 0)} across {data.outstanding.length} customer(s)
          </p>
          <p className="text-xs text-on-surface-variant mb-3">
            Written off so far: {data.defaultedCount} for KES {Number(data.defaultedTotal || 0)}.
          </p>
          {data.outstanding.length > 0 && (
            <div className="overflow-x-auto table-scroll">
              <table className="data-table w-full">
                <thead>
                  <tr><th>Customer</th><th>Pass</th><th className="text-right">Due</th><th>By</th><th className="text-right"></th></tr>
                </thead>
                <tbody>
                  {data.outstanding.map((a) => (
                    <tr key={a.id}>
                      <td className="font-mono text-xs">{a.phoneNumber}</td>
                      <td className="font-mono text-xs">{a.voucherCode}</td>
                      <td className="text-right tabular-nums">KES {Number(a.totalDue)}</td>
                      <td className="text-xs">{new Date(a.dueAt).toLocaleDateString()}</td>
                      <td className="text-right">
                        <button type="button" onClick={() => writeOff(a.id)}
                          className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high">
                          Write off
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
    </form>
  )
}

/**
 * Field jobs — the technician's WhatsApp assistant and the sweeps that chase
 * work nobody has touched. The preview matters more than it looks: an operator
 * who cannot try the conversation will not trust it enough to switch it on.
 */
function FieldSection({ auth }) {
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [sim, setSim] = useState({ phone: '', text: 'jobs' })
  const [simOut, setSimOut] = useState(null)

  useEffect(() => {
    api('/admin/field/settings', { auth }).then(setForm).catch((e) => setMsg({ ok: false, text: e.message }))
  }, [auth])
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      setForm(await api('/admin/field/settings', { method: 'PUT', auth, body: form }))
      setMsg({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function sweepNow() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/field/sweep', { method: 'POST', auth })
      setMsg({ ok: true, text: `Chased ${r.nudged} quiet job(s), escalated ${r.escalated} nobody had taken.` })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function runSim() {
    setSimOut(null)
    try {
      setSimOut(await api('/admin/field/simulate', { method: 'POST', auth, body: sim }))
    } catch (err) {
      setSimOut({ reply: '', note: err.message })
    }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Technicians work jobs from WhatsApp</span>
            <span className="text-sm text-on-surface-variant">
              A message from the number on a technician's record opens their job list instead of the
              customer menu — see the job, tell the customer you're coming, leave a note, close it.
            </span>
          </div>
          <Toggle checked={form.whatsappEnabled} onChange={(e) => set({ whatsappEnabled: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant">
          Technicians without a phone number on their record simply carry on using the Field Connect app.
          Nothing here replaces it.
        </p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-1">Chasing quiet work</p>
        <p className="text-xs text-on-surface-variant mb-3">
          Each job is chased once per window, not on every sweep, so a long job isn't pestered.
        </p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Nudge the technician after (hours)</label>
            <input type="number" min="1" max="168" className={INPUT_CLS} value={form.staleJobHours}
              onChange={(e) => set({ staleJobHours: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">Counted from the last note, not the last save.</p>
          </div>
          <div>
            <label className={LABEL_CLS}>Tell you nobody has taken it after (minutes)</label>
            <input type="number" min="5" max="1440" className={INPUT_CLS} value={form.unassignedAlertMinutes}
              onChange={(e) => set({ unassignedAlertMinutes: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">Goes to your alert number under SMS &amp; WhatsApp.</p>
          </div>
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-sm font-semibold block">Morning job list</span>
            <span className="text-sm text-on-surface-variant">
              Each technician gets what they're carrying, at the start of the day.
            </span>
          </div>
          <Toggle checked={form.dailySummaryEnabled} onChange={(e) => set({ dailySummaryEnabled: e.target.checked })} />
        </div>
        {form.dailySummaryEnabled && (
          <div className="max-w-[12rem]">
            <label className={LABEL_CLS}>Send at (hour, 0–23)</label>
            <input type="number" min="0" max="23" className={INPUT_CLS} value={form.dailySummaryHour}
              onChange={(e) => set({ dailySummaryHour: Number(e.target.value) })} />
          </div>
        )}
        <div className="flex items-start justify-between gap-4 pt-2 border-t border-outline-variant/40">
          <div>
            <span className="text-sm font-semibold block">Tell the customer when a job is closed</span>
            <span className="text-sm text-on-surface-variant">
              In the technician's own words, where they left any.
            </span>
          </div>
          <Toggle checked={form.notifyCustomerOnClose} onChange={(e) => set({ notifyCustomerOnClose: e.target.checked })} />
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-1">Try the technician conversation</p>
        <p className="text-xs text-on-surface-variant mb-3">
          Nothing is sent. Use a technician's real number — that is how they're recognised.
        </p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>From (technician's number)</label>
            <input className={INPUT_CLS} placeholder="2547XXXXXXXX" value={sim.phone}
              onChange={(e) => setSim((s) => ({ ...s, phone: e.target.value }))} />
          </div>
          <div>
            <label className={LABEL_CLS}>Message</label>
            <input className={INPUT_CLS} value={sim.text}
              onChange={(e) => setSim((s) => ({ ...s, text: e.target.value }))} />
          </div>
        </div>
        <button type="button" onClick={runSim}
          className="mt-3 px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high">
          Send to the preview
        </button>
        {simOut && (
          <div className="mt-3">
            {simOut.reply
              ? <pre className="text-xs whitespace-pre-wrap bg-surface-container rounded-lg p-3">{simOut.reply}</pre>
              : <p className="text-xs text-on-surface-variant">{simOut.note}</p>}
          </div>
        )}
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <div className="flex items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        <button type="button" onClick={sweepNow} disabled={busy}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-semibold cursor-pointer hover:bg-surface-container-high disabled:opacity-50">
          Chase now
        </button>
      </div>
    </form>
  )
}

const VERDICT_TONE = {
  CRITICAL: 'bg-[#b91c1c]/10 text-[#b91c1c]',
  WARNING: 'bg-[#f59e0b]/10 text-[#b45309]',
  OK: 'bg-secondary-container text-on-secondary-container',
  UNDERUSED: 'bg-primary-container/40 text-primary',
  UNKNOWN: 'bg-surface-container-high text-on-surface-variant',
}

/**
 * Capacity planning. Each site's link capacity is typed in here rather than
 * measured, because nothing can measure it from the outside — it is what the
 * operator bought. Without it the busy hour is a number with no denominator,
 * so the screen asks for it plainly instead of hiding the site.
 */
function CapacitySection({ auth }) {
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const [drafts, setDrafts] = useState({})

  const load = () => api('/admin/capacity', { auth })
    .then((d) => { setData(d); setForm(d.settings) })
    .catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      await api('/admin/capacity/settings', { method: 'PUT', auth, body: form })
      setMsg({ ok: true, text: 'Saved.' })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function saveCapacity(routerId) {
    const value = drafts[routerId]
    setBusy(true); setMsg(null)
    try {
      await api(`/admin/capacity/routers/${routerId}`, {
        method: 'PUT', auth, body: { capacityMbps: value === '' ? null : Number(value) },
      })
      setDrafts((d) => ({ ...d, [routerId]: undefined }))
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <form onSubmit={save} className="space-y-6 max-w-3xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Watch capacity</span>
            <span className="text-sm text-on-surface-variant">
              Reads each site's busy hour from traffic you're already recording, and says how many
              weeks of growth are left before it fills.
            </span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant flex items-start gap-2">
          <Icon name="info" className="text-[16px]! mt-0.5" />
          Advisory only. Nothing here reconfigures a router or changes anyone's package — buying
          backhaul is not a decision to hand to a scheduler.
        </p>
        {data?.note && <p className="text-xs text-[#b45309]">{data.note}</p>}
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-3">Your sites</p>
        {(data?.sites || []).length === 0 ? (
          <p className="text-xs text-on-surface-variant">No enabled routers yet.</p>
        ) : (
          <div className="space-y-3">
            {data.sites.map((s) => (
              <div key={s.routerId} className="border border-outline-variant/40 rounded-lg p-3">
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div>
                    <span className="font-semibold text-sm">{s.name}</span>
                    {s.location && <span className="text-xs text-on-surface-variant"> · {s.location}</span>}
                    <p className="text-xs text-on-surface-variant">
                      Busy hour {s.busyHourMbps} Mbps
                      {s.capacityMbps ? ` of ${s.capacityMbps} Mbps` : ''}
                      {' · '}{s.daysOfData} day(s) of data
                    </p>
                  </div>
                  <span className={`px-2 py-0.5 rounded-full text-[11px] font-semibold shrink-0 ${VERDICT_TONE[s.verdict] || ''}`}>
                    {s.verdict}
                  </span>
                </div>

                {s.usedPercent != null && (
                  <div className="h-2 rounded-full bg-surface-container-high overflow-hidden mb-2">
                    <div
                      className={`h-full ${s.usedPercent >= form.criticalPercent ? 'bg-[#b91c1c]'
                        : s.usedPercent >= form.warnPercent ? 'bg-[#f59e0b]' : 'bg-primary'}`}
                      style={{ width: `${Math.min(100, s.usedPercent)}%` }}
                    />
                  </div>
                )}

                <p className="text-xs text-on-surface-variant">{s.advice}</p>

                <div className="flex items-end gap-2 mt-3">
                  <div className="max-w-[10rem]">
                    <label className={LABEL_CLS}>Link capacity (Mbps)</label>
                    <input type="number" min="1" className={INPUT_CLS}
                      value={drafts[s.routerId] ?? (s.capacityMbps ?? '')}
                      onChange={(e) => setDrafts((d) => ({ ...d, [s.routerId]: e.target.value }))} />
                  </div>
                  <button type="button" disabled={busy} onClick={() => saveCapacity(s.routerId)}
                    className="px-3 py-2 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer disabled:opacity-50">
                    Save
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      {(data?.heaviest || []).length > 0 && (
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
          <p className="text-sm font-semibold mb-1">Heaviest users</p>
          <p className="text-xs text-on-surface-variant mb-3">
            Worth a word about a bigger package — or a look at whether one code is being shared
            around a whole building.
          </p>
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr><th>User</th><th>Site</th><th className="text-right">Used</th><th className="text-right">Share of site</th></tr>
              </thead>
              <tbody>
                {data.heaviest.map((u) => (
                  <tr key={`${u.routerId}-${u.userKey}`}>
                    <td className="font-mono text-xs">{u.userKey}</td>
                    <td className="text-xs">{u.routerName}</td>
                    <td className="text-right tabular-nums">{u.gigabytes} GB</td>
                    <td className="text-right tabular-nums">{u.shareOfSitePercent}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-3">Thresholds</p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>Days of history to read</label>
            <input type="number" min="7" max="180" className={INPUT_CLS} value={form.lookbackDays}
              onChange={(e) => set({ lookbackDays: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Getting full at (%)</label>
            <input type="number" min="10" max="99" className={INPUT_CLS} value={form.warnPercent}
              onChange={(e) => set({ warnPercent: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Full at (%)</label>
            <input type="number" min="11" max="100" className={INPUT_CLS} value={form.criticalPercent}
              onChange={(e) => set({ criticalPercent: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Capacity going to waste below (%)</label>
            <input type="number" min="1" max="90" className={INPUT_CLS} value={form.underusedPercent}
              onChange={(e) => set({ underusedPercent: Number(e.target.value) })} />
          </div>
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-sm font-semibold block">Text me weekly, if anything needs saying</span>
            <span className="text-sm text-on-surface-variant">
              Nothing is sent on a quiet week, so a message means something.
            </span>
          </div>
          <Toggle checked={form.notify} onChange={(e) => set({ notify: e.target.checked })} />
        </div>
        {form.notify && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className={LABEL_CLS}>Day</label>
              <select className={INPUT_CLS} value={form.notifyDayOfWeek}
                onChange={(e) => set({ notifyDayOfWeek: Number(e.target.value) })}>
                {['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
                  .map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
              </select>
            </div>
            <div>
              <label className={LABEL_CLS}>Hour (0–23)</label>
              <input type="number" min="0" max="23" className={INPUT_CLS} value={form.notifyHour}
                onChange={(e) => set({ notifyHour: Number(e.target.value) })} />
            </div>
          </div>
        )}
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
    </form>
  )
}

/**
 * Off-peak offers. The bar chart is not decoration: an operator will not hand
 * a discount to a scheduler on trust, and seeing that the hours it picked are
 * genuinely the empty ones is what makes switching it on a reasonable thing
 * to do.
 */
function OffPeakSection({ auth }) {
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/offpeak', { auth })
    .then((d) => { setData(d); setForm(d.settings) })
    .catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      await api('/admin/offpeak/settings', { method: 'PUT', auth, body: form })
      setMsg({ ok: true, text: 'Saved.' })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function syncNow() {
    setBusy(true); setMsg(null)
    try {
      const r = await api('/admin/offpeak/sync', { method: 'POST', auth })
      setMsg({
        ok: true,
        text: r.inWindow
          ? `Quiet hours right now — the offer is ${r.offerRunning ? 'running' : 'not running'}${r.skipped ? ` (${r.skipped})` : ''}.`
          : 'Not in the quiet hours, so no offer is running.',
      })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  const hours = data?.hours || []
  const peak = Math.max(1, ...hours.map((h) => Number(h.megabytes || 0)))
  const start = form.autoWindow && data?.suggestedStart != null ? data.suggestedStart : form.windowStartHour
  const end = form.autoWindow && data?.suggestedEnd != null ? data.suggestedEnd : form.windowEndHour
  const inWindow = (h) => (start === end ? false : start < end ? h >= start && h < end : h >= start || h < end)

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Run a night rate</span>
            <span className="text-sm text-on-surface-variant">
              Across the quiet hours, every package is discounted automatically. The offer opens and
              closes itself.
            </span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant">
          A promotion you started by hand always wins: this will not stack a second discount on top of
          one of yours, and it only ever closes offers it opened itself.
        </p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <div className="flex items-baseline justify-between mb-1">
          <p className="text-sm font-semibold">Your day, hour by hour</p>
          <span className="text-xs text-on-surface-variant">{data?.daysOfData || 0} day(s) of traffic</span>
        </div>
        {data?.note
          ? <p className="text-xs text-[#b45309] mb-3">{data.note}</p>
          : (
            <p className="text-xs text-on-surface-variant mb-3">
              Shaded hours are the window in force. Bars are traffic. A faint dashed column is an hour
              with nothing recorded — never treated as quiet, because nothing looks emptier than an
              hour that was never measured.
            </p>
          )}
        <div className="flex items-end gap-[3px] h-28">
          {hours.map((h) => (
            <div key={h.hour} className="flex-1 flex flex-col justify-end items-center h-full"
              title={h.observed === false
                ? `${String(h.hour).padStart(2, '0')}:00 — no traffic recorded in this hour`
                : `${String(h.hour).padStart(2, '0')}:00 — ${Number(h.megabytes).toLocaleString()} MB, ${h.sales} sale(s)`}>
              <div
                className={`w-full rounded-t ${h.observed === false ? 'bg-outline-variant/30 border-t border-dashed border-outline'
                  : inWindow(h.hour) ? 'bg-primary/70' : 'bg-outline-variant'}`}
                style={{ height: `${h.observed === false ? 100 : Math.max(2, (Number(h.megabytes) / peak) * 100)}%` }}
              />
            </div>
          ))}
        </div>
        <div className="flex gap-[3px] mt-1">
          {hours.map((h) => (
            <div key={h.hour} className="flex-1 text-center text-[9px] text-on-surface-variant tabular-nums">
              {h.hour % 3 === 0 ? String(h.hour).padStart(2, '0') : ''}
            </div>
          ))}
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-sm font-semibold block">Work the quiet hours out for me</span>
            <span className="text-sm text-on-surface-variant">
              {data?.suggestedStart != null
                ? `From the last ${form.lookbackDays} days that is ${String(data.suggestedStart).padStart(2, '0')}:00 to ${String(data.suggestedEnd).padStart(2, '0')}:00.`
                : 'Not enough traffic recorded yet — the hours below are used until there is.'}
            </span>
          </div>
          <Toggle checked={form.autoWindow} onChange={(e) => set({ autoWindow: e.target.checked })} />
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <div>
            <label className={LABEL_CLS}>Quiet hours start</label>
            <input type="number" min="0" max="23" className={INPUT_CLS} value={form.windowStartHour}
              onChange={(e) => set({ windowStartHour: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>…and end</label>
            <input type="number" min="0" max="23" className={INPUT_CLS} value={form.windowEndHour}
              onChange={(e) => set({ windowEndHour: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">May cross midnight.</p>
          </div>
          <div>
            <label className={LABEL_CLS}>Discount (%)</label>
            <input type="number" min="1" max="90" className={INPUT_CLS} value={form.discountPercent}
              onChange={(e) => set({ discountPercent: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">
              A KES 100 pass becomes KES {data?.exampleHundred ?? '—'}.
            </p>
          </div>
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-sm font-semibold block">Tell people when it opens</span>
            <span className="text-sm text-on-surface-variant">
              A discount nobody knows about sells nothing.
            </span>
          </div>
          <Toggle checked={form.notify} onChange={(e) => set({ notify: e.target.checked })} />
        </div>
        {form.notify && (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <div>
              <label className={LABEL_CLS}>Who to tell</label>
              <select className={INPUT_CLS} value={form.audience}
                onChange={(e) => set({ audience: e.target.value })}>
                {(data?.audiences || []).map((a) => (
                  <option key={a.key} value={a.key}>{a.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className={LABEL_CLS}>Most messages per night</label>
              <input type="number" min="1" max="5000" className={INPUT_CLS} value={form.maxMessagesPerRun}
                onChange={(e) => set({ maxMessagesPerRun: Number(e.target.value) })} />
            </div>
            <div>
              <label className={LABEL_CLS}>Leave at least (days) between</label>
              <input type="number" min="1" max="90" className={INPUT_CLS} value={form.minDaysBetweenMessages}
                onChange={(e) => set({ minDaysBetweenMessages: Number(e.target.value) })} />
              <p className="text-xs text-on-surface-variant mt-1">Per customer, not per run.</p>
            </div>
          </div>
        )}
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <div className="flex flex-wrap items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        <button type="button" onClick={syncNow} disabled={busy}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-semibold cursor-pointer disabled:opacity-50">
          Apply now
        </button>
      </div>
    </form>
  )
}

const PAYOUT_STATUS_TONE = {
  PAID: 'bg-secondary-container text-on-secondary-container',
  MANUAL: 'bg-surface-container-high text-on-surface-variant',
  SENT: 'bg-primary-container/40 text-primary',
  PENDING: 'bg-[#f59e0b]/10 text-[#b45309]',
  FAILED: 'bg-[#b91c1c]/10 text-[#b91c1c]',
}

/**
 * Agent payouts. The screen leads with who is owed what and why anyone is
 * blocked, because the failure this replaces is an agent quietly missing from
 * the run — being left out for want of a phone number should be impossible to
 * miss, not something discovered when they complain.
 */
function AgentPayoutSection({ auth }) {
  const [data, setData] = useState(null)
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/agents/payouts', { auth })
    .then((d) => { setData(d); setForm(d.settings) })
    .catch((e) => setMsg({ ok: false, text: e.message }))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      await api('/admin/agents/payouts/settings', { method: 'PUT', auth, body: form })
      setMsg({ ok: true, text: 'Saved.' })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function act(path, ok) {
    setBusy(true); setMsg(null)
    try {
      const r = await api(path, { method: 'POST', auth })
      setMsg({ ok: true, text: ok(r) })
      await load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  const dueTotal = (data?.due || []).filter((d) => !d.blockedBecause)
    .reduce((sum, d) => sum + Number(d.owed || 0), 0)

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Pay commission on a schedule</span>
            <span className="text-sm text-on-surface-variant">
              On pay day the round is worked out and each agent is sent their balance over M-Pesa.
            </span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
        <div className="flex items-start justify-between gap-4 pt-3 border-t border-outline-variant/40">
          <div>
            <span className="text-sm font-semibold block">Send without asking me</span>
            <span className="text-sm text-on-surface-variant">
              Off is the sensible first month: the round is still worked out and queued, you just
              press the button.
            </span>
          </div>
          <Toggle checked={form.autoSend} onChange={(e) => set({ autoSend: e.target.checked })} />
        </div>
        {!data?.canSendMoney && (
          <p className="text-xs text-[#b45309] flex items-start gap-2">
            <Icon name="warning" className="text-[16px]! mt-0.5" />
            M-Pesa cannot send money yet. Add the initiator name and security credential under
            Settings → Payment gateways, and make sure your callback URL is publicly reachable.
            Until then payouts can be prepared but not released.
          </p>
        )}
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-3">When, and how much</p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div>
            <label className={LABEL_CLS}>How often</label>
            <select className={INPUT_CLS} value={form.frequency}
              onChange={(e) => set({ frequency: e.target.value })}>
              <option value="WEEKLY">Every week</option>
              <option value="MONTHLY">Every month</option>
            </select>
          </div>
          {form.frequency === 'WEEKLY' ? (
            <div>
              <label className={LABEL_CLS}>Pay day</label>
              <select className={INPUT_CLS} value={form.dayOfWeek}
                onChange={(e) => set({ dayOfWeek: Number(e.target.value) })}>
                {['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
                  .map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
              </select>
            </div>
          ) : (
            <div>
              <label className={LABEL_CLS}>Day of the month</label>
              <input type="number" min="1" max="31" className={INPUT_CLS} value={form.dayOfMonth}
                onChange={(e) => set({ dayOfMonth: Number(e.target.value) })} />
              <p className="text-xs text-on-surface-variant mt-1">The 31st still pays on the last day of a short month.</p>
            </div>
          )}
          <div>
            <label className={LABEL_CLS}>At (hour, 0–23)</label>
            <input type="number" min="0" max="23" className={INPUT_CLS} value={form.runHour}
              onChange={(e) => set({ runHour: Number(e.target.value) })} />
          </div>
          <div>
            <label className={LABEL_CLS}>Don't pay less than (KES)</label>
            <input type="number" min="1" step="50" className={INPUT_CLS} value={form.minimumAmount}
              onChange={(e) => set({ minimumAmount: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">Smaller balances roll over to the next round.</p>
          </div>
          <div>
            <label className={LABEL_CLS}>Most to pay in one round (KES)</label>
            <input type="number" min="1" step="1000" className={INPUT_CLS} value={form.maxPerRun}
              onChange={(e) => set({ maxPerRun: Number(e.target.value) })} />
            <p className="text-xs text-on-surface-variant mt-1">A ceiling, so a mistake can't empty the float.</p>
          </div>
          <div>
            <label className={LABEL_CLS}>Pay from shortcode (optional)</label>
            <input className={INPUT_CLS} value={form.b2cShortCode || ''}
              onChange={(e) => set({ b2cShortCode: e.target.value })} placeholder="Same as collection" />
            <p className="text-xs text-on-surface-variant mt-1">Only if Safaricom gave you a separate B2C shortcode.</p>
          </div>
        </div>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <p className="text-sm font-semibold mb-1">
          Owed right now: KES {dueTotal.toLocaleString()} across {(data?.due || []).filter((d) => !d.blockedBecause).length} agent(s)
        </p>
        {(data?.due || []).length === 0 ? (
          <p className="text-xs text-on-surface-variant">Nobody is over the minimum yet.</p>
        ) : (
          <div className="overflow-x-auto table-scroll mt-2">
            <table className="data-table w-full">
              <thead>
                <tr><th>Agent</th><th>Number</th><th className="text-right">Owed</th><th></th></tr>
              </thead>
              <tbody>
                {data.due.map((d) => (
                  <tr key={d.agentId}>
                    <td>{d.agentName} <span className="text-xs text-on-surface-variant">{d.code}</span></td>
                    <td className="font-mono text-xs">{d.phoneNumber || '—'}</td>
                    <td className="text-right tabular-nums">KES {Number(d.owed).toLocaleString()}</td>
                    <td className="text-xs text-[#b45309]">{d.blockedBecause}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {(data?.history || []).length > 0 && (
        <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
          <p className="text-sm font-semibold mb-2">Recent payouts</p>
          <div className="overflow-x-auto table-scroll">
            <table className="data-table w-full">
              <thead>
                <tr><th>Agent</th><th className="text-right">Amount</th><th>Status</th><th>Reference</th><th className="text-right"></th></tr>
              </thead>
              <tbody>
                {data.history.slice(0, 25).map((p) => (
                  <tr key={p.id}>
                    <td>{p.agentName}</td>
                    <td className="text-right tabular-nums">KES {Number(p.amount).toLocaleString()}</td>
                    <td>
                      <span className={`px-2 py-0.5 rounded-full text-[11px] font-semibold ${PAYOUT_STATUS_TONE[p.status] || ''}`}>
                        {p.status}
                      </span>
                    </td>
                    <td className="font-mono text-xs">{p.receipt || <span className="text-on-surface-variant">{p.error || '—'}</span>}</td>
                    <td className="text-right whitespace-nowrap">
                      {p.status === 'PENDING' && (
                        <>
                          <button type="button" disabled={busy}
                            onClick={() => act(`/admin/agents/payouts/${p.id}/release`, () => 'Sent to M-Pesa.')}
                            className="px-3 py-1.5 rounded-lg bg-primary text-on-primary text-xs font-semibold cursor-pointer disabled:opacity-50">
                            Release
                          </button>
                          <button type="button" disabled={busy}
                            onClick={() => act(`/admin/agents/payouts/${p.id}/cancel`, () => 'Cancelled.')}
                            className="ml-2 px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer disabled:opacity-50">
                            Cancel
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <div className="flex flex-wrap items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        <button type="button" disabled={busy}
          onClick={() => act('/admin/agents/payouts/run',
            (r) => `Prepared ${r.queued} payout(s) totalling KES ${Number(r.total).toLocaleString()}${r.sent ? `, ${r.sent} sent` : ''}.`)}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-semibold cursor-pointer disabled:opacity-50">
          Work out this round now
        </button>
      </div>
    </form>
  )
}

function PaybillSection({ auth }) {
  const [form, setForm] = useState(null)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  useEffect(() => {
    api('/admin/settings/paybill', { auth }).then(setForm).catch((e) => setMsg({ ok: false, text: e.message }))
  }, [auth])
  const set = (patch) => { setForm((f) => ({ ...f, ...patch })); setMsg(null) }

  async function save(e) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      setForm(await api('/admin/settings/paybill', { method: 'PUT', auth, body: form }))
      setMsg({ ok: true, text: 'Saved.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  if (!form) return <Skeleton className="h-64" />

  return (
    <form onSubmit={save} className="space-y-6 max-w-2xl">
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Issue a pass automatically</span>
            <span className="text-sm text-on-surface-variant">
              When money reaches your paybill and it isn't a home-line customer, buy them the best package
              the amount covers and send the code by WhatsApp or SMS.
            </span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant">
          The captive portal shows each device a short account number to type, which is how the payment is
          tied back to that device. Without one it falls back to matching on the paying phone number.
        </p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Let the device straight on</span>
            <span className="text-sm text-on-surface-variant">
              Put the paying device online without it typing anything at all.
            </span>
          </div>
          <Toggle checked={form.autoLoginByMac} onChange={(e) => set({ autoLoginByMac: e.target.checked })} />
        </div>
        <p className="text-xs text-on-surface-variant">
          Needs <span className="font-mono">login-by=mac</span> switched on in your MikroTik hotspot server
          profile. The code is still sent either way, so nobody is stranded if the router refuses.
        </p>
      </section>

      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40 space-y-4">
        <div className="max-w-xs">
          <label className={LABEL_CLS}>Account number stays valid for (minutes)</label>
          <input type="number" min="5" max="1440" className={INPUT_CLS} value={form.payCodeMinutes}
            onChange={(e) => set({ payCodeMinutes: Number(e.target.value) })} />
          <p className="text-xs text-on-surface-variant mt-1">Long enough for somebody to walk to an M-Pesa agent.</p>
        </div>
        <div className="max-w-xs">
          <label className={LABEL_CLS}>Never auto-issue above (KES)</label>
          <input type="number" min="0" step="50" className={INPUT_CLS} value={form.maxAmount}
            onChange={(e) => set({ maxAmount: Number(e.target.value) })} />
          <p className="text-xs text-on-surface-variant mt-1">
            A stranger sending far more than any package costs is usually a mistake. Above this it waits in
            the unmatched list for you, rather than being turned into a small pass.
          </p>
        </div>
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Text them when it's not enough</span>
            <span className="text-sm text-on-surface-variant">If the amount is under your cheapest package, say so instead of staying silent.</span>
          </div>
          <Toggle checked={form.notifyOnShortfall} onChange={(e) => set({ notifyOnShortfall: e.target.checked })} />
        </div>
      </section>

      {msg && <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>}
      <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
    </form>
  )
}

function EmailSection({ auth }) {
  const [form, setForm] = useState(null)
  const [saved, setSaved] = useState(null)
  const [busy, setBusy] = useState(false)
  const [testTo, setTestTo] = useState('')
  const [msg, setMsg] = useState(null)

  const load = () =>
    api('/admin/settings/email', { auth }).then((d) => {
      setSaved(d)
      setForm({
        enabled: d.enabled,
        host: d.host || '',
        port: d.port || 587,
        username: d.username || '',
        password: '',
        fromAddress: d.fromAddress || '',
        fromName: d.fromName || '',
        startTls: d.startTls,
      })
    }).catch((e) => setMsg({ ok: false, text: e.message }))

  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function save(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/settings/email', { method: 'PUT', auth, body: form })
      setMsg({ ok: true, text: 'Saved. Email will use these from the next send.' })
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
      const r = await api('/admin/settings/email/test', { method: 'POST', auth, body: { to: testTo.trim() } })
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
      <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant/40">
        <div className="flex items-start justify-between gap-4">
          <div>
            <span className="text-base font-semibold block">Send email through your SMTP server</span>
            <span className="text-sm text-on-surface-variant">
              Receipts, password resets and reports go out from your own mailbox.
            </span>
          </div>
          <Toggle checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        </div>
        {form.enabled && (
          <div className="mt-4 space-y-4">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div className="md:col-span-2">
                <label className={LABEL_CLS}>SMTP host</label>
                <input className={INPUT_CLS} value={form.host}
                  onChange={(e) => set({ host: e.target.value })} placeholder="smtp.gmail.com" />
              </div>
              <div>
                <label className={LABEL_CLS}>Port</label>
                <input type="number" min="1" max="65535" className={INPUT_CLS} value={form.port}
                  onChange={(e) => set({ port: Number(e.target.value) })} />
              </div>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Username</label>
                <input className={INPUT_CLS} value={form.username} autoComplete="off"
                  onChange={(e) => set({ username: e.target.value })} placeholder="often your email address" />
              </div>
              <div>
                <label className={LABEL_CLS}>Password</label>
                <input type="password" className={INPUT_CLS} value={form.password} autoComplete="new-password"
                  onChange={(e) => set({ password: e.target.value })}
                  placeholder={saved?.password || 'app password or SMTP key'} />
              </div>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>From address</label>
                <input className={INPUT_CLS} value={form.fromAddress}
                  onChange={(e) => set({ fromAddress: e.target.value })} placeholder="billing@yourdomain.co.ke" />
              </div>
              <div>
                <label className={LABEL_CLS}>From name</label>
                <input className={INPUT_CLS} value={form.fromName}
                  onChange={(e) => set({ fromName: e.target.value })} placeholder="Zidi" />
              </div>
            </div>
            <label className="flex items-start gap-3 cursor-pointer">
              <input type="checkbox" className="mt-1" checked={form.startTls}
                onChange={(e) => set({ startTls: e.target.checked })} />
              <span>
                <span className="text-sm font-medium block">Use STARTTLS (port 587)</span>
                <span className="text-xs text-on-surface-variant">
                  Leave on for most providers. Turn off only for implicit SSL, usually on port 465.
                </span>
              </span>
            </label>
          </div>
        )}
      </section>

      {msg && (
        <p className={`text-sm ${msg.ok ? 'text-secondary' : 'text-[#b91c1c]'}`}>{msg.text}</p>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <PrimaryButton disabled={busy}>{busy ? 'Saving…' : 'Save changes'}</PrimaryButton>
        {saved?.working && (
          <>
            <input className={`${INPUT_CLS} max-w-xs`} type="email" value={testTo}
              onChange={(e) => setTestTo(e.target.value)} placeholder="you@example.com" />
            <button type="button" disabled={busy || !testTo.trim()}
              className="px-4 py-2 rounded-lg border border-outline-variant text-sm font-medium disabled:opacity-40"
              onClick={sendTest}>Send test</button>
          </>
        )}
      </div>
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

  const flatItems = groups.flatMap((g) => g.items)

  return (
    <div>
      <PageHeader title="Settings" subtitle="How this system behaves, and who it behaves as." />

      {/* Narrow screens: a horizontal, always-visible tab bar so the chosen
          section's content shows immediately instead of below a long list. */}
      <div className="lg:hidden mb-4">
        <input
          className="w-full mb-2.5 bg-surface border border-outline-variant rounded-lg px-3 py-2 text-sm focus:border-primary focus:ring-2 focus:ring-primary/20 outline-none"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Find a setting…"
          aria-label="Find a setting"
        />
        <div className="flex gap-2 overflow-x-auto pb-1 -mx-1 px-1">
          {flatItems.map((i) => (
            <button
              key={i.key}
              onClick={() => setActive(i.key)}
              aria-current={current === i.key ? 'page' : undefined}
              className={`shrink-0 flex items-center gap-1.5 h-9 px-3 rounded-full text-sm font-medium whitespace-nowrap transition-colors cursor-pointer ${
                current === i.key
                  ? 'bg-primary text-on-primary'
                  : 'bg-surface-container-lowest border border-outline-variant text-on-surface-variant hover:border-outline'
              }`}
            >
              <Icon name={i.icon} className="text-[16px]!" /> {i.label}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-6 items-start">
        <aside className="hidden lg:block bg-surface-container-lowest rounded-lg border border-outline-variant p-3 lg:sticky lg:top-4">
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
              <WhatsappAssistantPanel auth={auth} />
            </>
          )}
          {current === 'email' && (
            <>
              <PageHeader title="Email (SMTP)" subtitle="Your mail server, for receipts, password resets and reports." />
              <EmailSection auth={auth} />
            </>
          )}
          {current === 'alerts' && (
            <>
              <PageHeader title="Alerts & briefing" subtitle="Router-down alerts, outage compensation, and the one message a day that tells you how the business is doing." />
              <AlertsSection auth={auth} />
            </>
          )}
          {current === 'credit' && (
            <>
              <PageHeader
                title="Pay later (Lipa Baadaye)"
                subtitle="A customer who has paid you several times can get online now and settle on their next purchase."
              />
              <CreditSection auth={auth} />
            </>
          )}
          {current === 'field' && (
            <>
              <PageHeader
                title="Field jobs"
                subtitle="Technicians run their jobs from WhatsApp, and work nobody has touched chases itself."
              />
              <FieldSection auth={auth} />
            </>
          )}
          {current === 'capacity' && (
            <>
              <PageHeader
                title="Capacity planning"
                subtitle="Backhaul takes weeks to order. This is the several weeks of warning."
              />
              <CapacitySection auth={auth} />
            </>
          )}
          {current === 'offpeak' && (
            <>
              <PageHeader
                title="Off-peak offers"
                subtitle="Your link is paid for all night. This sells the part of it nobody is using."
              />
              <OffPeakSection auth={auth} />
            </>
          )}
          {current === 'agentpay' && (
            <>
              <PageHeader
                title="Agent payouts"
                subtitle="Commission is already worked out from the vouchers your agents sold. This pays it."
              />
              <AgentPayoutSection auth={auth} />
            </>
          )}
          {current === 'paybill' && (
            <>
              <PageHeader
                title="Zero-touch PayBill"
                subtitle="A customer pays the paybill by hand and their pass issues itself — no STK prompt, no smartphone."
              />
              <PaybillSection auth={auth} />
            </>
          )}
          {current === 'loyalty' && (
            <>
              <PageHeader title="Loyalty & rewards" subtitle="Customers earn points as they spend and redeem them for free time." />
              <LoyaltySection auth={auth} />
            </>
          )}
          {current === 'security' && (
            <>
              <PageHeader title="Password & security" subtitle="Your two-factor and biometric sign-in, and the security policy for everyone." />
              <PersonalSecuritySection auth={auth} me={me} />
              <SecuritySection auth={auth} />
            </>
          )}
          {current === 'developer' && (
            <>
              <PageHeader title="API tokens" subtitle="Personal access tokens for the REST API." />
              <DeveloperSection auth={auth} />
            </>
          )}
          {current === 'ai' && (
            <>
              <PageHeader title="AI assistant" subtitle="Ask questions about your business in plain language, powered by Groq." />
              <AiSection auth={auth} />
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
