import { useEffect, useRef, useState } from 'react'
import { api } from '../../api.js'
import { Icon, Skeleton, PageHeader, PrimaryButton, Toast, Toggle, INPUT_CLS, LABEL_CLS } from '../../components/ui.jsx'

const TEMPLATE_LABELS = {
  VOUCHER_ISSUED: ['Voucher issued', 'Sent after a customer pays for a WiFi pass.'],
  TRIAL_ISSUED: ['Free trial issued', 'Sent when someone claims the free trial.'],
  SUBSCRIPTION_PAID: ['Subscription paid', 'Sent when a home customer pays.'],
  EXPIRY_REMINDER: ['Expiry reminder', 'Sent 3 days before a subscription lapses.'],
  SUBSCRIPTION_SUSPENDED: ['Subscription suspended', 'Sent when a lapsed account is cut off.'],
  SUBSCRIPTION_EXTENDED: ['Subscription extended', 'Sent after a goodwill extension.'],
}

// Only the placeholders that actually get filled in for each message —
// {code} makes no sense on a subscription receipt, so it isn't offered there.
const TEMPLATE_PLACEHOLDERS = {
  VOUCHER_ISSUED: ['{business}', '{code}'],
  TRIAL_ISSUED: ['{business}', '{code}', '{minutes}'],
  SUBSCRIPTION_PAID: ['{business}', '{date}'],
  EXPIRY_REMINDER: ['{business}', '{date}', '{amount}', '{payUrl}'],
  SUBSCRIPTION_SUSPENDED: ['{business}', '{amount}', '{payUrl}'],
  SUBSCRIPTION_EXTENDED: ['{business}', '{date}'],
}

// Stand-in values so the preview reads like a real message.
const SAMPLE_VALUES = {
  '{business}': 'SPA WiFi',
  '{code}': '8F3K2Q',
  '{minutes}': '30',
  '{date}': '12 Aug 2026',
  '{amount}': '1,500',
  '{payUrl}': 'pay.spa.co.ke/9x2',
}

const renderPreview = (body) =>
  Object.entries(SAMPLE_VALUES).reduce((s, [token, val]) => s.split(token).join(val), body || '')

