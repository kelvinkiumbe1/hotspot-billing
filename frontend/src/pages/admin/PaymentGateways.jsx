import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import {
  Icon, Skeleton, PageHeader, PrimaryButton, INPUT_CLS, LABEL_CLS,
} from '../../components/ui.jsx'

/**
 * Only gateways that genuinely collect money appear here. Showing one that
 * cannot take a payment is the failure where the admin looks healthy and
 * nothing arrives.
 */
const GATEWAYS = [
  {
    kind: 'MPESA_API',
    name: 'M-Pesa Paybill / Till',
    provider: 'Safaricom Daraja · Kenya',
    badge: 'API KEYS',
    chips: ['STK push', 'Paybill', 'Automatic'],
    settlement: 'Instant, confirmed automatically',
    icon: 'smartphone',
    blurb: 'The customer gets a prompt on their phone and is connected the moment they pay. Needs a Daraja app from Safaricom.',
  },
  {
    kind: 'MPESA_PAYBILL_MANUAL',
    name: 'Paybill — no API keys',
    provider: 'M-Pesa · Kenya',
    badge: 'MANUAL',
    chips: ['Paybill', 'Reconciled by hand'],
    settlement: 'Instant to you, matched by a person',
    icon: 'receipt_long',
    blurb: 'Show customers your paybill and account number. Someone has to match each payment to a customer.',
  },
  {
    kind: 'MPESA_TILL_MANUAL',
    name: 'Buy Goods till — no API keys',
    provider: 'M-Pesa · Kenya',
    badge: 'MANUAL',
    chips: ['Till', 'Reconciled by hand'],
    settlement: 'Instant to you, matched by a person',
    icon: 'storefront',
    blurb: 'For operators with a Buy Goods till rather than a paybill.',
  },
  {
    kind: 'BANK_TRANSFER',
    name: 'Bank transfer',
    provider: 'Any bank',
    badge: 'MANUAL',
    chips: ['Bank', 'Reconciled by hand'],
    settlement: 'Whenever the bank clears it',
    icon: 'account_balance',
    blurb: 'Usually for monthly PPPoE customers rather than hotspot buyers.',
  },
  {
    kind: 'PAYSTACK',
    name: 'Paystack',
    provider: 'Nigeria · Ghana · Kenya · South Africa',
    badge: 'API KEYS',
    chips: ['Cards', 'Mobile money', 'Bank', 'Automatic'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'credit_card',
    blurb: 'The customer opens a secure page and pays by card, bank or mobile money. Needs a Paystack account.',
    webhook: '/api/payments/paystack/webhook',
    keyHint: 'sk_live_… from Settings → API Keys & Webhooks',
    secretless: true,
  },
  {
    kind: 'FLUTTERWAVE',
    name: 'Flutterwave',
    provider: 'Most of Africa',
    badge: 'API KEYS',
    chips: ['Cards', 'Mobile money', 'Automatic'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'public',
    blurb: 'Widest country coverage on the continent. The customer pays on a hosted page.',
    webhook: '/api/payments/flutterwave/webhook',
    keyHint: 'FLWSECK-… from Settings → API',
    secretHint: 'The secret hash you set on their webhook page — you choose it',
  },
  {
    kind: 'STRIPE',
    name: 'Stripe',
    provider: 'Worldwide',
    badge: 'API KEYS',
    chips: ['Cards', 'Automatic'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'language',
    blurb: 'For operators billing outside mobile-money markets. Cards only, but almost everywhere.',
    webhook: '/api/payments/stripe/webhook',
    keyHint: 'sk_live_… from Developers → API keys',
    secretHint: 'whsec_… shown when you add the endpoint below',
  },
]

const CARD_KINDS = ['PAYSTACK', 'FLUTTERWAVE', 'STRIPE']

/** Copies a webhook URL and says so, because a silent copy reads as a dead button. */
function CopyUrl({ url }) {
  const [copied, setCopied] = useState(false)
  return (
    <button type="button"
      onClick={() => {
        navigator.clipboard?.writeText(url)
        setCopied(true)
        setTimeout(() => setCopied(false), 1600)
      }}
      className="flex items-center gap-2 w-full text-left px-3 py-2 rounded-lg bg-surface-container-high text-xs font-mono break-all cursor-pointer hover:bg-surface-container-highest transition-colors">
      <Icon name={copied ? 'check' : 'content_copy'} className="text-[14px]! shrink-0" />
      <span className="flex-1">{url}</span>
      {copied && <span className="font-sans font-semibold text-primary shrink-0">Copied</span>}
    </button>
  )
}

function ConfigureForm({ auth, gateway, saved, webhookBase, onCancel, onSaved }) {
  const isApi = gateway.kind === 'MPESA_API'
  const isCard = CARD_KINDS.includes(gateway.kind)
  const [form, setForm] = useState({
    environment: saved?.environment || 'SANDBOX',
    secretKey: '',
    publicKey: saved?.publicKey || '',
    webhookSecret: '',
    consumerKey: '',
    consumerSecret: '',
    shortCode: saved?.shortCode || '',
    passkey: '',
    initiatorName: saved?.initiatorName || '',
    securityCredential: '',
    paybillNumber: saved?.paybillNumber || '',
    tillNumber: saved?.tillNumber || '',
    bankName: saved?.bankName || '',
    accountNumber: saved?.accountNumber || '',
    accountName: saved?.accountName || '',
    instructions: saved?.instructions || '',
  })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      await api(`/admin/settings/payments/${gateway.kind}`, { method: 'PUT', auth, body: form })
      onSaved()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  async function test() {
    setBusy(true)
    setMsg(null)
    try {
      const r = await api('/admin/settings/payments/MPESA_API/test', { method: 'POST', auth })
      setMsg({ ok: true, text: r.warning ? `${r.message}. ${r.warning}` : r.message })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="mt-4 pt-4 border-t border-outline-variant space-y-4">
      {isApi ? (
        <>
          <div>
            <label className={LABEL_CLS}>Environment</label>
            <div className="flex gap-2">
              {['SANDBOX', 'PRODUCTION'].map((env) => (
                <button key={env} type="button" onClick={() => set({ environment: env })}
                  aria-pressed={form.environment === env}
                  className={`px-4 py-2 rounded-full text-sm cursor-pointer transition-colors ${
                    form.environment === env
                      ? 'bg-primary-container text-on-primary-container font-semibold'
                      : 'border border-outline-variant hover:bg-surface-container-high'
                  }`}>
                  {env === 'SANDBOX' ? 'Sandbox (testing)' : 'Production (real money)'}
                </button>
              ))}
            </div>
            {form.environment === 'SANDBOX' && (
              <p className="text-xs text-[#b45309] mt-1.5">
                Sandbox behaves exactly like success and collects nothing. Switch to Production before
                telling customers they can pay.
              </p>
            )}
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Shortcode</label>
              <input className={INPUT_CLS} value={form.shortCode}
                onChange={(e) => set({ shortCode: e.target.value })} placeholder="e.g. 174379" />
            </div>
            <div>
              <label className={LABEL_CLS}>Consumer key</label>
              <input className={INPUT_CLS} value={form.consumerKey}
                onChange={(e) => set({ consumerKey: e.target.value })}
                placeholder={saved?.consumerKey || 'from your Daraja app'} />
            </div>
            <div>
              <label className={LABEL_CLS}>Consumer secret</label>
              <input className={INPUT_CLS} type="password" value={form.consumerSecret}
                onChange={(e) => set({ consumerSecret: e.target.value })}
                placeholder={saved?.consumerSecret || 'from your Daraja app'} />
            </div>
            <div>
              <label className={LABEL_CLS}>Passkey</label>
              <input className={INPUT_CLS} type="password" value={form.passkey}
                onChange={(e) => set({ passkey: e.target.value })}
                placeholder={saved?.passkey || 'from your Daraja app'} />
            </div>
          </div>

          {/* Optional: only needed to verify customers' pasted M-Pesa codes
              (Transaction Status API). STK push works without these. */}
          <details className="rounded-lg border border-outline-variant p-3">
            <summary className="text-sm font-medium cursor-pointer text-on-surface">
              Verify M-Pesa codes (optional)
            </summary>
            <p className="text-xs text-on-surface-variant mt-1 mb-3">
              Lets customers who paid by Paybill/Till claim access by entering their M-Pesa code.
              Needs your Daraja initiator name and Security Credential. Leave blank if unused.
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className={LABEL_CLS}>Initiator name</label>
                <input className={INPUT_CLS} value={form.initiatorName}
                  onChange={(e) => set({ initiatorName: e.target.value })}
                  placeholder={saved?.initiatorName || 'e.g. apiuser'} />
              </div>
              <div>
                <label className={LABEL_CLS}>Security credential</label>
                <input className={INPUT_CLS} type="password" value={form.securityCredential}
                  onChange={(e) => set({ securityCredential: e.target.value })}
                  placeholder={saved?.securityCredential || 'encrypted initiator password'} />
              </div>
            </div>
          </details>

          {saved?.consumerKey && (
            <p className="text-xs text-on-surface-variant">
              Secrets are never shown again once saved. Leave a field blank to keep what is stored.
            </p>
          )}
        </>
      ) : isCard ? (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Secret key</label>
              <input className={INPUT_CLS} type="password" value={form.secretKey}
                onChange={(e) => set({ secretKey: e.target.value })}
                placeholder={saved?.secretKey || gateway.keyHint} />
              <p className="text-xs text-on-surface-variant mt-1">{gateway.keyHint}</p>
            </div>
            <div>
              <label className={LABEL_CLS}>Public key <span className="font-normal opacity-70">(optional)</span></label>
              <input className={INPUT_CLS} value={form.publicKey}
                onChange={(e) => set({ publicKey: e.target.value })} placeholder="pk_live_…" />
            </div>
            {!gateway.secretless && (
              <div className="md:col-span-2">
                <label className={LABEL_CLS}>Webhook secret</label>
                <input className={INPUT_CLS} type="password" value={form.webhookSecret}
                  onChange={(e) => set({ webhookSecret: e.target.value })}
                  placeholder={saved?.webhookSecret || gateway.secretHint} />
                <p className="text-xs text-on-surface-variant mt-1">{gateway.secretHint}</p>
              </div>
            )}
          </div>

          {/* Without this pasted into their dashboard, a customer can pay in full
              and never be connected — the payment succeeds and we never hear. */}
          <div className="rounded-lg border border-outline-variant p-3">
            <p className="text-sm font-medium">Add this webhook in your {gateway.name} dashboard</p>
            <p className="text-xs text-on-surface-variant mt-1 mb-2">
              This is how we find out a payment succeeded. Until it is set, customers will pay and stay offline.
            </p>
            {webhookBase
              ? <CopyUrl url={webhookBase + gateway.webhook} />
              : (
                <p className="text-xs text-[#b45309]">
                  This server has no public address configured yet, so the URL can't be shown.
                  Set MPESA_CALLBACK_URL to your public domain and it will appear here.
                </p>
              )}
          </div>

          {gateway.secretless && (
            <p className="text-xs text-on-surface-variant">
              Paystack signs its webhooks with the secret key above, so there is nothing else to enter.
            </p>
          )}
          {saved?.secretKey && (
            <p className="text-xs text-on-surface-variant">
              Keys are never shown again once saved. Leave a field blank to keep what is stored.
            </p>
          )}
          <p className="text-xs text-[#b45309]">
            Charges through this gateway haven't been tried against a live account yet. Take one small
            real payment and confirm the customer gets online before pointing customers at it.
          </p>
        </>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {gateway.kind === 'MPESA_PAYBILL_MANUAL' && (
            <>
              <div>
                <label className={LABEL_CLS}>Paybill number</label>
                <input className={INPUT_CLS} required value={form.paybillNumber}
                  onChange={(e) => set({ paybillNumber: e.target.value })} placeholder="e.g. 522522" />
              </div>
              <div>
                <label className={LABEL_CLS}>Account name shown to customers</label>
                <input className={INPUT_CLS} value={form.accountName}
                  onChange={(e) => set({ accountName: e.target.value })}
                  placeholder="e.g. their phone number" />
              </div>
            </>
          )}
          {gateway.kind === 'MPESA_TILL_MANUAL' && (
            <div>
              <label className={LABEL_CLS}>Till number</label>
              <input className={INPUT_CLS} required value={form.tillNumber}
                onChange={(e) => set({ tillNumber: e.target.value })} placeholder="e.g. 8461234" />
            </div>
          )}
          {gateway.kind === 'BANK_TRANSFER' && (
            <>
              <div>
                <label className={LABEL_CLS}>Bank</label>
                <input className={INPUT_CLS} required value={form.bankName}
                  onChange={(e) => set({ bankName: e.target.value })} placeholder="e.g. Equity Bank" />
              </div>
              <div>
                <label className={LABEL_CLS}>Account number</label>
                <input className={INPUT_CLS} required value={form.accountNumber}
                  onChange={(e) => set({ accountNumber: e.target.value })} />
              </div>
              <div>
                <label className={LABEL_CLS}>Account name</label>
                <input className={INPUT_CLS} value={form.accountName}
                  onChange={(e) => set({ accountName: e.target.value })} />
              </div>
            </>
          )}
        </div>
      )}

      <div>
        <label className={LABEL_CLS}>What customers are told</label>
        <textarea className={`${INPUT_CLS} min-h-[70px]`} value={form.instructions}
          onChange={(e) => set({ instructions: e.target.value })}
          placeholder="Shown on the portal when they choose to pay this way." />
      </div>

      {msg && <p className={`text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      <div className="flex flex-wrap gap-2">
        <PrimaryButton type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</PrimaryButton>
        {isApi && saved?.configured && (
          <button type="button" onClick={test} disabled={busy}
            className="px-4 py-2 rounded-lg border border-primary text-primary text-sm font-semibold cursor-pointer hover:bg-primary/5 disabled:opacity-50">
            Test credentials
          </button>
        )}
        <button type="button" onClick={onCancel}
          className="px-4 py-2 rounded-lg border border-outline-variant text-sm cursor-pointer hover:bg-surface-container-high">
          Cancel
        </button>
      </div>
    </form>
  )
}

export default function PaymentGatewaysPage({ auth }) {
  const [data, setData] = useState(null)
  const [openKind, setOpenKind] = useState(null)
  const [msg, setMsg] = useState(null)

  const load = () => api('/admin/settings/payments', { auth }).then(setData).catch(() => setData(null))
  useEffect(() => { load() }, [auth]) // eslint-disable-line react-hooks/exhaustive-deps

  async function activate(kind) {
    try {
      const r = await api(`/admin/settings/payments/${kind}/activate`, { method: 'POST', auth })
      setMsg(r.live
        ? { ok: true, text: 'Switched. Customers now pay through this gateway.' }
        : { ok: false, text: 'Switched, but this gateway is on sandbox — it will not collect real money.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (!data) return <Skeleton className="h-64" />

  const saved = Object.fromEntries(data.gateways.map((g) => [g.kind, g]))
  const activeKind = data.activeKind

  return (
    <div>
      <PageHeader
        title="Payments"
        subtitle="Pick one gateway so customers can pay you. Only one is active at a time — switching keeps saved credentials."
      />

      <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-4">
        {data.available} gateways available · {data.connected > 0 ? `${data.connected} set up` : 'none connected'}
        {activeKind && ` · ${GATEWAYS.find((g) => g.kind === activeKind)?.name} is live`}
      </p>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {!activeKind && (
        <div className="mb-6 p-4 rounded-lg bg-[#f59e0b]/10 border border-[#f59e0b]/30">
          <p className="text-sm text-[#b45309]">
            No gateway is active, so nobody can pay you yet. Set one up below and switch it on.
          </p>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {GATEWAYS.map((g) => {
          const s = saved[g.kind] || {}
          const isActive = s.active
          const open = openKind === g.kind
          return (
            <div key={g.kind}
              className={`bg-surface-container-lowest rounded-lg p-4 border transition-colors ${
                isActive ? 'border-primary' : 'border-outline-variant/40'
              } ${open ? 'lg:col-span-2' : ''}`}>
              <div className="flex items-start gap-3">
                <span className="w-11 h-11 rounded-lg bg-surface-container-high flex items-center justify-center shrink-0">
                  <Icon name={g.icon} className="text-[22px]!" />
                </span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-bold">{g.name}</h3>
                    <span className="px-1.5 py-0.5 rounded bg-surface-container-high text-[10px] font-bold tracking-wider">
                      {g.badge}
                    </span>
                    {isActive && (
                      <span className="px-2 py-0.5 rounded-full bg-primary text-on-primary text-[10px] font-bold tracking-wider">
                        {s.live ? 'ACTIVE' : CARD_KINDS.includes(g.kind) ? 'ACTIVE · TEST KEYS' : 'ACTIVE · SANDBOX'}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-on-surface-variant">{g.provider}</p>
                </div>
              </div>

              <p className="text-sm text-on-surface-variant mt-3">{g.blurb}</p>

              <div className="flex flex-wrap gap-1.5 mt-3">
                {g.chips.map((c) => (
                  <span key={c} className="px-2 py-0.5 rounded-md bg-surface-container-high text-xs">{c}</span>
                ))}
              </div>

              <div className="flex items-center justify-between gap-3 mt-4 pt-3 border-t border-outline-variant/50">
                <span className="text-xs text-on-surface-variant">{g.settlement}</span>
                <div className="flex gap-2">
                  {s.configured && !isActive && (
                    <button onClick={() => activate(g.kind)}
                      className="px-3 py-1.5 rounded-md border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
                      Make active
                    </button>
                  )}
                  <button onClick={() => setOpenKind(open ? null : g.kind)}
                    className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high flex items-center gap-1">
                    {s.configured ? 'Edit' : 'Configure'}
                    <Icon name={open ? 'expand_less' : 'chevron_right'} className="text-[14px]!" />
                  </button>
                </div>
              </div>

              {open && (
                <ConfigureForm
                  auth={auth}
                  gateway={g}
                  saved={s}
                  webhookBase={data.webhookBase}
                  onCancel={() => setOpenKind(null)}
                  onSaved={() => {
                    setOpenKind(null)
                    setMsg({ ok: true, text: `${g.name} saved.` })
                    load()
                  }}
                />
              )}
            </div>
          )
        })}
      </div>

      <p className="mt-6 text-xs text-on-surface-variant max-w-3xl">
        Each gateway needs a merchant account with that provider. The three card processors are built and their
        webhooks are verified against forgery, but no charge has yet been made through a live merchant account —
        take one small real payment yourself before pointing customers at one.
      </p>
    </div>
  )
}
