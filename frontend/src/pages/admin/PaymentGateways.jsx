import { useEffect, useState } from 'react'
import { api } from '../../api.js'
import { GATEWAY_LOGOS } from '../../assets/gateways/index.jsx'
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
    kind: 'VODACOM_MPESA',
    name: 'M-Pesa (Vodacom)',
    provider: 'Tanzania · Mozambique · DR Congo',
    badge: 'API KEYS',
    chips: ['Prompt on phone', 'No web page', 'Automatic'],
    settlement: 'Instant, confirmed by asking Vodacom',
    icon: 'smartphone',
    blurb: 'The same M-Pesa name as Kenya, run by Vodacom on a different platform with '
      + 'its own credentials — nothing from a Safaricom Daraja app works here. It is the '
      + 'largest wallet in Tanzania and Mozambique, and reaching it directly saves the '
      + 'aggregator margin an intermediary charges on top of the wallet’s own fee.',
  },
  {
    kind: 'MTN_MOMO',
    name: 'MTN Mobile Money',
    provider: 'Ghana · Uganda · Rwanda · Zambia · Cameroon · Côte d’Ivoire · Benin · Eswatini · South Sudan',
    badge: 'API KEYS',
    chips: ['Prompt on phone', 'No web page', 'Automatic'],
    settlement: 'Instant, confirmed by asking MTN',
    icon: 'smartphone',
    blurb: 'Works like M-Pesa: the customer gets a prompt and enters their PIN, with no checkout page. One integration covers every MTN market.',
    webhook: '/api/payments/mtn-momo/webhook',
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
    kind: 'AIRTEL_MONEY',
    name: 'Airtel Money',
    provider: 'Kenya · Tanzania · Uganda · Rwanda · Zambia · Malawi · DR Congo · Nigeria · Niger · Chad · Gabon · Congo-Brazzaville · Madagascar',
    badge: 'API KEYS',
    chips: ['Prompt on phone', 'No web page', 'Automatic'],
    settlement: 'Instant, confirmed by asking Airtel',
    icon: 'smartphone',
    blurb: 'A USSD push, so the customer gets a prompt and enters their PIN. Reaches Airtel customers only — where another wallet also has a big share, an aggregator covers more people.',
    webhook: '/api/payments/airtel/webhook',
  },
  {
    kind: 'ORANGE_MONEY',
    name: 'Orange Money',
    provider: 'Senegal · Côte d’Ivoire · Mali · Burkina Faso · Guinea · Sierra Leone · Niger · Madagascar · Botswana · Cameroon · DR Congo',
    badge: 'API KEYS',
    chips: ['Wallet', 'Hosted page', 'Automatic'],
    settlement: 'Instant, confirmed by asking Orange',
    icon: 'account_balance_wallet',
    blurb: 'The second-biggest wallet network on the continent, and dominant in Senegal, '
      + 'Mali and Burkina Faso. A hosted page rather than a prompt on the phone — Orange’s '
      + 'direct-debit APIs are granted merchant by merchant, so this uses the one anybody can sign up for.',
    webhook: '/api/payments/orange-money/webhook',
  },
  {
    kind: 'WAVE',
    name: 'Wave',
    provider: 'Senegal · Côte d’Ivoire · Mali · Burkina Faso · Gambia',
    badge: 'API KEYS',
    chips: ['Wallet', 'Cheapest fees', 'Automatic'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'account_balance_wallet',
    blurb: 'Arrived charging a flat 1% and took a very large share of Senegalese mobile money. '
      + 'Worth running alongside Orange Money rather than instead of it.',
    webhook: '/api/payments/wave/webhook',
    keyHint: 'wave_sn_prod_… from Business → Developer → API keys',
    secretHint: 'The webhook secret shown when you add the endpoint below',
  },
  {
    kind: 'PAYNOW',
    name: 'Paynow',
    provider: 'Zimbabwe',
    badge: 'API KEYS',
    chips: ['Prompt on phone', 'EcoCash', 'Automatic'],
    settlement: 'Instant, confirmed by a hashed reply',
    icon: 'smartphone',
    blurb: 'Reaches EcoCash, OneMoney, InnBucks and Zimswitch. Express Checkout prompts the handset directly, so it feels like M-Pesa rather than a checkout page.',
    webhook: '/api/payments/paynow/webhook',
  },
  {
    kind: 'CHARGILY',
    name: 'Chargily',
    provider: 'Algeria',
    badge: 'API KEYS',
    chips: ['EDAHABIA', 'CIB', 'Automatic'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'credit_card',
    blurb: 'How Algerians actually pay — the EDAHABIA card from Algérie Poste, which tens of '
      + 'millions hold, and CIB bank cards. Nothing international collects dinars, so this is '
      + 'the way in. The customer picks which card on Chargily’s own page.',
    webhook: '/api/payments/chargily/webhook',
    keyHint: 'live_sk_… from your Chargily dashboard → Developers',
    secretless: true,
  },
  {
    kind: 'MULTICAIXA',
    name: 'Multicaixa Express',
    provider: 'Angola',
    badge: 'API KEYS',
    chips: ['Express wallet', 'QR code', 'Automatic'],
    settlement: 'Instant, confirmed by asking EMIS',
    icon: 'account_balance_wallet',
    blurb: 'The interbank network every Angolan card and the Express wallet sit on, through '
      + 'EMIS’s gateway. The customer opens EMIS’s own page and confirms in the Multicaixa '
      + 'Express app on their phone. Cards are off — enabling them needs a separate '
      + 'agreement with EMIS.',
    webhook: '/api/payments/multicaixa/webhook',
  },
  {
    kind: 'CMI',
    name: 'CMI',
    provider: 'Morocco',
    badge: 'API KEYS',
    chips: ['Cards', '3-D Secure', 'Automatic'],
    settlement: 'Instant to us, paid out on the bank’s schedule',
    icon: 'credit_card',
    blurb: 'Centre Monétique Interbancaire clears very nearly every Moroccan card, and nothing '
      + 'else here collects dirhams. The customer pays on CMI’s own 3-D Secure page, so no card '
      + 'number touches this server. Needs a public address — CMI posts the result back and '
      + 'there is nothing to ask if it cannot reach you.',
  },
  {
    kind: 'WAAFIPAY',
    name: 'EVC Plus (WaafiPay)',
    provider: 'Somalia',
    badge: 'API KEYS',
    chips: ['Prompt on phone', 'No web page', 'Automatic'],
    settlement: 'Confirmed in the same call — there is no callback',
    icon: 'smartphone',
    blurb: 'Hormuud’s EVC Plus, and the only way to take a Somali payment — no aggregator '
      + 'reaches the country. The customer approves on their handset and the answer comes '
      + 'back straight away, so a payment is settled the moment it is made.',
  },
  {
    kind: 'KONNECT',
    name: 'Konnect',
    provider: 'Tunisia',
    badge: 'API KEYS',
    chips: ['Wallets', 'e-DINAR', 'Cards', 'Automatic'],
    settlement: 'Instant to us, confirmed by asking Konnect',
    icon: 'account_balance_wallet',
    blurb: 'The domestic gateway, and the only way to take a Tunisian payment — Stripe does '
      + 'not serve Tunisia and the pan-African aggregators do not collect dinars. Reaches the '
      + 'Konnect and Flouci wallets, e-DINAR and bank cards.',
    webhook: '/api/payments/konnect/webhook',
  },
  {
    kind: 'PAYMOB',
    name: 'Paymob',
    provider: 'Egypt',
    badge: 'API KEYS',
    chips: ['Vodafone Cash', 'InstaPay', 'Meeza', 'Cards'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'account_balance_wallet',
    blurb: 'The one integration that covers how Egyptians actually pay — the telco wallets, '
      + 'InstaPay, Meeza and ordinary cards — on a page Paymob hosts, so no card number '
      + 'touches this server.',
    webhook: '/api/payments/paymob/webhook',
  },
  {
    kind: 'CHAPA',
    name: 'Chapa',
    provider: 'Ethiopia',
    badge: 'API KEYS',
    chips: ['telebirr', 'CBE Birr', 'Cards', 'Automatic'],
    settlement: 'Instant to us, paid out on their schedule',
    icon: 'public',
    blurb: 'Reaches telebirr and the Ethiopian banks no pan-African gateway touches. The customer pays on a hosted page.',
    webhook: '/api/payments/chapa/webhook',
    keyHint: 'CHASECK_… from your Chapa dashboard',
    secretHint: 'The webhook secret you set on Chapa’s webhook page',
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

// Chargily belongs here rather than in TELCO_FIELDS: one key, no separate
// webhook secret (the same key verifies the signature), and the key's own
// prefix decides test against live -- exactly Paystack's shape.
const CARD_KINDS = ['PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'CHAPA', 'WAVE', 'CHARGILY']
/**
 * The credentials each direct-wallet rail needs, in the order to ask for them.
 *
 * A table rather than three near-identical blocks of JSX, and the reason is a
 * bug this had: only MTN had a branch, so opening Configure on Airtel Money
 * rendered a form with no fields in it at all. An operator could not enter
 * Airtel credentials however hard they tried, and nothing said why.
 *
 * The labels are per-rail on purpose. All three are an environment plus an id
 * and a secret, but they are not called the same things, and a MoMo label on an
 * Airtel form is how somebody pastes a subscription key into a client id.
 */
/**
 * Rails that pick sandbox against live by which address is called.
 *
 * Everything else in TELCO_FIELDS does. WaafiPay does not, so it is absent and
 * gets a sentence instead of a toggle.
 */
const HAS_ENVIRONMENT = ['MTN_MOMO', 'AIRTEL_MONEY', 'ORANGE_MONEY', 'VODACOM_MPESA',
  'PAYMOB', 'KONNECT']

const TELCO_FIELDS = {
  MTN_MOMO: [
    { key: 'secretKey', label: 'Subscription key', secret: true, wide: true,
      placeholder: 'Ocp-Apim-Subscription-Key, from your Collection subscription',
      hint: 'MoMo developer portal → your profile → the Collection product.' },
    { key: 'consumerKey', label: 'API user', placeholder: 'a UUID' },
    { key: 'consumerSecret', label: 'API key', secret: true,
      placeholder: 'generated for that API user' },
    { key: 'shortCode', label: 'Target environment', wide: true,
      placeholder: 'mtnghana, mtnuganda, … — leave blank in Ghana, Uganda, Rwanda, '
        + 'Zambia, Cameroon or Côte d’Ivoire',
      hint: 'MTN issues this per merchant. It is filled in automatically for the six '
        + 'markets above; anywhere else — Benin, Eswatini, South Sudan — MTN MoMo will '
        + 'not appear to customers until you paste yours here. It is on your MoMo '
        + 'developer profile beside the Collection product.' },
  ],
  AIRTEL_MONEY: [
    { key: 'consumerKey', label: 'Client ID', placeholder: 'from your Airtel developer app' },
    { key: 'consumerSecret', label: 'Client secret', secret: true,
      placeholder: 'beside the client ID' },
  ],
  MULTICAIXA: [
    { key: 'secretKey', label: 'Merchant frame token', secret: true, wide: true,
      placeholder: 'the token EMIS issued for your point of sale',
      hint: 'The only credential this rail needs. EMIS calls it the frame token, and it '
        + 'travels in the body of every request rather than a header.' },
  ],
  CMI: [
    { key: 'shortCode', label: 'Merchant ID (clientid)',
      placeholder: 'e.g. 600001234',
      hint: 'From your CMI merchant pack.' },
    { key: 'secretKey', label: 'Store key', secret: true, wide: true,
      placeholder: 'the store key CMI issued',
      hint: 'This signs what goes to CMI and checks what comes back — it is the whole of '
        + 'the security on this rail. Never put it anywhere a customer could see it.' },
  ],
  WAAFIPAY: [
    { key: 'shortCode', label: 'Merchant UID',
      placeholder: 'e.g. M0910291',
      hint: 'Issued by Hormuud with the two below.' },
    { key: 'consumerKey', label: 'API user ID',
      placeholder: 'e.g. 1000416' },
    { key: 'secretKey', label: 'API key', secret: true, wide: true,
      placeholder: 'e.g. API-675418888AHX',
      hint: 'All three travel with every request — there is no token to exchange them for.' },
  ],
  KONNECT: [
    { key: 'secretKey', label: 'API key', secret: true, wide: true,
      placeholder: 'from your Konnect dashboard → Developers',
      hint: 'Sent as x-api-key on every call.' },
    { key: 'shortCode', label: 'Receiver wallet ID', wide: true,
      placeholder: 'e.g. 5f7a209dfc9c6a0021a4b3ce',
      hint: 'Which of your wallets the money lands in. Not a secret. Konnect refuses '
        + 'every payment without it.' },
  ],
  PAYMOB: [
    { key: 'secretKey', label: 'API key', secret: true, wide: true,
      placeholder: 'the long key from Dashboard → Settings → Account Info',
      hint: 'This buys an access token and is used for nothing else.' },
    { key: 'webhookSecret', label: 'HMAC secret', secret: true, wide: true,
      placeholder: 'beside the API key, on the same page',
      hint: 'Without this a callback cannot be told from a forgery, and this endpoint '
        + 'issues vouchers — so payments will not settle until it is set.' },
    { key: 'shortCode', label: 'Integration ID',
      placeholder: 'e.g. 4077777',
      hint: 'Which payment method to charge. Paymob gives you one per method — the card '
        + 'one and the wallet one are different numbers.' },
    { key: 'publicKey', label: 'Iframe ID',
      placeholder: 'e.g. 890123',
      hint: 'From Developers → iframes. Not a secret — it is half the address your '
        + 'customer opens.' },
  ],
  VODACOM_MPESA: [
    { key: 'secretKey', label: 'API key', secret: true,
      placeholder: 'from your app on openapiportal.m-pesa.com' },
    { key: 'shortCode', label: 'Service provider code',
      placeholder: 'the till the money lands in',
      hint: 'Vodacom calls this the service provider code. 000000 in the sandbox.' },
    { key: 'publicKey', label: 'Vodacom public key', wide: true, multiline: true,
      placeholder: 'the long block of characters beside your API key',
      hint: 'Not a secret, and still required: your API key and then every session '
        + 'is encrypted under it. Paste the whole thing — line breaks are fine.' },
  ],
  ORANGE_MONEY: [
    { key: 'consumerKey', label: 'Client ID',
      placeholder: 'from your app on developer.orange.com' },
    { key: 'consumerSecret', label: 'Client secret', secret: true,
      placeholder: 'beside the client ID' },
    { key: 'shortCode', label: 'Merchant key', wide: true,
      placeholder: 'MerchantKey from your Orange Money merchant account',
      hint: 'Not a secret — but Orange refuses every payment without it, '
        + 'and the error does not say so.' },
  ],
}

/** What the sandbox actually does, per rail, since none of them collect money. */
const SANDBOX_NOTE = {
  MTN_MOMO: 'MTN’s sandbox settles in euros whatever your currency is, and collects nothing.',
  AIRTEL_MONEY: 'Airtel’s sandbox accepts test numbers only, and collects nothing.',
  ORANGE_MONEY: 'Orange’s sandbox only accepts 1 unit of a fake currency, whatever the price is — '
    + 'so it proves your keys work and nothing at all about amounts.',
  VODACOM_MPESA: 'Vodacom’s sandbox answers with test outcomes and collects nothing. '
    + 'The service provider code there is 000000.',
  PAYMOB: 'Paymob’s test mode takes their test card numbers and collects nothing. Switch to '
    + 'Production and paste your live keys before pointing customers at it.',
  KONNECT: 'Konnect’s preprod environment accepts test payments and collects nothing. Note the '
    + 'dinar has a thousand millimes — set Currency decimals to 3 under Branding.',
}


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

const OTHER_BANK = 'Other — type it in'

/**
 * A number that has to be typed twice.
 *
 * The account, paybill and till numbers are the one thing on this screen that
 * nothing downstream can check. A wrong digit is not rejected by anybody — it
 * is money arriving somewhere else. Typing it twice means a typo has to be
 * made identically twice, which is the only defence available.
 */
function Confirmed({ label, value, confirm, placeholder, onValue, onConfirm }) {
  const touched = (confirm || '').length > 0
  const matches = (value || '') === (confirm || '')
  // Kept together in their own row. Loose in the parent grid, the confirmation
  // landed beside an unrelated field and read as belonging to it.
  return (
    <div className="md:col-span-2 grid grid-cols-1 md:grid-cols-2 gap-4">
      <div>
        <label className={LABEL_CLS}>{label}</label>
        <input className={INPUT_CLS} required value={value} placeholder={placeholder}
          onChange={(e) => onValue(e.target.value)} />
      </div>
      <div>
        <label className={LABEL_CLS}>Type it again</label>
        <input
          className={`${INPUT_CLS} ${touched && !matches ? 'border-error' : ''}`}
          required value={confirm}
          // Paste defeats the point entirely — it copies the typo.
          onPaste={(e) => e.preventDefault()}
          onChange={(e) => onConfirm(e.target.value)} />
        {touched && !matches && (
          <p className="text-xs text-error mt-1">These don&rsquo;t match.</p>
        )}
        {touched && matches && (
          <p className="text-xs text-primary mt-1">Matches.</p>
        )}
      </div>
    </div>
  )
}

function ConfigureForm({ auth, gateway, saved, webhookBase, banks, onCancel, onSaved }) {
  const isApi = gateway.kind === 'MPESA_API'
  const isCard = CARD_KINDS.includes(gateway.kind)
  const telcoFields = TELCO_FIELDS[gateway.kind]
  // A saved bank that is not in the list means the operator typed it before,
  // so the free-text box opens showing it rather than silently dropping it.
  const [otherBank, setOtherBank] = useState(
    !!saved?.bankName && !banks.includes(saved.bankName))
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
    // Seeded from what is stored, so editing an unrelated field does not make
    // the operator retype a number they got right months ago.
    paybillConfirm: saved?.paybillNumber || '',
    tillNumber: saved?.tillNumber || '',
    tillConfirm: saved?.tillNumber || '',
    bankName: saved?.bankName || '',
    accountNumber: saved?.accountNumber || '',
    accountConfirm: saved?.accountNumber || '',
    accountName: saved?.accountName || '',
    instructions: saved?.instructions || '',
  })
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const pickedBank = otherBank ? OTHER_BANK : (form.bankName || '')

  /** Every number that must be typed twice actually was. */
  function mismatched() {
    const pairs = [
      [form.paybillNumber, form.paybillConfirm, gateway.kind === 'MPESA_PAYBILL_MANUAL'],
      [form.tillNumber, form.tillConfirm, gateway.kind === 'MPESA_TILL_MANUAL'],
      [form.accountNumber, form.accountConfirm, gateway.kind === 'BANK_TRANSFER'],
    ]
    return pairs.some(([a, b, applies]) => applies && (a || '') !== (b || ''))
  }

  async function submit(e) {
    e.preventDefault()
    // Refused rather than saved. A number that failed its own confirmation is
    // one the operator has already half-noticed is wrong.
    if (mismatched()) {
      setMsg({ ok: false, text: 'The number and its confirmation do not match.' })
      return
    }
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

  // Which rails can be checked without taking a payment. Vodacom earns its
  // place here: correct credentials on an app whose payment product was never
  // switched on look exactly like a working setup, and the only other way to
  // find out is a customer waiting a quarter of an hour for nothing.
  const testable = gateway.kind === 'MPESA_API' || gateway.kind === 'VODACOM_MPESA'

  async function test() {
    setBusy(true)
    setMsg(null)
    try {
      const r = await api(`/admin/settings/payments/${gateway.kind}/test`, { method: 'POST', auth })
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
      ) : gateway.kind === 'PAYNOW' ? (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className={LABEL_CLS}>Integration ID</label>
              <input className={INPUT_CLS} value={form.consumerKey}
                onChange={(e) => set({ consumerKey: e.target.value })}
                placeholder={saved?.consumerKey || 'from your Paynow dashboard'} />
            </div>
            <div>
              <label className={LABEL_CLS}>
                Integration key {saved?.secretKey && <span className="normal-case font-normal">(blank = keep)</span>}
              </label>
              <input className={INPUT_CLS} type="password" value={form.secretKey}
                onChange={(e) => set({ secretKey: e.target.value })}
                placeholder={saved?.secretKey || 'the long key beside the ID'} />
              <p className="text-xs text-on-surface-variant mt-1">
                Not just a password — every message Paynow sends is hashed with it, and that hash
                is what proves a payment really happened.
              </p>
            </div>
          </div>

          <div className="rounded-lg border border-outline-variant p-3">
            <p className="text-sm font-medium">Result URL for your Paynow dashboard</p>
            <p className="text-xs text-on-surface-variant mt-1 mb-2">
              Where Paynow posts the outcome. Every message is checked against its hash before it
              is believed, and a payment still settles without this — the sweep asks Paynow directly.
            </p>
            {webhookBase
              ? <CopyUrl url={webhookBase + gateway.webhook} />
              : (
                <p className="text-xs text-[#b45309]">
                  No public address is configured, so the URL can&rsquo;t be shown.
                </p>
              )}
          </div>

          <p className="text-xs text-[#b45309]">
            No charge has gone through a live Paynow account yet. The hashing is tested; the
            conversation with Paynow is not.
          </p>
        </>
      ) : telcoFields ? (
        <>
          {/* Not every rail has one. WaafiPay serves both from a single address
              and the credentials Hormuud issued decide whether money moves, so a
              Sandbox toggle there would claim a choice that does not exist. */}
          {HAS_ENVIRONMENT.includes(gateway.kind) ? (
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
            {form.environment === 'SANDBOX' && SANDBOX_NOTE[gateway.kind] && (
              <p className="text-xs text-[#b45309] mt-1.5">{SANDBOX_NOTE[gateway.kind]}</p>
            )}
          </div>
          ) : (
            <p className="text-xs text-on-surface-variant">
              There is no sandbox to switch to &mdash; {gateway.name} serves testing and live
              from one address, and the credentials you were issued decide which you get.
            </p>
          )}

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {telcoFields.map((f) => (
              <div key={f.key} className={f.wide ? 'md:col-span-2' : undefined}>
                <label className={LABEL_CLS}>
                  {f.label}
                  {f.secret && saved?.[f.key] && (
                    <span className="normal-case font-normal"> (blank = keep)</span>
                  )}
                </label>
                {/* An RSA public key is some four hundred characters. In a
                    single-line input an operator cannot see whether they pasted
                    all of it, and a half-paste fails at authentication with an
                    error that blames the key rather than its length. */}
                {f.multiline ? (
                  <textarea className={`${INPUT_CLS} h-24 font-mono text-xs`}
                    value={form[f.key]}
                    onChange={(e) => set({ [f.key]: e.target.value })}
                    placeholder={f.placeholder} />
                ) : (
                  <input className={INPUT_CLS} type={f.secret ? 'password' : 'text'}
                    value={form[f.key]}
                    onChange={(e) => set({ [f.key]: e.target.value })}
                    placeholder={(f.secret && saved?.[f.key]) || f.placeholder} />
                )}
                {f.hint && <p className="text-xs text-on-surface-variant mt-1">{f.hint}</p>}
              </div>
            ))}
          </div>

          {gateway.webhook ? (
            <div className="rounded-lg border border-outline-variant p-3">
              <p className="text-sm font-medium">Callback</p>
              <p className="text-xs text-on-surface-variant mt-1 mb-2">
                None of these sign what they send, so this system never believes a callback — it asks
                them directly instead, and asks again on a sweep if nothing arrives. Setting this makes
                a paid customer get online in seconds rather than up to a minute and a half.
              </p>
              {webhookBase
                ? <CopyUrl url={webhookBase + gateway.webhook} />
                : (
                  <p className="text-xs text-[#b45309]">
                    No public address is configured, so the URL can&rsquo;t be shown. Payments still
                    settle — the sweep asks every minute.
                  </p>
                )}
            </div>
          ) : (
            /* Vodacom does not call back at all. An empty Callback box would
               have an operator hunting their portal for a field that is not
               there. */
            <div className="rounded-lg border border-outline-variant p-3">
              <p className="text-sm font-medium">There is no callback</p>
              <p className="text-xs text-on-surface-variant mt-1">
                {gateway.name} never posts anything back, so there is nothing to set up and nothing
                to paste anywhere. Payments settle by this system asking, on a sweep that runs every
                minute — a paying customer is online within about that.
              </p>
            </div>
          )}

          {(saved?.consumerKey || saved?.secretKey) && (
            <p className="text-xs text-on-surface-variant">
              Secrets are never shown again once saved. Leave a field blank to keep what is stored.
            </p>
          )}
          <p className="text-xs text-[#b45309]">
            No charge has gone through a live {gateway.name} account yet. Take one small real payment
            and confirm the customer gets online before pointing customers at it.
          </p>
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
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {gateway.kind === 'MPESA_PAYBILL_MANUAL' && (
              <>
                <Confirmed label="Paybill number" value={form.paybillNumber}
                  confirm={form.paybillConfirm} placeholder="e.g. 522522"
                  onValue={(v) => set({ paybillNumber: v })}
                  onConfirm={(v) => set({ paybillConfirm: v })} />
                <div>
                  <label className={LABEL_CLS}>Account name shown to customers</label>
                  <input className={INPUT_CLS} value={form.accountName}
                    onChange={(e) => set({ accountName: e.target.value })}
                    placeholder="e.g. their phone number" />
                </div>
              </>
            )}
            {gateway.kind === 'MPESA_TILL_MANUAL' && (
              <Confirmed label="Till number" value={form.tillNumber}
                confirm={form.tillConfirm} placeholder="e.g. 8461234"
                onValue={(v) => set({ tillNumber: v })}
                onConfirm={(v) => set({ tillConfirm: v })} />
            )}
            {gateway.kind === 'BANK_TRANSFER' && (
              <>
                <div>
                  <label className={LABEL_CLS}>Bank</label>
                  {/* A list, not a text box. "Equty Bank" is not a bank, and
                      nothing downstream would ever have told the operator so —
                      they would find out from a confused customer. */}
                  <select className={INPUT_CLS} value={pickedBank}
                    onChange={(e) => {
                      const v = e.target.value
                      setOtherBank(v === OTHER_BANK)
                      set({ bankName: v === OTHER_BANK ? '' : v })
                    }}>
                    <option value="">Choose your bank…</option>
                    {banks.map((b) => <option key={b} value={b}>{b}</option>)}
                  </select>
                  {otherBank && (
                    <input className={`${INPUT_CLS} mt-2`} required value={form.bankName}
                      onChange={(e) => set({ bankName: e.target.value })}
                      placeholder="Type the bank's full name" />
                  )}
                </div>
                <Confirmed label="Account number" value={form.accountNumber}
                  confirm={form.accountConfirm}
                  onValue={(v) => set({ accountNumber: v })}
                  onConfirm={(v) => set({ accountConfirm: v })} />
                <div>
                  <label className={LABEL_CLS}>Account name</label>
                  <input className={INPUT_CLS} value={form.accountName}
                    onChange={(e) => set({ accountName: e.target.value })} />
                </div>
              </>
            )}
          </div>

          {/* Nothing checks these against the bank, so the only defence is
              showing the operator exactly what a customer will read. */}
          <div className="rounded-lg border border-outline-variant p-3">
            <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2">
              What the customer will see
            </p>
            <div className="text-sm space-y-0.5">
              {gateway.kind === 'BANK_TRANSFER' ? (
                <>
                  <p>Bank: <strong>{form.bankName || '—'}</strong></p>
                  <p>Account number: <strong className="font-mono">{form.accountNumber || '—'}</strong></p>
                  <p>Account name: <strong>{form.accountName || '—'}</strong></p>
                </>
              ) : gateway.kind === 'MPESA_TILL_MANUAL' ? (
                <p>Buy Goods till: <strong className="font-mono">{form.tillNumber || '—'}</strong></p>
              ) : (
                <>
                  <p>Pay Bill: <strong className="font-mono">{form.paybillNumber || '—'}</strong></p>
                  <p>Account: <strong>{form.accountName || 'their phone number'}</strong></p>
                </>
              )}
            </div>
            <p className="text-xs text-[#b45309] mt-2">
              Nobody checks this against the bank. A wrong digit sends your customers&rsquo; money to a
              stranger and nothing here would notice — read it back before saving.
            </p>
          </div>
        </>
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
        {testable && saved?.configured && (
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

/**
 * The gateway's mark: a real brand logo where the operator has supplied one,
 * the generic glyph otherwise.
 *
 * Logos are dropped into `frontend/src/assets/gateways/` as inline SVG — not
 * a CDN link, because the captive portal has no internet until the customer
 * has paid, which is exactly when a missing logo would be noticed.
 */
function GatewayMark({ gateway }) {
  const Logo = GATEWAY_LOGOS[gateway.kind]
  return (
    // Was a fixed 44px square, which suits an icon-form mark and crushes a
    // wordmark: M-Pesa, MTN, Paystack and Paynow all publish wide logos, and
    // Paystack's is 5.6:1 -- eight pixels tall in a square. The chip grows
    // instead, so each mark is shown at the shape its owner drew it.
    <span className="h-11 min-w-11 px-2 rounded-lg bg-surface-container-high flex items-center justify-center shrink-0 overflow-hidden">
      {Logo ? <Logo /> : <Icon name={gateway.icon} className="text-[22px]!" />}
    </span>
  )
}

/**
 * The configure form, in a modal.
 *
 * It used to expand the card inline, which on a two-column grid shoved
 * everything else around and pushed Save below the fold. Every other admin
 * screen uses a modal for this; consistency is the smaller reason and not
 * losing the button is the larger one.
 */
function ConfigureModal({ gateway, onClose, ...rest }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 bg-on-background/50 backdrop-blur-sm z-50 flex items-center justify-center p-5"
      onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="bg-surface-container-lowest w-full max-w-2xl rounded-xl shadow-[0_8px_24px_rgba(15,23,42,0.15)] max-h-[90vh] flex flex-col">
        <div className="p-6 border-b border-outline-variant/50 flex justify-between items-start gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <GatewayMark gateway={gateway} />
            <div className="min-w-0">
              <h3 className="text-xl font-bold text-on-background truncate">{gateway.name}</h3>
              <p className="text-xs text-on-surface-variant truncate">{gateway.provider}</p>
            </div>
          </div>
          <button onClick={onClose} aria-label="Close"
            className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-full hover:bg-error/10 cursor-pointer shrink-0">
            <Icon name="close" />
          </button>
        </div>
        <div className="overflow-y-auto px-6 pb-6">
          <ConfigureForm gateway={gateway} onCancel={onClose} {...rest} />
        </div>
      </div>
    </div>
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
        ? { ok: true, text: 'Switched on. Customers can now pay through this gateway.' }
        : { ok: false, text: 'Switched on, but this gateway is on sandbox — it will not collect real money.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  /**
   * Switching one off, which this screen had no way to do at all — the endpoint
   * existed and nothing called it, so gateways could be turned on and never off.
   *
   * The server refuses to switch off the last one and says why; that message is
   * shown as-is rather than being second-guessed here.
   */
  async function deactivate(kind) {
    try {
      const r = await api(`/admin/settings/payments/${kind}/deactivate`, { method: 'POST', auth })
      setMsg({ ok: true, text: r.message
        || 'Switched off. Customers are no longer offered this one.' })
      load()
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    }
  }

  if (!data) return <Skeleton className="h-64" />

  const saved = Object.fromEntries(data.gateways.map((g) => [g.kind, g]))
  // Plural. The API still returns activeKind for anything reading the old
  // shape, but several gateways run at once now and a screen that names only
  // the first tells an operator two thirds of their setup is not working.
  const offered = data.offered || (data.activeKind ? [data.activeKind] : [])
  // Short names for the summary. The card titles carry qualifiers that read as
  // errors once comma-separated -- "live: Paybill - no API keys" looks like a
  // complaint rather than a list of what is switched on.
  const offeredNames = offered
    .map((k) => GATEWAYS.find((g) => g.kind === k)?.name || k)
    .map((n) => n.split(/\s+[-—]\s+|\s+\/\s+/)[0])

  return (
    <div>
      <PageHeader
        title="Payments"
        subtitle="Switch on as many as your customers actually use. They pick at checkout, and whichever is first is what USSD and the WhatsApp bot use. Leaving a field blank keeps the saved credential."
      />

      <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-4">
        {data.available} gateways available · {data.connected > 0 ? `${data.connected} set up` : 'none connected'}
        {offeredNames.length > 0 && ` · live: ${offeredNames.join(', ')}`}
      </p>

      {msg && <p className={`mb-4 text-sm ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}

      {offered.length === 0 && (
        <div className="mb-6 p-4 rounded-lg bg-[#f59e0b]/10 border border-[#f59e0b]/30">
          <p className="text-sm text-[#b45309]">
            Nothing is switched on, so nobody can pay you yet. Set one up below and switch it on —
            you can have several running at once.
          </p>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {GATEWAYS.map((g) => {
          const s = saved[g.kind] || {}
          const isActive = s.active
          return (
            <div key={g.kind}
              className={`bg-surface-container-lowest rounded-lg p-4 border transition-colors ${
                isActive ? 'border-primary' : 'border-outline-variant/40'
              }`}>
              <div className="flex items-start gap-3">
                <GatewayMark gateway={g} />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-bold">{g.name}</h3>
                    <span className="px-1.5 py-0.5 rounded bg-surface-container-high text-[10px] font-bold tracking-wider">
                      {g.badge}
                    </span>
                    {isActive && (
                      <span className="px-2 py-0.5 rounded-full bg-primary text-on-primary text-[10px] font-bold tracking-wider">
                        {s.live ? 'ON' : CARD_KINDS.includes(g.kind) ? 'ON · TEST KEYS' : 'ON · SANDBOX'}
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
                  {/* A toggle, not an exclusive choice. "Make active" read as
                      "switch the others off", which is exactly what this stopped
                      doing when several gateways became possible at once. */}
                  {s.configured && !isActive && (
                    <button onClick={() => activate(g.kind)}
                      className="px-3 py-1.5 rounded-md border border-outline-variant text-on-surface text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
                      Switch on
                    </button>
                  )}
                  {isActive && (
                    <button onClick={() => deactivate(g.kind)}
                      className="px-3 py-1.5 rounded-md border border-outline-variant text-on-surface-variant text-xs font-semibold hover:bg-surface-container-high transition-colors cursor-pointer">
                      Switch off
                    </button>
                  )}
                  <button onClick={() => setOpenKind(g.kind)}
                    className="px-3 py-1.5 rounded-lg border border-outline-variant text-xs font-semibold cursor-pointer hover:bg-surface-container-high flex items-center gap-1">
                    {s.configured ? 'Edit' : 'Configure'}
                    <Icon name="chevron_right" className="text-[14px]!" />
                  </button>
                </div>
              </div>

            </div>
          )
        })}
      </div>

      {openKind && (
        <ConfigureModal
          auth={auth}
          gateway={GATEWAYS.find((g) => g.kind === openKind)}
          saved={saved[openKind] || {}}
          webhookBase={data.webhookBase}
          banks={data.banks || []}
          onClose={() => setOpenKind(null)}
          onSaved={() => {
            const name = GATEWAYS.find((g) => g.kind === openKind)?.name
            setOpenKind(null)
            setMsg({ ok: true, text: `${name} saved.` })
            load()
          }}
        />
      )}

      <p className="mt-6 text-xs text-on-surface-variant max-w-3xl">
        Each gateway needs a merchant account with that provider. The three card processors are built and their
        webhooks are verified against forgery, but no charge has yet been made through a live merchant account —
        take one small real payment yourself before pointing customers at one.
      </p>
    </div>
  )
}