export default function Branding({ auth }) {
  const [tab, setTab] = useState('portal')
  const [form, setForm] = useState(null)
  const [logoUrl, setLogoUrl] = useState(null)
  const [templates, setTemplates] = useState(null)
  const [msg, setMsg] = useState(null)
  const [busy, setBusy] = useState(false)
  const logoRef = useRef(null)

  useEffect(() => {
    api('/admin/portal-settings', { auth }).then((s) => {
      setForm({
        businessName: s.businessName || '',
        headline: s.headline || '',
        subheadline: s.subheadline || '',
        backgroundColor: s.backgroundColor || '#000000',
        accentColor: s.accentColor || '#FDBF2D',
        supportPhone: s.supportPhone || '',
        termsText: s.termsText || '',
        trialEnabled: !!s.trialEnabled,
        trialMinutes: s.trialMinutes || 15,
      })
      setLogoUrl(s.logoFilename ? `/api/uploads/${s.logoFilename}` : null)
    }).catch(() => {})
    api('/admin/templates', { auth }).then(setTemplates).catch(() => setTemplates([]))
  }, [auth])

  if (!form) return <Skeleton className="h-64" />

  const set = (key, value) => { setForm({ ...form, [key]: value }); setMsg(null) }

  async function save() {
    setBusy(true)
    setMsg(null)
    try {
      await api('/admin/portal-settings', { method: 'PUT', auth, body: { ...form, trialMinutes: Number(form.trialMinutes) || 15 } })
      setMsg({ ok: true, text: 'Saved!' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function uploadLogo(e) {
    const file = e.target.files?.[0]
    if (!file) return
    setBusy(true)
    setMsg(null)
    try {
      const body = new FormData()
      body.append('logo', file)
      const s = await api('/admin/portal-settings/logo', { method: 'POST', auth, body })
      setLogoUrl(s.logoFilename ? `/api/uploads/${s.logoFilename}` : null)
      setMsg({ ok: true, text: 'Logo uploaded.' })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
      if (logoRef.current) logoRef.current.value = ''
    }
  }

  async function saveTemplate(t) {
    setBusy(true)
    setMsg(null)
    try {
      await api(`/admin/templates/${t.templateKey}`, { method: 'PUT', auth, body: { body: t.body, enabled: t.enabled } })
      setMsg({ ok: true, text: `${TEMPLATE_LABELS[t.templateKey]?.[0] || t.templateKey} saved.` })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="max-w-4xl">
      <PageHeader title="Branding & Messages" subtitle="Your captive portal's identity and every automatic message." />

      <nav className="flex gap-2 mb-6 flex-wrap">
        {[['portal', 'Portal'], ['trial', 'Free Trial'], ['messages', 'Messages']].map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
            className={`px-4 py-2 rounded-full text-sm transition-colors cursor-pointer ${
              tab === key ? 'bg-primary-container text-on-primary-container font-semibold'
                : 'border border-outline-variant text-on-surface hover:bg-surface-container-high'
            }`}>
            {label}
          </button>
        ))}
      </nav>

      {/* Confirmation is a toast rather than a line of text further down the
          page, which people were missing and then saving a second time. */}
      <Toast toast={msg} onDone={() => setMsg(null)} />

      {tab === 'portal' && (
        <div className="flex flex-col gap-6">
          <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant border-l-2 border-l-primary">
            <h3 className="text-lg font-semibold text-on-surface mb-4">Identity</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Business Name</label>
                <input className={INPUT_CLS} value={form.businessName} onChange={(e) => set('businessName', e.target.value)} />
                <p className="text-xs text-on-surface-variant mt-1">Used on the portal, printed vouchers and every SMS.</p>
              </div>
              <div>
                <label className={LABEL_CLS}>Support Phone</label>
                <input className={INPUT_CLS} value={form.supportPhone} onChange={(e) => set('supportPhone', e.target.value)} />
              </div>
              <div className="md:col-span-2">
                <label className={LABEL_CLS}>Headline</label>
                <input className={INPUT_CLS} value={form.headline} onChange={(e) => set('headline', e.target.value)} />
              </div>
              <div className="md:col-span-2">
                <label className={LABEL_CLS}>Sub-headline</label>
                <input className={INPUT_CLS} value={form.subheadline} onChange={(e) => set('subheadline', e.target.value)} />
              </div>
            </div>
          </section>

          <section className="bg-surface-container-lowest rounded-lg p-4 ">
            <h3 className="text-lg font-semibold text-on-surface mb-4">Logo &amp; Colours</h3>
            <div className="flex flex-wrap items-end gap-6">
              <div>
                <label className={LABEL_CLS}>Logo</label>
                <div className="flex items-center gap-4">
                  <div className="w-20 h-20 rounded-xl bg-surface-container flex items-center justify-center overflow-hidden border border-outline-variant">
                    {logoUrl
                      ? <img src={logoUrl} alt="Portal logo" className="w-full h-full object-contain p-2" />
                      : <Icon name="image" className="text-outline text-[28px]!" />}
                  </div>
                  <label className="px-4 py-3 rounded-lg border border-primary text-primary text-sm font-semibold hover:bg-primary/5 transition-colors cursor-pointer">
                    Upload logo
                    <input ref={logoRef} type="file" accept="image/png,image/jpeg,image/webp" className="hidden" onChange={uploadLogo} />
                  </label>
                </div>
              </div>
              <div>
                <label className={LABEL_CLS}>Background</label>
                <div className="flex items-center gap-2">
                  <input type="color" value={form.backgroundColor} onChange={(e) => set('backgroundColor', e.target.value)}
                    className="w-12 h-12 rounded-lg border border-outline-variant bg-surface cursor-pointer" />
                  <input className={`${INPUT_CLS} w-32 font-mono`} value={form.backgroundColor} onChange={(e) => set('backgroundColor', e.target.value)} />
                </div>
              </div>
              <div>
                <label className={LABEL_CLS}>Accent</label>
                <div className="flex items-center gap-2">
                  <input type="color" value={form.accentColor} onChange={(e) => set('accentColor', e.target.value)}
                    className="w-12 h-12 rounded-lg border border-outline-variant bg-surface cursor-pointer" />
                  <input className={`${INPUT_CLS} w-32 font-mono`} value={form.accentColor} onChange={(e) => set('accentColor', e.target.value)} />
                </div>
              </div>
            </div>
            <div
              className="mt-6 rounded-lg p-4 flex items-center gap-4"
              style={{ backgroundColor: form.backgroundColor }}
            >
              <div className="w-12 h-12 rounded-full flex items-center justify-center" style={{ backgroundColor: `${form.accentColor}22` }}>
                <Icon name="wifi" filled style={{ color: form.accentColor }} />
              </div>
              <div className="min-w-0">
                <p className="text-lg font-bold truncate" style={{ color: form.accentColor }}>{form.businessName || 'Your business'}</p>
                <p className="text-sm truncate" style={{ color: '#ffffffcc' }}>{form.headline || 'Your headline'}</p>
              </div>
              <span className="ml-auto px-4 py-2 rounded-lg text-sm font-semibold shrink-0"
                style={{ backgroundColor: form.accentColor, color: form.backgroundColor }}>
                Buy
              </span>
            </div>
          </section>

          <section className="bg-surface-container-lowest rounded-lg p-4 ">
            <h3 className="text-lg font-semibold text-on-surface mb-4">Terms shown to customers</h3>
            <textarea className={`${INPUT_CLS} resize-none`} rows="4" value={form.termsText} onChange={(e) => set('termsText', e.target.value)} />
          </section>

          <div className="flex justify-end">
            <PrimaryButton onClick={save} disabled={busy}>
              <Icon name="save" className="text-[20px]!" /> {busy ? 'Saving…' : 'Save Branding'}
            </PrimaryButton>
          </div>
        </div>
      )}

      {tab === 'trial' && (
        <div className="flex flex-col gap-6">
          <section className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant border-l-2 border-l-secondary">
            <div className="flex items-start justify-between gap-4 mb-4">
              <div>
                <h3 className="text-lg font-semibold text-on-surface">Free Trial</h3>
                <p className="text-sm text-on-surface-variant">
                  Gives a first-time visitor a short free pass so they experience the speed before paying.
                  Each phone number can claim once.
                </p>
              </div>
              <Toggle checked={form.trialEnabled} onChange={() => set('trialEnabled', !form.trialEnabled)} />
            </div>
            <div className="w-40">
              <label className={LABEL_CLS}>Trial Minutes</label>
              <input className={INPUT_CLS} type="number" min="1" max="1440" value={form.trialMinutes}
                onChange={(e) => set('trialMinutes', e.target.value)} />
            </div>
          </section>
          <div className="flex justify-end">
            <PrimaryButton onClick={save} disabled={busy}>
              <Icon name="save" className="text-[20px]!" /> {busy ? 'Saving…' : 'Save Trial Settings'}
            </PrimaryButton>
          </div>
        </div>
      )}

      {tab === 'messages' && (templates === null ? <Skeleton className="h-64" /> : (
        <div className="flex flex-col gap-4">
          <div className="bg-surface-container-lowest rounded-lg p-4 border border-outline-variant flex items-start gap-3">
            <Icon name="info" className="text-primary text-[20px]! mt-0.5" />
            <p className="text-sm text-on-surface-variant">
              Tap a placeholder to drop it into a message — it's filled in with the real value when the
              message is sent. Each message shows a live preview using example values.
            </p>
          </div>
          {templates.map((t, idx) => {
            const [label, hint] = TEMPLATE_LABELS[t.templateKey] || [t.templateKey, '']
            const tokens = TEMPLATE_PLACEHOLDERS[t.templateKey] || []
            const insert = (token) => {
              const next = [...templates]
              const body = t.body || ''
              const sep = body.length && !body.endsWith(' ') ? ' ' : ''
              next[idx] = { ...t, body: body + sep + token }
              setTemplates(next)
              setMsg(null)
            }
            const preview = renderPreview(t.body)
            return (
              <section key={t.templateKey} className="bg-surface-container-lowest rounded-lg p-4 ">
                <div className="flex items-start justify-between gap-4 mb-3">
                  <div>
                    <h3 className="text-base font-semibold text-on-surface">{label}</h3>
                    <p className="text-xs text-on-surface-variant">{hint}</p>
                  </div>
                  <Toggle checked={t.enabled} onChange={() => {
                    const next = [...templates]
                    next[idx] = { ...t, enabled: !t.enabled }
                    setTemplates(next)
                  }} />
                </div>
                <textarea
                  className={`${INPUT_CLS} resize-none`}
                  rows="2"
                  value={t.body}
                  onChange={(e) => {
                    const next = [...templates]
                    next[idx] = { ...t, body: e.target.value }
                    setTemplates(next)
                  }}
                />
                {tokens.length > 0 && (
                  <div className="flex flex-wrap items-center gap-1.5 mt-2">
                    {tokens.map((p) => (
                      <button key={p} type="button" onClick={() => insert(p)}
                        className="text-xs font-mono bg-surface-container hover:bg-primary/10 border border-outline-variant/60 px-2 py-1 rounded cursor-pointer transition-colors">
                        {p}
                      </button>
                    ))}
                  </div>
                )}
                <div className="mt-3 rounded-lg bg-surface-container/60 border border-outline-variant/40 p-3">
                  <span className="text-[11px] uppercase tracking-wide text-on-surface-variant">Preview</span>
                  <p className="text-sm text-on-surface mt-1 whitespace-pre-wrap">
                    {preview || <span className="text-on-surface-variant italic">Type a message above…</span>}
                  </p>
                </div>
                <div className="flex items-center justify-between mt-2">
                  <span className="text-xs text-on-surface-variant">
                    {t.body.length} characters {t.body.length > 160 ? '· 2 SMS per send' : ''}
                  </span>
                  <button onClick={() => saveTemplate(templates[idx])} disabled={busy}
                    className="px-4 py-2 rounded-lg bg-primary text-on-primary text-sm font-semibold disabled:opacity-60 cursor-pointer">
                    Save
                  </button>
                </div>
              </section>
            )
          })}
        </div>
      ))}
    </div>
  )
}
