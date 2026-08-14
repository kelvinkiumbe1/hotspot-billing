import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api.js'
import { designByKey, normalizeDesignKey } from '../portalDesigns.js'
import heroCity from '../assets/hero-city.jpg'
import customerPhoto from '../assets/customer.jpg'

/* ------------------------------------------------------------------ */
/* Localization — English + Kiswahili for the customer captive portal  */
/* ------------------------------------------------------------------ */

const STRINGS = {
  EN: {
    'hero.title': 'Get Connected in Seconds',
    'hero.sub': 'Fast, reliable internet across the city.',
    'hero.minTitle': 'Get Connected',
    'hero.minSub': 'Pick a pass and pay with M-Pesa.',
    'breeze.hi': 'Karibu — let’s get you online.',
    'breeze.sub': 'Pick a pass, pay with M-Pesa, and you’re browsing in under a minute.',
    'tab.all': 'All',
    'steps.heading': 'How to get connected',
    'steps.1': 'Pick an internet pass below',
    'steps.2': 'Enter your M-Pesa number and send the request',
    'steps.3': 'Approve the payment on your phone',
    'steps.4': 'Connect with the access code we text you',
    'steps.plans': 'Internet passes',
    'matrix.hint': 'Tap a plan to buy it with M-Pesa.',
    'poster.tag': 'Internet by the hour, day and month.',
    'neon.online': 'network: online',
    'neon.select': 'select a pass to continue',
    'card.buyShort': 'Buy',
    'pay.checkout': 'Secure M-Pesa checkout',
    'pay.total': 'Total',
    'pay.payNow': 'Pay KES {n}',
    'ok.granted': 'Access granted',
    'wait.printing': 'Preparing your pass',
    'poster.paid': 'PAID',
    'steps.of': 'Step {n} of {m}',
    'neon.confirmed': 'payment confirmed',
    'neon.grant': 'ACCESS GRANTED',
    'recover.q': 'Already paid but not connected?',
    'recover.hint': 'Enter the number you paid with and we’ll text your access code.',
    'recover.phone': 'Phone you paid with',
    'recover.btn': 'Send my code',
    'recover.sending': 'Checking…',
    'verify.q': 'Paid by Paybill or Till?',
    'verify.hint': 'Enter your M-Pesa code and the number you paid with — we’ll verify it and text your access code.',
    'verify.code': 'M-Pesa code',
    'verify.btn': 'Verify payment',
    'verify.sending': 'Verifying…',
    'group.Hourly': 'Hourly Passes',
    'group.Daily': 'Daily Passes',
    'group.Weekly': 'Weekly Passes',
    'group.Monthly': 'Monthly Passes',
    'plans.retry': 'Retry',
    'plans.offline': "We can't reach the server right now. Check your connection and try again.",
    'voucher.label': 'Have a voucher or M-Pesa code?',
    'voucher.placeholder': 'Enter code, or paste your M-Pesa message',
    'voucher.redeem': 'Redeem',
    'voucher.checking': 'Checking…',
    'nav.connect': 'Connect',
    'nav.plans': 'Plans',
    'nav.help': 'Help',
    'card.popular': 'MOST POPULAR',
    'card.price': 'Price',
    'card.buy': 'Buy with M-Pesa',
    'card.devices': 'device',
    'card.devices_plural': 'devices',
    'custom.heading': 'Custom Pass',
    'custom.title': 'Only need a little time?',
    'custom.perHour': 'KES {n}/hour, billed per minute',
    'custom.minutes': 'Minutes you need',
    'custom.youPay': 'You pay',
    'custom.range': 'Choose between {min} and {max} minutes.',
    'custom.buy': 'Buy {dur} with M-Pesa',
    'pay.complete': 'Complete Payment',
    'pay.sub': 'Fast, secure M-Pesa transaction.',
    'pay.summary': 'Selected Plan Summary',
    'pay.access': '{name} Access',
    'pay.phone': 'M-Pesa Phone Number',
    'pay.info': "You'll receive an M-Pesa prompt on your phone. Enter your PIN to complete the transaction.",
    'pay.send': 'Send Payment Request',
    'pay.sending': 'Sending request…',
    'wait.title': 'Awaiting Payment',
    'wait.sub': 'Check your phone for the M-Pesa prompt.',
    'wait.sent': 'Request sent',
    'wait.pin': 'Enter your M-Pesa PIN',
    'wait.activating': 'Activating your access',
    'wait.cancel': 'Cancel payment',
    'ok.title': "You're Connected!",
    'ok.codeLabel': 'Access Code',
    'ok.usePre': 'Use this code as your WiFi',
    'ok.username': 'username',
    'ok.and': 'and',
    'ok.password': 'password',
    'ok.copyConnect': 'Copy Code & Connect',
    'ok.copied': 'Code Copied!',
    'ok.continue': 'Continue',
    'ok.return': 'Return to Home',
    'ok.closingRedirect': 'Taking you on in {n}s — connect with your code first.',
    'ok.closing': 'This page will close in {n}s — connect to the WiFi with your code.',
    'ok.closed': 'You can now connect to the WiFi with your code.',
    'err.title': 'Payment Failed',
    'err.badge': 'Error',
    'err.retry': 'Retry Payment',
    'err.choose': 'Choose another plan',
    'err.support': 'Support:',
    'rewards.title': 'Rewards',
    'rewards.phone': 'Your phone e.g. 0712…',
    'rewards.check': 'Check',
    'rewards.youHave': 'You have',
    'rewards.points': 'point(s) — up to',
    'rewards.freeMin': 'free minutes.',
    'rewards.minutesLabel': 'Minutes to redeem',
    'rewards.costs': 'costs {n} pts',
    'rewards.redeem': 'Redeem',
    'rewards.needMore': 'Earn a bit more — you need at least {n} points to redeem.',
    'rewards.unavailable': 'Rewards are not available right now.',
    'foot.terms': 'Terms',
    'foot.help': 'Help',
    'foot.powered': 'Powered by SPA Limited',
    'promo.endsIn': 'Ends in',
  },
  SW: {
    'hero.title': 'Pata Intaneti kwa Sekunde',
    'hero.sub': 'Intaneti ya haraka na ya kuaminika mjini kote.',
    'hero.minTitle': 'Pata Intaneti',
    'hero.minSub': 'Chagua kifurushi ulipe na M-Pesa.',
    'breeze.hi': 'Karibu — tukuunganishe mtandaoni.',
    'breeze.sub': 'Chagua kifurushi, lipa na M-Pesa, uanze kutumia intaneti kwa dakika chache.',
    'tab.all': 'Zote',
    'steps.heading': 'Jinsi ya kuunganishwa',
    'steps.1': 'Chagua kifurushi cha intaneti hapa chini',
    'steps.2': 'Weka nambari yako ya M-Pesa utume ombi',
    'steps.3': 'Kubali malipo kwenye simu yako',
    'steps.4': 'Unganisha kwa nambari tutakayokutumia',
    'steps.plans': 'Vifurushi vya intaneti',
    'matrix.hint': 'Gusa kifurushi kununua kwa M-Pesa.',
    'poster.tag': 'Intaneti kwa saa, siku na mwezi.',
    'neon.online': 'mtandao: unapatikana',
    'neon.select': 'chagua kifurushi kuendelea',
    'card.buyShort': 'Nunua',
    'pay.checkout': 'Malipo salama ya M-Pesa',
    'pay.total': 'Jumla',
    'pay.payNow': 'Lipa KES {n}',
    'ok.granted': 'Ufikiaji umeruhusiwa',
    'wait.printing': 'Kifurushi chako kinaandaliwa',
    'poster.paid': 'IMELIPWA',
    'steps.of': 'Hatua {n} kati ya {m}',
    'neon.confirmed': 'malipo yamethibitishwa',
    'neon.grant': 'UFIKIAJI UMERUHUSIWA',
    'recover.q': 'Umelipa lakini hujaunganishwa?',
    'recover.hint': 'Weka nambari uliyolipia nayo, tutakutumia nambari yako ya ufikiaji kwa SMS.',
    'recover.phone': 'Simu uliyolipia nayo',
    'recover.btn': 'Nitumie nambari yangu',
    'recover.sending': 'Inaangalia…',
    'verify.q': 'Ulilipa kwa Paybill au Till?',
    'verify.hint': 'Weka nambari ya M-Pesa na simu uliyolipia nayo — tutaithibitisha na kukutumia nambari yako ya ufikiaji.',
    'verify.code': 'Nambari ya M-Pesa',
    'verify.btn': 'Thibitisha malipo',
    'verify.sending': 'Inathibitisha…',
    'group.Hourly': 'Vifurushi vya Saa',
    'group.Daily': 'Vifurushi vya Siku',
    'group.Weekly': 'Vifurushi vya Wiki',
    'group.Monthly': 'Vifurushi vya Mwezi',
    'plans.retry': 'Jaribu tena',
    'plans.offline': 'Hatuwezi kufikia seva kwa sasa. Angalia muunganisho wako ujaribu tena.',
    'voucher.label': 'Una kuponi au nambari ya M-Pesa?',
    'voucher.placeholder': 'Weka nambari, au bandika ujumbe wa M-Pesa',
    'voucher.redeem': 'Tumia',
    'voucher.checking': 'Inaangalia…',
    'nav.connect': 'Unganisha',
    'nav.plans': 'Vifurushi',
    'nav.help': 'Msaada',
    'card.popular': 'MAARUFU ZAIDI',
    'card.price': 'Bei',
    'card.buy': 'Nunua na M-Pesa',
    'card.devices': 'kifaa',
    'card.devices_plural': 'vifaa',
    'custom.heading': 'Kifurushi Maalum',
    'custom.title': 'Unahitaji muda kidogo tu?',
    'custom.perHour': 'KES {n}/saa, hulipwa kwa dakika',
    'custom.minutes': 'Dakika unazohitaji',
    'custom.youPay': 'Unalipa',
    'custom.range': 'Chagua kati ya dakika {min} na {max}.',
    'custom.buy': 'Nunua {dur} na M-Pesa',
    'pay.complete': 'Kamilisha Malipo',
    'pay.sub': 'Malipo ya haraka na salama ya M-Pesa.',
    'pay.summary': 'Muhtasari wa Kifurushi',
    'pay.access': '{name}',
    'pay.phone': 'Nambari ya Simu ya M-Pesa',
    'pay.info': 'Utapokea ombi la M-Pesa kwenye simu yako. Weka PIN yako kukamilisha malipo.',
    'pay.send': 'Tuma Ombi la Malipo',
    'pay.sending': 'Inatuma ombi…',
    'wait.title': 'Inasubiri Malipo',
    'wait.sub': 'Angalia simu yako kwa ombi la M-Pesa.',
    'wait.sent': 'Ombi limetumwa',
    'wait.pin': 'Weka PIN yako ya M-Pesa',
    'wait.activating': 'Inawasha ufikiaji wako',
    'wait.cancel': 'Ghairi malipo',
    'ok.title': 'Umeunganishwa!',
    'ok.codeLabel': 'Nambari ya Ufikiaji',
    'ok.usePre': 'Tumia nambari hii kama',
    'ok.username': 'jina la mtumiaji',
    'ok.and': 'na',
    'ok.password': 'nenosiri',
    'ok.copyConnect': 'Nakili Nambari & Unganisha',
    'ok.copied': 'Imenakiliwa!',
    'ok.continue': 'Endelea',
    'ok.return': 'Rudi Mwanzo',
    'ok.closingRedirect': 'Tunakupeleka baada ya sekunde {n} — unganisha na nambari yako kwanza.',
    'ok.closing': 'Ukurasa huu utafunga baada ya sekunde {n} — unganisha na WiFi kwa nambari yako.',
    'ok.closed': 'Sasa unaweza kuunganisha na WiFi kwa nambari yako.',
    'err.title': 'Malipo Yameshindikana',
    'err.badge': 'Hitilafu',
    'err.retry': 'Jaribu Malipo Tena',
    'err.choose': 'Chagua kifurushi kingine',
    'err.support': 'Msaada:',
    'rewards.title': 'Zawadi',
    'rewards.phone': 'Simu yako mf. 0712…',
    'rewards.check': 'Angalia',
    'rewards.youHave': 'Una',
    'rewards.points': 'pointi — hadi',
    'rewards.freeMin': 'dakika za bure.',
    'rewards.minutesLabel': 'Dakika za kutumia',
    'rewards.costs': 'gharama pointi {n}',
    'rewards.redeem': 'Tumia',
    'rewards.needMore': 'Pata zaidi kidogo — unahitaji angalau pointi {n} kutumia.',
    'rewards.unavailable': 'Zawadi hazipatikani kwa sasa.',
    'foot.terms': 'Masharti',
    'foot.help': 'Msaada',
    'foot.powered': 'Inaendeshwa na SPA Limited',
    'promo.endsIn': 'Inaisha baada ya',
  },
}

const LangContext = createContext({ lang: 'EN', setLang: () => {}, design: 'CLASSIC', brand: { name: '', logoUrl: null, headline: '', subheadline: '' } })

function useT() {
  const { lang, setLang, design: designKey, brand } = useContext(LangContext)
  const t = (key, vars) => {
    let s = (STRINGS[lang] && STRINGS[lang][key]) || STRINGS.EN[key] || key
    if (vars) {
      for (const k of Object.keys(vars)) s = s.split('{' + k + '}').join(vars[k])
    }
    return s
  }
  // Everything a screen needs to paint itself in the chosen design.
  const design = designByKey(designKey)
  return { t, lang, setLang, design, designVars: design.vars, brand: brand || {} }
}

function LangToggle() {
  const { lang, setLang } = useContext(LangContext)
  return (
    <button
      type="button"
      onClick={() => setLang(lang === 'EN' ? 'SW' : 'EN')}
      className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant border border-outline-variant rounded-full px-3 py-1.5 hover:bg-surface-container transition-colors cursor-pointer"
      aria-label="Switch language"
    >
      {lang === 'EN' ? 'Kiswahili' : 'English'}
    </button>
  )
}

const POLL_INTERVAL_MS = 3000
const MAX_POLLS = 40
const SUPPORT_PHONE = '+254 700 000 000'

function formatDuration(minutes) {
  if (minutes < 60) return `${minutes} min`
  if (minutes < 1440) {
    const h = Math.floor(minutes / 60)
    const m = minutes % 60
    return m ? `${h} hr ${m} min` : `${h} Hour${h > 1 ? 's' : ''}`
  }
  const d = Math.floor(minutes / 1440)
  const h = Math.floor((minutes % 1440) / 60)
  return h ? `${d} Day${d > 1 ? 's' : ''} ${h} hr` : `${d} Day${d > 1 ? 's' : ''}`
}

const PLAN_GROUPS = ['Hourly', 'Daily', 'Weekly', 'Monthly']

function planGroup(minutes) {
  if (minutes < 1440) return 'Hourly'
  if (minutes < 7 * 1440) return 'Daily'
  if (minutes < 28 * 1440) return 'Weekly'
  return 'Monthly'
}

function speedLabel(bandwidth) {
  if (!bandwidth) return null
  const down = bandwidth.split('/')[0].trim()
  return /^\d+$/.test(down) ? `${down} Mbps` : `${down}bps`.replace('Mbps', ' Mbps')
}

// Accepts "0712...", "712...", "254712...", with or without spaces
function normalizePhone(raw) {
  let d = raw.replace(/\D/g, '')
  if (d.startsWith('254')) d = d.slice(3)
  if (d.startsWith('0')) d = d.slice(1)
  return '254' + d
}

import { Icon } from '../components/icons.jsx'

function Brand() {
  const { brand } = useT()
  if (brand?.logoUrl) {
    return <img src={brand.logoUrl} alt={brand.name || 'WiFi'} className="h-8 w-auto object-contain" />
  }
  return (
    <div className="flex items-center gap-2">
      <Icon name="wifi" className="text-primary" />
      <span className="text-lg font-semibold text-primary tracking-tight uppercase">{brand?.name || 'WiFi'}</span>
    </div>
  )
}

/* The ISP's name as plain text, for the design variants that print a wordmark
   in their own typography. Falls back to a generic label before it's set. */
function BrandName() {
  const { brand } = useT()
  return <>{brand?.name || 'WiFi'}</>
}

/* Hero copy: the ISP's own headline/subheadline when they've set them,
   otherwise the translated default. */
function HeroTitle() {
  const { t, brand } = useT()
  return <>{brand?.headline || t('hero.title')}</>
}
function HeroSub() {
  const { t, brand } = useT()
  return <>{brand?.subheadline || t('hero.sub')}</>
}

/* Paints the chosen design's tokens over the shared .portal-theme scaffold.
   Every screen renders inside one of these, so the design identity carries
   through the payment, waiting and result screens too. */
function DesignShell({ className = '', children }) {
  const { design, designVars } = useT()
  return (
    <div className={`portal-theme bg-background text-on-background ${className}`} style={designVars} data-skin={design.key}>
      {children}
    </div>
  )
}

function Footer() {
  const { t } = useT()
  return (
    <footer className="bg-surface-container-lowest border-t border-surface-variant flex flex-col items-center gap-4 w-full mt-auto p-6">
      <div className="flex gap-6 text-xs font-semibold tracking-wider uppercase text-on-surface-variant">
        <a className="hover:text-primary transition-colors opacity-80 hover:opacity-100" href="#">{t('foot.terms')}</a>
        <a className="hover:text-primary transition-colors opacity-80 hover:opacity-100" href="#">{t('foot.help')}</a>
      </div>
      <p className="text-xs font-semibold tracking-wider text-on-surface-variant">
        © {new Date().getFullYear()} {t('foot.powered')}
      </p>
    </footer>
  )
}

export default function Portal() {
  const [plans, setPlans] = useState([])
  const [screen, setScreen] = useState('plans') // plans | pay | waiting | success | error
  const [selected, setSelected] = useState(null)
  const [phone, setPhone] = useState('')
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState(null) // { code, note } on success
  const [errorMsg, setErrorMsg] = useState('')
  const [plansError, setPlansError] = useState(false)
  const [custom, setCustom] = useState(null)
  const [promo, setPromo] = useState(null)
  const [design, setDesign] = useState('CLASSIC')
  const [loyaltyEnabled, setLoyaltyEnabled] = useState(false)
  // The ISP's own brand for this captive portal (each tenant sets their own).
  const [brand, setBrand] = useState({ name: '', logoUrl: null, headline: '', subheadline: '' })
  const [lang, setLang] = useState('EN')
  const langChosen = useRef(false)
  const pollRef = useRef(null)
  // ?design=KEY lets the admin gallery open a live preview of any design
  // without having to save it first.
  const forcedDesign = useRef(normalizeDesignKey(new URLSearchParams(window.location.search).get('design')))

  function loadPlans() {
    setPlansError(false)
    api('/plans').then(setPlans).catch(() => setPlansError(true))
    api('/custom-plan').then(setCustom).catch(() => {})
    api('/promotion').then(setPromo).catch(() => {})
    api('/portal-settings').then((s) => {
      setDesign(normalizeDesignKey(s.portalTemplate) || 'CLASSIC')
      setLoyaltyEnabled(!!s.loyaltyEnabled)
      setBrand({
        name: s.businessName || '',
        logoUrl: s.logoUrl || null,
        headline: s.headline || '',
        subheadline: s.subheadline || '',
      })
      // Honour the operator's default only until the customer picks for themselves.
      if (!langChosen.current && s.defaultLanguage) setLang(s.defaultLanguage)
    }).catch(() => {})
  }

  const chooseLang = (l) => { langChosen.current = true; setLang(l) }

  useEffect(() => {
    loadPlans()
    return () => clearInterval(pollRef.current)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const stopPoll = () => clearInterval(pollRef.current)

  function choosePlan(plan) {
    setSelected(plan)
    setScreen('pay')
    window.scrollTo(0, 0)
  }

  async function sendStk(e) {
    e.preventDefault()
    setSending(true)
    try {
      const { paymentId } = selected.customMinutes
        ? await api('/payments/stk-push-custom', {
            method: 'POST',
            body: { phoneNumber: normalizePhone(phone), minutes: selected.customMinutes },
          })
        : await api('/payments/stk-push', {
            method: 'POST',
            body: { phoneNumber: normalizePhone(phone), planId: selected.id },
          })
      setScreen('waiting')
      let tries = 0
      pollRef.current = setInterval(async () => {
        tries++
        try {
          const p = await api(`/payments/${paymentId}`)
          if (p.status === 'SUCCESS' && p.voucherCode) {
            stopPoll()
            setResult({ code: p.voucherCode, note: 'Your payment was successful and your connection is ready.' })
            setScreen('success')
          } else if (p.status === 'FAILED') {
            stopPoll()
            setErrorMsg("Payment didn't go through. The M-Pesa request failed or was cancelled.")
            setScreen('error')
          } else if (tries > MAX_POLLS) {
            stopPoll()
            setErrorMsg('We timed out waiting for the payment. If you were charged, contact support below.')
            setScreen('error')
          }
        } catch { /* keep polling */ }
      }, POLL_INTERVAL_MS)
    } catch (err) {
      setErrorMsg(err.message)
      setScreen('error')
    } finally {
      setSending(false)
    }
  }

  // Called by the redeem/verify box when a voucher is activated on the spot.
  function showActivated({ code, note }) {
    setResult({ code, note })
    setScreen('success')
    window.scrollTo(0, 0)
  }

  function backToPlans() {
    stopPoll()
    setScreen('plans')
    window.scrollTo(0, 0)
  }

  let screen_
  if (screen === 'pay') {
    screen_ = (
      <PayScreen
        plan={selected}
        phone={phone}
        setPhone={setPhone}
        sending={sending}
        onSubmit={sendStk}
        onClose={backToPlans}
      />
    )
  } else if (screen === 'waiting') {
    screen_ = <WaitingScreen onCancel={() => { stopPoll(); setScreen('pay') }} />
  } else if (screen === 'success') {
    screen_ = <SuccessScreen code={result.code} note={result.note} onHome={backToPlans} />
  } else if (screen === 'error') {
    screen_ = (
      <ErrorScreen
        message={errorMsg}
        onRetry={() => setScreen(selected ? 'pay' : 'plans')}
        onChoosePlan={backToPlans}
      />
    )
  } else {
    screen_ = (
      <PlansScreen
        plans={plans}
        custom={custom}
        promo={promo}
        loyaltyEnabled={loyaltyEnabled}
        plansError={plansError}
        onRetryPlans={loadPlans}
        onPromoExpire={loadPlans}
        onBuy={choosePlan}
        onActivated={showActivated}
      />
    )
  }

  return (
    <LangContext.Provider value={{ lang, setLang: chooseLang, design: forcedDesign.current || design, brand }}>
      {/* key on the screen name so every step of the flow animates in */}
      <div key={screen} className="screen-enter">
        {screen_}
      </div>
    </LangContext.Provider>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 1 — Welcome / Plans                                          */
/* ------------------------------------------------------------------ */

function promoPrice(price, promo) {
  if (!promo?.active) return null
  return Math.max(1, Math.round((price * (100 - promo.discountPercent)) / 100))
}

// The price a plan really sells for right now, plus the crossed-out one.
function dealFor(plan, promo) {
  const d = promoPrice(plan.price, promo)
  return d != null && d < plan.price ? { price: d, old: plan.price } : { price: plan.price, old: null }
}

function useCountdown(endsAt) {
  const [remaining, setRemaining] = useState(() => new Date(endsAt).getTime() - Date.now())
  useEffect(() => {
    const t = setInterval(() => setRemaining(new Date(endsAt).getTime() - Date.now()), 1000)
    return () => clearInterval(t)
  }, [endsAt])
  return remaining
}

function formatCountdown(ms) {
  if (ms <= 0) return '0s'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (d > 0) return `${d}d ${h}h ${m}m ${sec}s`
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(h)}:${pad(m)}:${pad(sec)}`
}

function PromoBanner({ promo, onExpire }) {
  const { t } = useT()
  const remaining = useCountdown(promo.endsAt)
  const expired = remaining <= 0

  useEffect(() => {
    if (expired) onExpire()
  }, [expired]) // eslint-disable-line react-hooks/exhaustive-deps

  if (expired) return null

  return (
    <div className="rounded-xl bg-gradient-to-r from-[#7a4a06] to-[#c98a12] text-white p-4 flex items-center gap-3 shadow-[0_8px_16px_rgba(180,83,9,0.25)] fade-up">
      <Icon name="celebration" filled className="text-[32px]!" />
      <div className="flex-1 min-w-0">
        <p className="font-bold text-lg leading-tight">{promo.title}</p>
        <p className="text-sm text-white/90 flex items-center gap-1.5 mt-0.5">
          <Icon name="timer" className="text-[16px]!" />
          {t('promo.endsIn')} <span className="font-mono font-bold tabular-nums">{formatCountdown(remaining)}</span>
        </p>
      </div>
      <span className="text-2xl font-bold whitespace-nowrap">-{promo.discountPercent}%</span>
    </div>
  )
}

function PlanCard({ plan, popular, onBuy, index = 0, promo }) {
  const { t } = useT()
  const speed = speedLabel(plan.bandwidth)
  const discounted = promoPrice(plan.price, promo)
  const deviceCount = plan.effectiveMaxDevices || 1
  return (
    <div
      className={`bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] flex flex-col gap-3 relative overflow-hidden fade-up transition-all duration-200 hover:-translate-y-1 hover:shadow-[0_8px_16px_rgba(15,23,42,0.08)] ${
        popular ? 'p-6 border-t-4 border-primary' : 'p-4'
      }`}
      style={{ animationDelay: `${100 + index * 70}ms` }}
    >
      {popular && (
        <div className="absolute top-0 right-0 bg-primary text-on-primary text-xs font-semibold tracking-wider px-3 py-1 rounded-bl-lg">
          {t('card.popular')}
        </div>
      )}
      <div className={`flex justify-between items-center border-b border-surface-container pb-3 ${popular ? 'mt-2' : ''}`}>
        <div>
          <h3 className="text-lg font-semibold text-on-background">{plan.name}</h3>
          <p className="text-sm text-on-surface-variant flex items-center gap-2 flex-wrap">
            {speed && (
              <span className="flex items-center gap-1"><Icon name="speed" className="text-[16px]!" /> {speed}</span>
            )}
            <span className="flex items-center gap-1">
              <Icon name="devices" className="text-[16px]!" />
              {deviceCount} {deviceCount > 1 ? t('card.devices_plural') : t('card.devices')}
            </span>
          </p>
        </div>
        <div className="text-right">
          <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{t('card.price')}</p>
          {discounted != null && discounted < plan.price ? (
            <>
              <p className="font-mono text-sm text-on-surface-variant line-through">KES {plan.price}</p>
              <p className={`font-mono font-semibold text-[#ffd479] ${popular ? 'text-2xl' : 'text-lg'}`}>KES {discounted}</p>
            </>
          ) : (
            <p className={`font-mono font-semibold text-primary ${popular ? 'text-2xl' : 'text-lg'}`}>KES {plan.price}</p>
          )}
        </div>
      </div>
      <button
        onClick={() => onBuy(discounted != null && discounted < plan.price ? { ...plan, price: discounted } : plan)}
        className="w-full h-12 bg-gradient-to-r from-primary to-[#e0aa22] text-on-secondary rounded-xl text-lg font-semibold flex items-center justify-center gap-2 shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-95 transition-all duration-100 cursor-pointer"
      >
        <Icon name="payments" /> {t('card.buy')}
      </button>
    </div>
  )
}

function CustomTimeCard({ custom, promo, onBuy }) {
  const { t } = useT()
  const [minutes, setMinutes] = useState(custom.minMinutes)
  const m = Number(minutes) || 0
  const valid = m >= custom.minMinutes && m <= custom.maxMinutes
  const basePrice = Math.max(1, Math.ceil((custom.pricePerHour * m) / 60))
  const price = promoPrice(basePrice, promo) ?? basePrice
  const speed = speedLabel(custom.bandwidth)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3 fade-up">
        <h2 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant whitespace-nowrap">{t('custom.heading')}</h2>
        <div className="h-px bg-outline-variant/50 flex-1"></div>
      </div>
      <div className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-secondary fade-up flex flex-col gap-4">
        <div className="flex justify-between items-start gap-3">
          <div>
            <h3 className="text-lg font-semibold text-on-background">{t('custom.title')}</h3>
            <p className="text-sm text-on-surface-variant flex items-center gap-1 mt-1">
              {speed && (<><Icon name="speed" className="text-[16px]!" /> {speed} · </>)}
              {t('custom.perHour', { n: custom.pricePerHour })}
            </p>
          </div>
        </div>
        <div className="flex items-end gap-4 flex-wrap">
          <div className="flex-1 min-w-[140px]">
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="custom-minutes">
              {t('custom.minutes')}
            </label>
            <input
              id="custom-minutes"
              type="number"
              min={custom.minMinutes}
              max={custom.maxMinutes}
              value={minutes}
              onChange={(e) => setMinutes(e.target.value)}
              className="w-full h-12 bg-surface-bright border border-outline-variant rounded-xl px-4 text-lg font-semibold tabular-nums text-on-background focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
            />
          </div>
          <div className="text-right pb-1">
            <p className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{t('custom.youPay')}</p>
            {valid && price < basePrice && <p className="font-mono text-sm text-on-surface-variant line-through">KES {basePrice}</p>}
            <p className={`font-mono text-2xl font-bold tabular-nums ${valid ? 'text-primary' : 'text-outline'}`}>
              KES {valid ? price : '—'}
            </p>
          </div>
        </div>
        {!valid && (
          <p className="text-sm text-error">{t('custom.range', { min: custom.minMinutes, max: custom.maxMinutes.toLocaleString() })}</p>
        )}
        <button
          onClick={() => valid && onBuy({ id: 'custom', customMinutes: m, name: `${formatDuration(m)} Custom`, price, bandwidth: custom.bandwidth })}
          disabled={!valid}
          className="w-full h-12 bg-primary text-on-primary rounded-xl text-lg font-semibold flex items-center justify-center gap-2 shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-95 transition-all duration-100 disabled:opacity-50 cursor-pointer"
        >
          <Icon name="payments" /> {t('custom.buy', { dur: valid ? formatDuration(m) : '' })}
        </button>
      </div>
    </div>
  )
}

function RewardsCard() {
  const { t } = useT()
  const [phone, setPhone] = useState('')
  const [bal, setBal] = useState(null) // { points, redeemableMinutes, pointsPerMinute, min, max }
  const [minutes, setMinutes] = useState(0)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  async function check(e) {
    e?.preventDefault()
    setMsg(null); setBal(null); setBusy(true)
    try {
      const b = await api(`/loyalty/${normalizePhone(phone)}`)
      if (!b.enabled) { setMsg({ ok: false, text: t('rewards.unavailable') }); return }
      setBal(b)
      setMinutes(b.minRedeemMinutes)
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  async function redeem() {
    setMsg(null); setBusy(true)
    try {
      const r = await api(`/loyalty/${normalizePhone(phone)}/redeem`, { method: 'POST', body: { minutes } })
      setMsg({ ok: true, text: r.message })
      check() // refresh balance
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally { setBusy(false) }
  }

  const cost = bal ? minutes * bal.pointsPerMinute : 0
  const canRedeem = bal && bal.points >= cost && minutes >= bal.minRedeemMinutes

  return (
    <section className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] mt-3 fade-up" style={{ animationDelay: '450ms' }}>
      <div className="flex items-center gap-2 mb-2">
        <Icon name="loyalty" className="text-primary" />
        <h3 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{t('rewards.title')}</h3>
      </div>
      <form onSubmit={check} className="flex gap-3">
        <input
          type="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder={t('rewards.phone')}
          className="flex-1 min-w-0 h-12 bg-surface-bright border border-outline-variant rounded-xl px-3 text-sm text-on-background focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
        />
        <button type="submit" disabled={busy || !phone.trim()}
          className="h-12 px-6 bg-surface text-primary border border-primary rounded-xl text-base font-semibold hover:bg-surface-container-low transition-colors active:scale-95 cursor-pointer disabled:opacity-40">
          {t('rewards.check')}
        </button>
      </form>

      {bal && (
        <div className="mt-4">
          <p className="text-sm text-on-surface-variant">
            {t('rewards.youHave')} <span className="font-mono font-semibold text-on-background">{bal.points}</span> {t('rewards.points')}{' '}
            <span className="font-mono font-semibold text-on-background">{bal.redeemableMinutes}</span> {t('rewards.freeMin')}
          </p>
          {bal.redeemableMinutes >= bal.minRedeemMinutes ? (
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <div>
                <label className="block text-xs text-on-surface-variant mb-1">{t('rewards.minutesLabel')}</label>
                <input type="number" min={bal.minRedeemMinutes} max={bal.maxRedeemMinutes} step={bal.minRedeemMinutes}
                  value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}
                  className="w-32 h-11 bg-surface-bright border border-outline-variant rounded-xl px-3 text-sm font-mono text-on-background focus:outline-none focus:border-primary" />
              </div>
              <span className="text-xs text-on-surface-variant pb-3">{t('rewards.costs', { n: cost })}</span>
              <button type="button" onClick={redeem} disabled={busy || !canRedeem}
                className="h-11 px-6 bg-primary text-on-primary rounded-xl text-base font-semibold active:scale-95 transition-transform cursor-pointer disabled:opacity-40">
                {t('rewards.redeem')}
              </button>
            </div>
          ) : (
            <p className="text-xs text-on-surface-variant mt-2">
              {t('rewards.needMore', { n: bal.minRedeemMinutes * bal.pointsPerMinute })}
            </p>
          )}
        </div>
      )}
      {msg && <p className={`text-sm mt-3 ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}
    </section>
  )
}

/* Each design is a complete screen of its own; this just dispatches. */
function PlansScreen(props) {
  const { design } = useT()
  switch (design.key) {
    case 'BREEZE': return <BreezePlans {...props} />
    case 'POSTER': return <PosterPlans {...props} />
    case 'MATRIX': return <MatrixPlans {...props} />
    case 'STEPS': return <StepsPlans {...props} />
    case 'NEON': return <NeonPlans {...props} />
    default: return <ClassicPlans {...props} />
  }
}

/* --- Sections every design reuses; the design's tokens restyle them --- */

/* One box for getting online with something you already have: a voucher code,
   an M-Pesa confirmation code, or the whole M-Pesa SMS pasted in. It scans the
   code out of the text, tries it as a voucher first, and — where the operator
   has enabled it — falls back to verifying it as an M-Pesa payment (which
   reconnects an already-claimed code to the time still left on it). */
function VoucherSection({ onActivated, delay = 400 }) {
  const { t } = useT()
  const [input, setInput] = useState('')
  const [codeVerify, setCodeVerify] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null) // { ok, text }

  useEffect(() => {
    api('/portal-settings').then((s) => setCodeVerify(!!s.codeVerifyEnabled)).catch(() => {})
  }, [])

  async function submit(e) {
    e.preventDefault()
    // A pasted M-Pesa SMS begins with the confirmation code; for a bare code
    // this is just the code itself.
    const code = (input.trim().split(/\s+/)[0] || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
    if (!code) return
    setBusy(true)
    setMsg(null)
    try {
      const v = await api(`/vouchers/${code}/activate`, { method: 'POST' })
      onActivated({
        code: v.code,
        note: `Voucher activated — you have ${formatDuration(v.effectiveDurationMinutes || v.plan.durationMinutes)} of internet.`,
      })
      return // navigates to the success screen
    } catch (voucherErr) {
      if (codeVerify) {
        try {
          const r = await api('/payments/verify-code', { method: 'POST', body: { code } })
          setMsg({ ok: r.result === 'CHECKING' || r.result === 'ALREADY_ACTIVE', text: r.message })
        } catch (e2) {
          setMsg({ ok: false, text: e2.message })
        }
      } else {
        setMsg({ ok: false, text: voucherErr.message })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] fade-up" style={{ animationDelay: `${delay}ms` }}>
      <form onSubmit={submit}>
        <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="voucher">
          {t('voucher.label')}
        </label>
        <div className="flex gap-3">
          <input
            id="voucher"
            type="text"
            required
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={t('voucher.placeholder')}
            className="flex-1 min-w-0 h-12 bg-surface-bright border border-outline-variant rounded-xl px-3 text-sm text-on-background focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
          />
          <button
            type="submit"
            disabled={busy || !input.trim()}
            className="h-12 px-6 bg-surface text-primary border border-primary rounded-xl text-lg font-semibold hover:bg-surface-container-low transition-colors active:scale-95 cursor-pointer disabled:opacity-40"
          >
            {busy ? t('voucher.checking') : t('voucher.redeem')}
          </button>
        </div>
        {msg && <p className={`text-sm mt-2 ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}
      </form>
      <div className="mt-4 pt-4 border-t border-outline-variant">
        <RecoverBox compact />
      </div>
    </section>
  )
}

/* "I paid but wasn't connected." Enters the paying number, triggers an
   on-demand reconcile on the backend, and the code is texted to that number —
   never shown here, so nobody can pull someone else's voucher. Reused on the
   plans screen and the error screen. */
function RecoverBox({ compact = false }) {
  const { t } = useT()
  const [phone, setPhone] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState(null)

  async function submit(e) {
    e.preventDefault()
    setBusy(true)
    setMsg(null)
    try {
      const r = await api('/payments/recover', { method: 'POST', body: { phoneNumber: normalizePhone(phone) } })
      setMsg({ ok: r.result === 'SENT', text: r.message })
    } catch (err) {
      setMsg({ ok: false, text: err.message })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className={compact ? '' : 'bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)]'}>
      <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-1" htmlFor="recover-phone">
        {t('recover.q')}
      </label>
      <p className="text-xs text-on-surface-variant mb-2">{t('recover.hint')}</p>
      <div className="flex gap-3">
        <input
          id="recover-phone"
          type="tel"
          required
          value={phone}
          onChange={(e) => setPhone(e.target.value.replace(/[^\d ]/g, ''))}
          placeholder="0712 345 678"
          className="flex-1 min-w-0 h-12 bg-surface-bright border border-outline-variant rounded-xl px-3 text-sm text-on-background focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
        />
        <button
          type="submit"
          disabled={busy || !phone.trim()}
          className="h-12 px-5 bg-surface text-primary border border-primary rounded-xl text-sm font-semibold hover:bg-surface-container-low transition-colors active:scale-95 cursor-pointer disabled:opacity-40 whitespace-nowrap"
        >
          {busy ? t('recover.sending') : t('recover.btn')}
        </button>
      </div>
      {msg && <p className={`text-sm mt-2 ${msg.ok ? 'text-primary' : 'text-error'}`}>{msg.text}</p>}
    </form>
  )
}


function PlansFallback({ plans, plansError, onRetryPlans }) {
  const { t } = useT()
  // Only speak up when there is nothing to show — a failed background
  // refresh while plans are already on screen shouldn't scare anyone.
  if (plans.length > 0) return null
  if (!plansError) {
    return (
      <div className="flex flex-col gap-4">
        {[0, 1, 2].map((i) => (
          <div key={i} className="animate-pulse bg-surface-container-high rounded-xl h-36" style={{ animationDelay: `${i * 150}ms` }}></div>
        ))}
      </div>
    )
  }
  if (plansError) {
    return (
      <div className="bg-surface-container-lowest rounded-xl p-6 shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-t-4 border-error flex flex-col items-center text-center gap-3">
        <Icon name="cloud_off" className="text-error text-[32px]!" />
        <p className="text-base text-on-surface-variant">{t('plans.offline')}</p>
        <button
          onClick={onRetryPlans}
          className="h-12 px-6 bg-primary text-on-primary rounded-xl text-lg font-semibold flex items-center gap-2 active:scale-95 transition-transform cursor-pointer"
        >
          <Icon name="refresh" /> {t('plans.retry')}
        </button>
      </div>
    )
  }
  return null
}

/* Bottom nav used by the darker, app-like designs. */
function MobileNav() {
  const { t } = useT()
  return (
    <nav className="md:hidden bg-surface fixed bottom-0 w-full z-50 shadow-[0_-4px_12px_rgba(15,23,42,0.05)] flex justify-around items-center h-20 px-2">
      <button
        onClick={() => document.getElementById('voucher')?.focus()}
        className="flex flex-col items-center justify-center text-on-surface-variant px-4 py-1.5 rounded-xl group transition-colors w-20 cursor-pointer"
      >
        <Icon name="wifi_tethering" className="mb-1 group-hover:text-primary transition-colors" />
        <span className="text-xs font-semibold tracking-wider group-hover:text-primary transition-colors">{t('nav.connect')}</span>
      </button>
      <button className="flex flex-col items-center justify-center bg-secondary-container text-on-secondary-container rounded-xl px-4 py-1.5 w-20 cursor-pointer">
        <Icon name="payments" filled className="mb-1" />
        <span className="text-xs font-semibold tracking-wider">{t('nav.plans')}</span>
      </button>
      <a
        href="tel:+254700000000"
        className="flex flex-col items-center justify-center text-on-surface-variant px-4 py-1.5 rounded-xl group transition-colors w-20"
      >
        <Icon name="support_agent" className="mb-1 group-hover:text-primary transition-colors" />
        <span className="text-xs font-semibold tracking-wider group-hover:text-primary transition-colors">{t('nav.help')}</span>
      </a>
    </nav>
  )
}

/* --- Design: Signature (CLASSIC) — black canvas, amber accent, photo hero --- */

function ClassicPlans({ plans, custom, promo, loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, onActivated }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="bg-surface flex items-center justify-between px-5 h-16 w-full border-b border-outline-variant sticky top-0 z-40">
        <Brand />
        <LangToggle />
      </header>

      <main className="flex-1 w-full max-w-lg mx-auto pb-24 px-5 pt-6 flex flex-col gap-6">
        <section className="relative rounded-xl overflow-hidden shadow-[0_8px_16px_rgba(15,23,42,0.08)] fade-up">
          <img src={heroCity} alt="" className="absolute inset-0 w-full h-full object-cover" />
          <div className="absolute inset-0 bg-gradient-to-r from-black/95 via-black/80 to-primary/25"></div>
          <div className="relative z-10 p-6 py-8 md:py-10 flex items-center gap-6">
            <div className="flex-1">
              <div className="w-14 h-14 rounded-full bg-white/10 backdrop-blur flex items-center justify-center mb-4 border border-white/20">
                <Icon name="wifi" filled className="text-primary-fixed text-[28px]!" />
              </div>
              <h1 className="text-3xl md:text-4xl font-bold tracking-tight text-white"><HeroTitle /></h1>
              <p className="text-base text-white/80 mt-2 max-w-sm"><HeroSub /></p>
            </div>
            <img
              src={customerPhoto}
              alt="Customer browsing online"
              className="hidden md:block w-36 h-48 object-cover rounded-xl border-2 border-white/20 shadow-lg"
            />
          </div>
        </section>

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        <section className="flex flex-col gap-6">
          {PLAN_GROUPS.map((group) => {
            const groupPlans = plans.filter((p) => planGroup(p.durationMinutes) === group)
            if (!groupPlans.length) return null
            return (
              <div key={group} className="flex flex-col gap-4">
                <div className="flex items-center gap-3 fade-up">
                  <h2 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant whitespace-nowrap">
                    {t('group.' + group)}
                  </h2>
                  <div className="h-px bg-outline-variant/50 flex-1"></div>
                </div>
                <div className="flex flex-col gap-4">
                  {groupPlans.map((p) => (
                    <PlanCard
                      key={p.id}
                      plan={p}
                      popular={p.durationMinutes === 1440}
                      onBuy={onBuy}
                      index={plans.indexOf(p)}
                      promo={promo}
                    />
                  ))}
                </div>
              </div>
            )
          })}
          {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}
          <PlansFallback plans={plans} plansError={plansError} onRetryPlans={onRetryPlans} />
        </section>

        <div className="mt-3">
          <VoucherSection onActivated={onActivated} />
        </div>

        {loyaltyEnabled && <RewardsCard />}
      </main>

      <div className="hidden md:block w-full"><Footer /></div>
      <MobileNav />
    </DesignShell>
  )
}

/* --- Design: Breeze (BREEZE) — light, airy, tabs + list rows, voucher first --- */

function BreezePlans({ plans, custom, promo, loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, onActivated }) {
  const { t } = useT()
  const [tab, setTab] = useState('All')
  const groups = PLAN_GROUPS.filter((g) => plans.some((p) => planGroup(p.durationMinutes) === g))
  const shown = tab === 'All' ? plans : plans.filter((p) => planGroup(p.durationMinutes) === tab)
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="flex items-center justify-between px-5 h-16 w-full max-w-lg mx-auto">
        <Brand />
        <LangToggle />
      </header>

      <main className="flex-1 w-full max-w-lg mx-auto pb-16 px-5 flex flex-col gap-5">
        <section className="fade-up bg-surface rounded-3xl border border-outline-variant p-6 text-center shadow-[0_4px_12px_rgba(15,23,42,0.04)]">
          <div className="w-12 h-12 mx-auto rounded-full bg-primary/10 flex items-center justify-center mb-3">
            <Icon name="wifi" filled className="text-primary text-[24px]!" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight">{t('breeze.hi')}</h1>
          <p className="text-sm text-on-surface-variant mt-1">{t('breeze.sub')}</p>
        </section>

        <VoucherSection onActivated={onActivated} delay={100} />

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        {groups.length > 1 && (
          <div className="flex gap-2 overflow-x-auto fade-up" style={{ animationDelay: '150ms' }}>
            {['All', ...groups].map((g) => {
              const active = tab === g
              return (
                <button key={g} type="button" onClick={() => setTab(g)}
                  className={`px-4 h-9 rounded-full text-sm font-semibold whitespace-nowrap transition-colors cursor-pointer ${
                    active ? 'bg-primary text-on-primary' : 'bg-surface border border-outline-variant text-on-surface-variant hover:border-primary'
                  }`}>
                  {g === 'All' ? t('tab.all') : t('group.' + g)}
                </button>
              )
            })}
          </div>
        )}

        <section className="flex flex-col gap-3">
          {shown.map((p, i) => (
            <BreezeRow key={p.id} plan={p} promo={promo} onBuy={onBuy} index={i} />
          ))}
          {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}
          <PlansFallback plans={plans} plansError={plansError} onRetryPlans={onRetryPlans} />
        </section>

        {loyaltyEnabled && <RewardsCard />}
      </main>

      <Footer />
    </DesignShell>
  )
}

function BreezeRow({ plan, promo, onBuy, index = 0 }) {
  const { t } = useT()
  const deal = dealFor(plan, promo)
  const speed = speedLabel(plan.bandwidth)
  return (
    <button
      type="button"
      onClick={() => onBuy(deal.old ? { ...plan, price: deal.price } : plan)}
      className="w-full bg-surface rounded-2xl border border-outline-variant p-4 flex items-center gap-3 text-left hover:border-primary hover:shadow-[0_6px_14px_rgba(15,23,42,0.06)] transition-all cursor-pointer fade-up"
      style={{ animationDelay: `${80 + index * 50}ms` }}
    >
      <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center shrink-0">
        <Icon name="bolt" filled className="text-primary text-[20px]!" />
      </div>
      <div className="flex-1 min-w-0">
        <p className="font-semibold text-on-background truncate">{plan.name}</p>
        <p className="text-xs text-on-surface-variant">{formatDuration(plan.durationMinutes)}{speed ? ` · ${speed}` : ''}</p>
      </div>
      <div className="text-right">
        {deal.old && <p className="text-xs text-on-surface-variant line-through">KES {deal.old}</p>}
        <p className="font-mono font-bold text-primary">KES {deal.price}</p>
      </div>
      <span className="h-9 px-4 rounded-full bg-primary text-on-primary text-sm font-semibold flex items-center">{t('card.buyShort')}</span>
    </button>
  )
}

/* --- Design: Market Poster (POSTER) — cream paper, serif display, price tags --- */

function PosterPlans({ plans, custom, promo, loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, onActivated }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="flex items-center justify-between px-5 h-16 w-full max-w-lg mx-auto">
        <span className="text-xl font-bold tracking-tight" style={{ fontFamily: 'var(--portal-heading-font)' }}><BrandName /></span>
        <LangToggle />
      </header>

      <main className="flex-1 w-full max-w-lg mx-auto pb-16 px-5 flex flex-col gap-6">
        <section className="fade-up text-center border-y-4 border-double border-on-background/70 py-6">
          <p className="text-xs font-bold tracking-[0.3em] uppercase text-secondary mb-2">
            <Icon name="wifi" filled className="text-[14px]! align-middle mr-1" /><BrandName />
          </p>
          <h1 className="text-4xl md:text-5xl font-bold leading-tight"><HeroTitle /></h1>
          <p className="text-xs text-on-surface-variant mt-3 uppercase tracking-[0.2em]">{t('poster.tag')}</p>
        </section>

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        <section className="grid grid-cols-2 gap-4 px-1">
          {plans.map((p, i) => (
            <PosterTag key={p.id} plan={p} promo={promo} onBuy={onBuy} index={i} />
          ))}
        </section>
        <PlansFallback plans={plans} plansError={plansError} onRetryPlans={onRetryPlans} />
        {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}

        <VoucherSection onActivated={onActivated} delay={200} />
        {loyaltyEnabled && <RewardsCard />}
      </main>

      <Footer />
    </DesignShell>
  )
}

function PosterTag({ plan, promo, onBuy, index = 0 }) {
  const { t } = useT()
  const deal = dealFor(plan, promo)
  return (
    <button
      type="button"
      onClick={() => onBuy(deal.old ? { ...plan, price: deal.price } : plan)}
      className={`bg-surface border-2 border-on-background p-4 flex flex-col items-center text-center gap-1 shadow-[5px_5px_0_var(--color-primary)] hover:shadow-[7px_7px_0_var(--color-primary)] hover:-translate-y-0.5 transition-all cursor-pointer fade-up ${index % 2 ? 'rotate-[0.6deg]' : 'rotate-[-0.6deg]'}`}
      style={{ animationDelay: `${80 + index * 60}ms` }}
    >
      <p className="text-[11px] font-bold uppercase tracking-[0.18em] text-on-surface-variant w-full truncate">{plan.name}</p>
      {deal.old && <p className="text-xs text-on-surface-variant line-through">KES {deal.old}</p>}
      <p className="text-3xl font-bold text-primary" style={{ fontFamily: 'var(--portal-heading-font)' }}>
        {deal.price}<span className="text-sm align-top ml-0.5">KES</span>
      </p>
      <p className="text-xs text-on-surface-variant">{formatDuration(plan.durationMinutes)}</p>
      <span className="mt-2 px-4 h-8 flex items-center bg-on-background text-background text-xs font-bold uppercase tracking-widest">{t('card.buyShort')}</span>
    </button>
  )
}

/* --- Design: Compact Grid (MATRIX) — every plan on screen as a small tile --- */

function MatrixPlans({ plans, custom, promo, loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, onActivated }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="bg-surface flex items-center justify-between px-5 h-16 w-full border-b border-outline-variant sticky top-0 z-40">
        <Brand />
        <div className="flex items-center gap-2">
          <a href={`tel:${SUPPORT_PHONE.replace(/\s/g, '')}`}
            className="hidden sm:flex items-center gap-1.5 text-xs font-semibold text-on-surface-variant border border-outline-variant rounded-full px-3 py-1.5 hover:text-primary transition-colors">
            <Icon name="support_agent" className="text-[16px]!" /> {SUPPORT_PHONE}
          </a>
          <LangToggle />
        </div>
      </header>

      <main className="flex-1 w-full max-w-2xl mx-auto pb-24 px-4 pt-5 flex flex-col gap-4">
        <div className="fade-up flex items-center gap-2 text-sm text-on-surface-variant">
          <Icon name="grid_view" className="text-primary text-[18px]!" />
          {t('matrix.hint')}
        </div>

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        <section className="grid grid-cols-3 sm:grid-cols-4 gap-2">
          {plans.map((p, i) => (
            <MatrixTile key={p.id} plan={p} promo={promo} onBuy={onBuy} index={i} />
          ))}
        </section>
        <PlansFallback plans={plans} plansError={plansError} onRetryPlans={onRetryPlans} />
        {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}

        <VoucherSection onActivated={onActivated} delay={150} />
        {loyaltyEnabled && <RewardsCard />}
      </main>

      <div className="hidden md:block w-full"><Footer /></div>
      <MobileNav />
    </DesignShell>
  )
}

function MatrixTile({ plan, promo, onBuy, index = 0 }) {
  const deal = dealFor(plan, promo)
  return (
    <button
      type="button"
      onClick={() => onBuy(deal.old ? { ...plan, price: deal.price } : plan)}
      className="bg-surface-container-low border border-outline-variant rounded-lg p-2.5 flex flex-col items-center text-center gap-0.5 hover:border-primary hover:bg-surface-container transition-colors cursor-pointer fade-up"
      style={{ animationDelay: `${40 + index * 30}ms` }}
    >
      <p className="text-sm font-bold text-on-background leading-tight">{formatDuration(plan.durationMinutes)}</p>
      <p className="font-mono text-primary font-semibold text-sm">
        {deal.old && <span className="text-[10px] text-on-surface-variant line-through mr-1">{deal.old}</span>}
        KES {deal.price}
      </p>
      <p className="text-[10px] text-on-surface-variant truncate w-full">{plan.name}</p>
    </button>
  )
}

/* --- Design: Step-by-Step (STEPS) — numbered how-to first, then simple rows --- */

function StepsPlans({ plans, custom, promo, loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, onActivated }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="bg-surface border-b border-outline-variant flex items-center justify-between px-5 h-16 w-full sticky top-0 z-40">
        <Brand />
        <LangToggle />
      </header>

      <main className="flex-1 w-full max-w-lg mx-auto pb-16 px-5 pt-6 flex flex-col gap-5">
        <section className="fade-up bg-surface rounded-xl border border-outline-variant p-5 shadow-[0_4px_12px_rgba(15,23,42,0.04)]">
          <h1 className="text-lg font-bold mb-4 flex items-center gap-2">
            <Icon name="checklist" className="text-primary" /> {t('steps.heading')}
          </h1>
          <ol className="flex flex-col gap-3">
            {[1, 2, 3, 4].map((n) => (
              <li key={n} className="flex items-start gap-3">
                <span className="w-6 h-6 rounded-full bg-primary/10 text-primary text-xs font-bold flex items-center justify-center shrink-0 mt-0.5">{n}</span>
                <span className="text-sm text-on-surface-variant">{t('steps.' + n)}</span>
              </li>
            ))}
          </ol>
        </section>

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        <section className="flex flex-col gap-3">
          <h2 className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant fade-up">{t('steps.plans')}</h2>
          {plans.map((p, i) => (
            <StepsRow key={p.id} plan={p} promo={promo} onBuy={onBuy} index={i} />
          ))}
          {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}
          <PlansFallback plans={plans} plansError={plansError} onRetryPlans={onRetryPlans} />
        </section>

        <VoucherSection onActivated={onActivated} delay={250} />
        {loyaltyEnabled && <RewardsCard />}
      </main>

      <Footer />
    </DesignShell>
  )
}

function StepsRow({ plan, promo, onBuy, index = 0 }) {
  const { t } = useT()
  const deal = dealFor(plan, promo)
  const speed = speedLabel(plan.bandwidth)
  return (
    <div className="bg-surface rounded-xl border border-outline-variant p-4 flex items-center gap-3 fade-up" style={{ animationDelay: `${60 + index * 50}ms` }}>
      <div className="flex-1 min-w-0">
        <p className="font-semibold truncate">{plan.name}</p>
        <p className="text-xs text-on-surface-variant">{formatDuration(plan.durationMinutes)}{speed ? ` · ${speed}` : ''}</p>
        <p className="font-mono text-sm font-bold text-on-background mt-1">
          {deal.old && <span className="text-xs text-on-surface-variant line-through mr-1.5">KES {deal.old}</span>}
          KES {deal.price}
        </p>
      </div>
      <button
        type="button"
        onClick={() => onBuy(deal.old ? { ...plan, price: deal.price } : plan)}
        className="h-10 px-5 rounded-lg border-2 border-primary text-primary text-sm font-bold hover:bg-primary hover:text-on-primary transition-colors cursor-pointer"
      >
        {t('card.buyShort')}
      </button>
    </div>
  )
}

/* --- Design: Terminal (NEON) — black, monospace, command-line menu --- */

function NeonPlans({ plans, custom, promo, loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, onActivated }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="border-b border-outline-variant bg-surface sticky top-0 z-40">
        <div className="max-w-lg mx-auto px-5 h-12 flex items-center gap-2">
          <span className="w-2.5 h-2.5 rounded-full bg-error/80"></span>
          <span className="w-2.5 h-2.5 rounded-full bg-primary/40"></span>
          <span className="w-2.5 h-2.5 rounded-full bg-primary"></span>
          <span className="flex-1 text-center text-xs tracking-[0.25em] uppercase text-on-surface-variant"><BrandName /></span>
          <LangToggle />
        </div>
      </header>

      <main className="flex-1 w-full max-w-lg mx-auto pb-16 px-5 pt-6 flex flex-col gap-5">
        <section className="fade-up">
          <p className="text-xs text-primary mb-2">&gt; {t('neon.online')}</p>
          <h1 className="text-2xl font-bold tracking-tight">{t('hero.minTitle')}</h1>
          <p className="text-sm text-on-surface-variant mt-1">&gt; {t('neon.select')}<span className="cursor-blink text-primary">_</span></p>
        </section>

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        {plans.length > 0 ? (
          <section className="border border-outline-variant rounded-lg bg-surface divide-y divide-dashed divide-outline-variant">
            {plans.map((p, i) => (
              <NeonRow key={p.id} plan={p} promo={promo} onBuy={onBuy} index={i} />
            ))}
          </section>
        ) : (
          <PlansFallback plans={plans} plansError={plansError} onRetryPlans={onRetryPlans} />
        )}
        {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}

        <VoucherSection onActivated={onActivated} delay={200} />
        {loyaltyEnabled && <RewardsCard />}
      </main>

      <Footer />
    </DesignShell>
  )
}

function NeonRow({ plan, promo, onBuy, index = 0 }) {
  const { t } = useT()
  const deal = dealFor(plan, promo)
  return (
    <button
      type="button"
      onClick={() => onBuy(deal.old ? { ...plan, price: deal.price } : plan)}
      className="w-full p-4 flex items-center gap-3 text-left hover:bg-surface-container-low transition-colors cursor-pointer fade-up"
      style={{ animationDelay: `${50 + index * 40}ms` }}
    >
      <span className="text-primary shrink-0">&gt;</span>
      <span className="flex-1 min-w-0">
        <span className="block font-semibold truncate">{plan.name}</span>
        <span className="block text-xs text-on-surface-variant">{formatDuration(plan.durationMinutes)}</span>
      </span>
      {deal.old && <span className="text-xs text-on-surface-variant line-through">{deal.old}</span>}
      <span className="text-primary font-semibold whitespace-nowrap">[ KES {deal.price} ]</span>
      <span className="border border-primary text-primary text-xs font-bold px-2.5 py-1.5 rounded">{t('card.buyShort').toUpperCase()}</span>
    </button>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 2 — Payment (phone number entry)                             */
/* ------------------------------------------------------------------ */

/**
 * The way out when the M-Pesa prompt doesn't work — a flat battery on the
 * prompt, a SIM that won't take it, a customer who simply prefers the M-Pesa
 * menu. They pay the paybill by hand using the account number shown here, and
 * the pass issues itself; this panel watches for that and puts the code on
 * screen without them having to go and read their SMS.
 */
function PayBillPanel({ plan }) {
  const [info, setInfo] = useState(null)
  const [activated, setActivated] = useState(null)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    // The MikroTik hotspot login page passes the device's MAC through; with it
    // the payment is tied to this exact device, without it we fall back to
    // matching on the paying phone number.
    const q = new URLSearchParams()
    if (params.get('mac')) q.set('mac', params.get('mac'))
    if (params.get('router')) q.set('router', params.get('router'))
    api(`/paybill/instructions${q.toString() ? `?${q}` : ''}`)
      .then(setInfo)
      .catch(() => setInfo(null))
  }, [])

  useEffect(() => {
    if (!info?.payCode || activated) return undefined
    const id = setInterval(() => {
      api(`/paybill/status/${info.payCode}`)
        .then((s) => { if (s.activated) setActivated(s.voucherCode) })
        .catch(() => { /* keep waiting */ })
    }, 5000)
    return () => clearInterval(id)
  }, [info, activated])

  if (!info?.enabled || !(info.paybillNumber || info.tillNumber)) return null

  if (activated) {
    return (
      <div className="w-full mt-8 p-4 rounded-xl border border-primary/40 bg-primary/5">
        <p className="text-sm font-semibold flex items-center gap-1.5">
          <Icon name="check_circle" className="text-primary text-[18px]!" /> Payment received
        </p>
        <p className="mt-1 text-sm">Your access code is <span className="font-mono font-bold text-lg">{activated}</span></p>
        <p className="mt-1 text-xs text-on-surface-variant">Use it as both the WiFi username and password.</p>
      </div>
    )
  }

  return (
    <details className="w-full mt-8 rounded-xl border border-outline-variant overflow-hidden">
      <summary className="px-4 py-3 text-sm font-semibold cursor-pointer select-none flex items-center gap-2">
        <Icon name="account_balance" className="text-[18px]! text-on-surface-variant" />
        No prompt? Pay from the M-Pesa menu
      </summary>
      <div className="px-4 pb-4 text-sm">
        <ol className="space-y-2 text-on-surface-variant">
          <li>1. Open M-Pesa → Lipa na M-Pesa → {info.paybillNumber ? 'Pay Bill' : 'Buy Goods'}</li>
          <li>
            2. {info.paybillNumber ? 'Business number' : 'Till number'}:{' '}
            <span className="font-mono font-bold text-on-surface">{info.paybillNumber || info.tillNumber}</span>
          </li>
          {info.payCode && (
            <li>
              3. Account number: <span className="font-mono font-bold text-on-surface tracking-widest">{info.payCode}</span>
            </li>
          )}
          <li>{info.payCode ? '4' : '3'}. Amount: <span className="font-mono font-bold text-on-surface">KES {plan.price}</span></li>
        </ol>
        <p className="mt-3 text-xs text-on-surface-variant">
          {info.autoLogin
            ? 'Your internet starts by itself the moment the money lands — nothing else to do.'
            : 'Your access code arrives by SMS the moment the money lands, and will appear here too.'}
        </p>
      </div>
    </details>
  )
}

function PayScreen({ plan, phone, setPhone, sending, onSubmit, onClose }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="bg-surface border-b border-outline-variant flex items-center justify-between px-5 h-16 w-full sticky top-0 z-50">
        <Brand />
        <button
          aria-label="Close payment"
          onClick={onClose}
          className="text-on-surface-variant hover:bg-surface-container-low p-2 rounded-full transition-colors active:scale-95 duration-100 cursor-pointer"
        >
          <Icon name="close" className="text-[24px]!" />
        </button>
      </header>

      <main className="flex-grow flex flex-col items-center px-6 py-10 w-full max-w-md mx-auto">
        <p className="text-[11px] font-semibold tracking-[0.3em] uppercase text-on-surface-variant mb-3 fade-up">{t('pay.checkout')}</p>
        <h1 className="text-3xl font-bold text-center tracking-tight mb-10 fade-up" style={{ animationDelay: '80ms' }}>{t('pay.complete')}</h1>

        {/* A quiet receipt: item, rule, total. Nothing boxed, nothing shouting. */}
        <div className="w-full mb-8 fade-up" style={{ animationDelay: '140ms' }}>
          <div className="flex justify-between items-baseline pb-3 gap-4">
            <span className="text-base font-semibold min-w-0 truncate">{t('pay.access', { name: plan.name })}</span>
            <span className="font-mono text-base tabular-nums text-on-surface-variant whitespace-nowrap">KES {plan.price}</span>
          </div>
          <div className="h-px bg-outline-variant"></div>
          <div className="flex justify-between items-baseline pt-3">
            <span className="text-xs font-semibold tracking-[0.2em] uppercase text-on-surface-variant">{t('pay.total')}</span>
            <span className="font-mono text-2xl font-bold tabular-nums text-primary">KES {plan.price}</span>
          </div>
        </div>

        <form className="w-full" onSubmit={onSubmit}>
          <div className="w-full mb-6 fade-up" style={{ animationDelay: '200ms' }}>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="mpesa-phone">
              {t('pay.phone')}
            </label>
            <div className="relative flex items-center">
              <span className="absolute left-4 text-lg font-semibold text-on-surface-variant">+254</span>
              <input
                id="mpesa-phone"
                name="phone"
                type="tel"
                autoComplete="tel"
                required
                pattern="0?[17]\d{8}"
                title="e.g. 712 345 678"
                value={phone}
                onChange={(e) => setPhone(e.target.value.replace(/[^\d ]/g, ''))}
                placeholder="712 345 678"
                className="w-full bg-surface text-on-background text-lg font-semibold rounded-lg border border-outline-variant pl-[72px] pr-4 h-12 focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
              />
            </div>
          </div>

          <p className="w-full flex items-start gap-2 text-xs text-on-surface-variant mb-8 fade-up" style={{ animationDelay: '260ms' }}>
            <Icon name="lock" className="text-primary text-[15px]! mt-px" />
            {t('pay.info')}
          </p>

          <button
            type="submit"
            disabled={sending}
            className="w-full bg-primary text-on-primary text-lg font-semibold h-13 rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 disabled:opacity-60 cursor-pointer fade-up"
            style={{ animationDelay: '300ms' }}
          >
            <Icon name={sending ? 'progress_activity' : 'send_money'} className={sending ? 'animate-spin' : ''} />
            {sending ? t('pay.sending') : t('pay.payNow', { n: plan.price })}
          </button>
        </form>

        <PayBillPanel plan={plan} />
      </main>

      <Footer />
    </DesignShell>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 3 — Waiting for M-Pesa PIN                                   */
/* ------------------------------------------------------------------ */

/* Every design owns its waiting experience; this just dispatches. */
function WaitingScreen(props) {
  const { design } = useT()
  switch (design.key) {
    case 'BREEZE': return <BreezeWaiting {...props} />
    case 'POSTER': return <PosterWaiting {...props} />
    case 'MATRIX': return <MatrixWaiting {...props} />
    case 'STEPS': return <StepsWaiting {...props} />
    case 'NEON': return <NeonWaiting {...props} />
    default: return <ClassicWaiting {...props} />
  }
}

// A visible clock reassures people the page is alive while M-Pesa thinks.
function useElapsed() {
  const [elapsed, setElapsed] = useState(0)
  useEffect(() => {
    const i = setInterval(() => setElapsed((s) => s + 1), 1000)
    return () => clearInterval(i)
  }, [])
  return `${Math.floor(elapsed / 60)}:${String(elapsed % 60).padStart(2, '0')}`
}

/* Signature: a slow gold halo, a rotating arc, one big clock. Luxury calm. */
function ClassicWaiting({ onCancel }) {
  const { t } = useT()
  const clock = useElapsed()
  return (
    <DesignShell className="min-h-screen flex flex-col items-center justify-center">
      <main className="w-full max-w-md px-6 py-10 flex flex-col items-center text-center">
        <p className="text-[11px] font-semibold tracking-[0.35em] uppercase text-on-surface-variant mb-12"><BrandName /></p>

        <div className="relative w-48 h-48 mb-12">
          <div className="halo-pulse absolute inset-0 rounded-full border border-primary/25"></div>
          <div className="halo-pulse absolute inset-5 rounded-full border border-primary/15" style={{ animationDelay: '1.3s' }}></div>
          <div
            className="spin-slow absolute inset-0 rounded-full"
            style={{
              background: 'conic-gradient(from 0deg, transparent 0 72%, var(--color-primary) 96%, transparent 100%)',
              WebkitMask: 'radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 2px))',
              mask: 'radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 2px))',
            }}
          ></div>
          <div className="absolute inset-[24%] rounded-full bg-surface-container-low border border-outline-variant flex items-center justify-center">
            <Icon name="smartphone" className="text-primary text-[36px]!" />
          </div>
        </div>

        <h1 className="text-2xl font-bold tracking-tight mb-2">{t('wait.title')}</h1>
        <p className="text-sm text-on-surface-variant mb-8">{t('wait.sub')}</p>
        <p className="font-mono text-4xl font-semibold tabular-nums text-primary mb-10">{clock}</p>

        <div className="flex items-center justify-center gap-4 text-[11px] font-semibold tracking-[0.18em] uppercase mb-14">
          <span className="text-primary flex items-center gap-1.5"><Icon name="check" className="text-[14px]!" />{t('wait.sent')}</span>
          <span className="w-8 h-px bg-outline-variant"></span>
          <span className="text-on-background flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-primary animate-ping"></span>{t('wait.pin')}
          </span>
        </div>

        <button onClick={onCancel} className="text-xs font-semibold tracking-[0.2em] uppercase text-on-surface-variant hover:text-primary transition-colors cursor-pointer">
          {t('wait.cancel')}
        </button>
      </main>
    </DesignShell>
  )
}

/* Breeze: a phone card floating over a breathing glow. Spa-calm. */
function BreezeWaiting({ onCancel }) {
  const { t } = useT()
  const clock = useElapsed()
  return (
    <DesignShell className="min-h-screen flex flex-col items-center justify-center">
      <main className="w-full max-w-md px-5 py-8 flex flex-col items-center">
        <div className="mb-10"><Brand /></div>

        <div className="relative w-52 h-52 flex items-center justify-center mb-8">
          <div className="breathe absolute inset-0 rounded-full" style={{ background: 'radial-gradient(circle, var(--portal-glow1), transparent 65%)' }}></div>
          <div className="float-y relative z-10 w-28 h-40 bg-surface rounded-[20px] border border-outline-variant shadow-[0_18px_40px_rgba(15,23,42,0.12)] flex flex-col items-center px-2.5 pt-2.5 pb-3">
            <div className="w-9 h-1 rounded-full bg-outline-variant mb-2 shrink-0"></div>
            <div className="flex-1 w-full rounded-xl bg-surface-container-low flex flex-col items-center justify-center gap-1.5 px-2">
              <span className="text-[9px] font-bold tracking-widest text-primary">M-PESA</span>
              <div className="w-full h-1 rounded bg-outline-variant"></div>
              <div className="w-3/4 h-1 rounded bg-outline-variant"></div>
              <div className="flex gap-1.5 mt-1.5">
                {[0, 1, 2, 3].map((i) => (
                  <span key={i} className="pin-dot w-2 h-2 rounded-full bg-primary" style={{ animationDelay: `${i * 0.35}s` }} />
                ))}
              </div>
            </div>
          </div>
        </div>

        <h1 className="text-2xl font-bold tracking-tight mb-1 text-center">{t('wait.title')}</h1>
        <p className="text-sm text-on-surface-variant mb-8 text-center">{t('wait.sub')}</p>

        <div className="w-full max-w-xs">
          <div className="w-full h-1.5 rounded-full bg-surface-container-high overflow-hidden">
            <div className="shimmer-bar h-full w-1/3 rounded-full bg-primary"></div>
          </div>
          <p className="text-xs text-on-surface-variant text-center mt-2 font-mono tabular-nums">{clock}</p>
        </div>

        <button onClick={onCancel} className="mt-10 h-11 px-8 rounded-full border border-outline-variant text-on-surface-variant text-sm font-semibold hover:border-primary hover:text-primary transition-colors cursor-pointer">
          {t('wait.cancel')}
        </button>
      </main>
    </DesignShell>
  )
}

/* Market Poster: your pass is being printed, easing out of the slot. */
function PosterWaiting({ onCancel }) {
  const { t } = useT()
  const clock = useElapsed()
  return (
    <DesignShell className="min-h-screen flex flex-col items-center justify-center">
      <main className="w-full max-w-sm px-6 py-10 flex flex-col items-center text-center">
        <span className="text-xl font-bold mb-10" style={{ fontFamily: 'var(--portal-heading-font)' }}><BrandName /></span>

        <div className="relative z-10 w-60 h-3.5 rounded-full bg-on-background shadow-md"></div>
        <div className="ticket-out w-52">
          <div className="border-2 border-dashed border-on-background/60 border-t-0 bg-surface px-4 pt-7 pb-6 text-center">
            <p className="text-[10px] font-bold tracking-[0.3em] uppercase text-on-surface-variant mb-3">M-PESA</p>
            <h1 className="text-xl font-bold leading-snug mb-1">{t('wait.printing')}…</h1>
            <p className="text-[11px] text-on-surface-variant">{t('wait.pin')}</p>
            <div className="border-t border-dashed border-on-background/40 my-4"></div>
            <p className="font-mono text-2xl font-bold tabular-nums">{clock}</p>
            <div className="mt-3 flex justify-center gap-1.5">
              {[0, 1, 2, 3].map((i) => (
                <span key={i} className="pin-dot w-2 h-2 rounded-full bg-primary" style={{ animationDelay: `${i * 0.35}s` }} />
              ))}
            </div>
          </div>
        </div>

        <p className="text-[11px] uppercase tracking-[0.22em] text-on-surface-variant mt-8 mb-8">{t('wait.sub')}</p>
        <button onClick={onCancel} className="text-sm font-bold underline underline-offset-4 hover:text-primary transition-colors cursor-pointer" style={{ fontFamily: 'var(--portal-heading-font)' }}>
          {t('wait.cancel')}
        </button>
      </main>
    </DesignShell>
  )
}

/* Compact Grid: an ops console — status rows, a scan line, tabular time. */
function MatrixWaiting({ onCancel }) {
  const { t } = useT()
  const clock = useElapsed()
  return (
    <DesignShell className="min-h-screen flex flex-col items-center justify-center">
      <main className="w-full max-w-md px-5 py-8">
        <div className="flex items-center justify-between mb-6">
          <Brand />
          <span className="font-mono text-2xl font-semibold tabular-nums text-primary">{clock}</span>
        </div>

        <div className="relative overflow-hidden bg-surface border border-outline-variant rounded-lg">
          <div className="scan-y absolute left-0 right-0 h-px bg-primary/50 z-10" style={{ boxShadow: '0 0 10px 2px var(--portal-glow1)' }}></div>
          <div className="px-4 py-3 border-b border-outline-variant flex items-center justify-between">
            <span className="text-[11px] font-bold tracking-[0.25em] uppercase text-on-surface-variant">{t('wait.title')}</span>
            <span className="w-2 h-2 rounded-full bg-primary animate-pulse"></span>
          </div>
          <div className="p-4 font-mono text-sm space-y-3.5">
            <div className="flex items-center justify-between gap-3">
              <span className="text-on-surface-variant">{t('wait.sent')}</span>
              <Icon name="check" className="text-primary text-[18px]!" />
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-on-background font-semibold">{t('wait.pin')}</span>
              <span className="cursor-blink text-primary">▊</span>
            </div>
            <div className="flex items-center justify-between gap-3 opacity-45">
              <span className="text-on-surface-variant">{t('wait.activating')}</span>
              <span className="text-on-surface-variant">--</span>
            </div>
          </div>
        </div>

        <p className="text-xs text-on-surface-variant text-center mt-4">{t('wait.sub')}</p>
        <button onClick={onCancel} className="mt-6 w-full h-11 rounded-lg border border-outline-variant text-on-surface-variant text-sm font-semibold hover:border-primary hover:text-primary transition-colors cursor-pointer">
          {t('wait.cancel')}
        </button>
      </main>
    </DesignShell>
  )
}

/* Step-by-Step: the journey continues — a ring parked at step 2 of 3. */
const RING_C = 2 * Math.PI * 52

function StepsWaiting({ onCancel }) {
  const { t } = useT()
  const clock = useElapsed()
  return (
    <DesignShell className="min-h-screen flex flex-col items-center justify-center">
      <main className="w-full max-w-md px-5 py-8 flex flex-col items-center">
        <div className="mb-10"><Brand /></div>

        <div className="relative w-44 h-44 mb-8">
          <svg viewBox="0 0 120 120" className="w-full h-full -rotate-90">
            <circle cx="60" cy="60" r="52" fill="none" stroke="var(--color-surface-container-high)" strokeWidth="7" />
            <circle
              cx="60" cy="60" r="52" fill="none" stroke="var(--color-primary)" strokeWidth="7" strokeLinecap="round"
              strokeDasharray={RING_C} strokeDashoffset={RING_C / 3}
              className="ring-draw" style={{ '--ring-from': RING_C, '--ring-to': RING_C / 3 }}
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-[11px] font-semibold tracking-wider uppercase text-on-surface-variant">{t('steps.of', { n: 2, m: 3 })}</span>
            <span className="font-mono text-2xl font-bold tabular-nums text-primary mt-1">{clock}</span>
          </div>
        </div>

        <h1 className="text-2xl font-bold tracking-tight mb-1 text-center">{t('wait.pin')}</h1>
        <p className="text-sm text-on-surface-variant mb-8 text-center">{t('wait.sub')}</p>

        <div className="w-full bg-surface rounded-xl border border-outline-variant p-4 flex flex-col gap-3">
          <div className="flex items-center gap-3">
            <span className="pop-in w-6 h-6 rounded-full bg-primary text-on-primary flex items-center justify-center" style={{ animationDelay: '0.2s' }}>
              <Icon name="check" className="text-[14px]!" />
            </span>
            <span className="text-sm text-on-surface-variant">{t('wait.sent')}</span>
          </div>
          <div className="flex items-center gap-3">
            <span className="w-6 h-6 rounded-full bg-primary/10 text-primary text-xs font-bold flex items-center justify-center relative">
              <span className="absolute inset-0 rounded-full bg-primary/20 animate-ping"></span>2
            </span>
            <span className="text-sm font-semibold text-on-background">{t('wait.pin')}</span>
          </div>
          <div className="flex items-center gap-3 opacity-50">
            <span className="w-6 h-6 rounded-full border-2 border-outline-variant text-on-surface-variant text-xs font-bold flex items-center justify-center">3</span>
            <span className="text-sm text-on-surface-variant">{t('wait.activating')}</span>
          </div>
        </div>

        <button onClick={onCancel} className="mt-8 h-11 px-8 rounded-lg border-2 border-primary text-primary text-sm font-bold hover:bg-primary hover:text-on-primary transition-colors cursor-pointer">
          {t('wait.cancel')}
        </button>
      </main>
    </DesignShell>
  )
}

/* Terminal: the wait is a log, each line typing itself in. */
function NeonWaiting({ onCancel }) {
  const { t } = useT()
  const clock = useElapsed()
  return (
    <DesignShell className="min-h-screen flex flex-col items-center justify-center">
      <main className="w-full max-w-md px-5 py-8">
        <div className="border border-outline-variant rounded-lg bg-surface overflow-hidden">
          <div className="px-4 h-10 flex items-center gap-2 border-b border-outline-variant">
            <span className="w-2.5 h-2.5 rounded-full bg-error/80"></span>
            <span className="w-2.5 h-2.5 rounded-full bg-primary/40"></span>
            <span className="w-2.5 h-2.5 rounded-full bg-primary"></span>
            <span className="flex-1 text-center text-[11px] tracking-[0.25em] uppercase text-on-surface-variant">m-pesa://stk</span>
          </div>
          <div className="p-5 text-sm leading-7">
            <p className="type-line" style={{ animationDelay: '0.1s' }}>
              <span className="text-primary">&gt;</span> stk_push <span className="text-on-surface-variant">--gateway m-pesa</span>
            </p>
            <p className="type-line text-on-surface-variant" style={{ animationDelay: '0.7s' }}>
              &gt; {t('wait.sent').toLowerCase()} <span className="text-primary">[ok]</span>
            </p>
            <p className="type-line" style={{ animationDelay: '1.4s' }}>
              &gt; {t('wait.pin').toLowerCase()} <span className="cursor-blink text-primary">▊</span>
            </p>
            <p className="type-line text-on-surface-variant" style={{ animationDelay: '2s' }}>
              &gt; t+<span className="tabular-nums">{clock}</span>
            </p>
          </div>
        </div>

        <p className="text-xs text-on-surface-variant text-center mt-4">{t('wait.sub')}</p>
        <button onClick={onCancel} className="mt-6 w-full h-11 rounded border border-primary/60 text-primary text-sm hover:bg-primary/10 transition-colors cursor-pointer">
          [ ctrl+c ] {t('wait.cancel').toLowerCase()}
        </button>
      </main>
    </DesignShell>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 4 — Success (voucher code)                                   */
/* ------------------------------------------------------------------ */

function Confetti() {
  // Confetti in the design's own colours so it never fights the palette.
  const { design } = useT()
  const colors = design.confetti
  const pieces = useMemo(
    () =>
      Array.from({ length: 50 }, (_, i) => ({
        id: i,
        left: Math.random() * 100,
        color: colors[Math.floor(Math.random() * colors.length)],
        duration: Math.random() * 2 + 1,
        delay: Math.random() * 2,
        round: Math.random() > 0.5,
      })),
    [colors]
  )
  return (
    <div className="absolute inset-0 pointer-events-none z-0 overflow-hidden">
      {pieces.map((p) => (
        <div
          key={p.id}
          className="confetti-piece"
          style={{
            left: `${p.left}vw`,
            backgroundColor: p.color,
            borderRadius: p.round ? '50%' : 0,
            animation: `confetti-fall ${p.duration}s linear ${p.delay}s forwards`,
          }}
        />
      ))}
    </div>
  )
}

/* The shared mechanics of the success moment: the code copy, the optional
   operator redirect, and the auto-close countdown. Presentation is per design. */
function useSuccessFlow(code) {
  const [copied, setCopied] = useState(false)
  const [closeIn, setCloseIn] = useState(10)
  const [redirect, setRedirect] = useState(null)
  // Read in a ref so the countdown, which is set up once, sees the value
  // even though it arrives from the network a moment later.
  const redirectRef = useRef(null)

  useEffect(() => {
    api('/portal-settings')
      .then((s) => {
        const url = s?.postPurchaseRedirect || null
        setRedirect(url)
        redirectRef.current = url
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    const t = setInterval(() => {
      setCloseIn((s) => {
        if (s <= 1) {
          clearInterval(t)
          // Send them to the ISP's page if one is set, otherwise close the
          // captive-portal popup (regular tabs just show the fallback text).
          if (redirectRef.current) {
            window.location.href = redirectRef.current
          } else {
            window.close()
          }
          return 0
        }
        return s - 1
      })
    }, 1000)
    return () => clearInterval(t)
  }, [])

  function copyCode() {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return { copied, copyCode, closeIn, redirect }
}

function closingText(t, { closeIn, redirect }) {
  if (closeIn > 0) return redirect ? t('ok.closingRedirect', { n: closeIn }) : t('ok.closing', { n: closeIn })
  return t('ok.closed')
}

/* Copy button, optional continue link, countdown note, return link.
   Token classes mean it re-skins per design; the hero above it is unique. */
function SuccessFooter({ s, onHome }) {
  const { t } = useT()
  return (
    <>
      <button
        onClick={s.copyCode}
        className="w-full bg-primary text-on-primary text-lg font-semibold h-12 rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 cursor-pointer"
      >
        <Icon name={s.copied ? 'check' : 'content_copy'} />
        {s.copied ? t('ok.copied') : t('ok.copyConnect')}
      </button>
      {s.redirect && (
        <a
          href={s.redirect}
          className="mt-3 w-full border border-primary text-primary text-sm font-semibold h-11 rounded-xl hover:bg-primary/5 transition-colors flex items-center justify-center gap-2 cursor-pointer"
        >
          {t('ok.continue')} <Icon name="arrow_forward" className="text-[18px]!" />
        </a>
      )}
      <p className="mt-4 text-xs text-on-surface-variant text-center">{closingText(t, s)}</p>
      <button onClick={onHome} className="mt-2 w-full text-center text-xs text-primary hover:underline cursor-pointer">
        {t('ok.return')}
      </button>
    </>
  )
}

function CredentialHint() {
  const { t } = useT()
  return (
    <p className="text-[13px] text-on-surface-variant text-center">
      {t('ok.usePre')} <strong className="text-on-surface font-semibold">{t('ok.username')}</strong> {t('ok.and')}{' '}
      <strong className="text-on-surface font-semibold">{t('ok.password')}</strong>.
    </p>
  )
}

/* Every design owns its success moment; this just dispatches. */
function SuccessScreen(props) {
  const { design } = useT()
  switch (design.key) {
    case 'BREEZE': return <BreezeSuccess {...props} />
    case 'POSTER': return <PosterSuccess {...props} />
    case 'MATRIX': return <MatrixSuccess {...props} />
    case 'STEPS': return <StepsSuccess {...props} />
    case 'NEON': return <NeonSuccess {...props} />
    default: return <ClassicSuccess {...props} />
  }
}

/* Signature: gold dust drifting up, the code materialising letter by letter. */
function GoldDust() {
  const { design } = useT()
  const color = design.preview.accent
  const motes = useMemo(
    () =>
      Array.from({ length: 26 }, (_, i) => ({
        id: i,
        left: Math.random() * 100,
        size: 2 + Math.random() * 4,
        dur: 6 + Math.random() * 6,
        delay: Math.random() * 6,
        op: 0.4 + Math.random() * 0.6,
      })),
    []
  )
  return (
    <div className="absolute inset-0 overflow-hidden pointer-events-none z-0">
      {motes.map((m) => (
        <span
          key={m.id}
          className="dust"
          style={{
            left: `${m.left}%`, width: m.size, height: m.size, background: color,
            animationDuration: `${m.dur}s`, animationDelay: `${m.delay}s`, opacity: m.op,
          }}
        />
      ))}
    </div>
  )
}

function ClassicSuccess({ code, note, onHome }) {
  const { t } = useT()
  const s = useSuccessFlow(code)
  return (
    <DesignShell className="flex flex-col min-h-screen relative overflow-hidden">
      <GoldDust />
      <main className="flex-grow flex flex-col items-center justify-center px-6 py-12 z-10 w-full max-w-md mx-auto text-center">
        <p className="text-[11px] font-semibold tracking-[0.35em] uppercase text-primary mb-5 fade-up">{t('ok.granted')}</p>
        <h1 className="text-3xl font-bold tracking-tight mb-3 fade-up" style={{ animationDelay: '120ms' }}>{t('ok.title')}</h1>
        <p className="text-sm text-on-surface-variant mb-10 fade-up" style={{ animationDelay: '200ms' }}>{note}</p>

        <div
          className="w-full border-y border-primary/30 py-8 mb-4 fade-up"
          style={{ animationDelay: '280ms', background: 'radial-gradient(60% 130% at 50% 50%, var(--portal-glow1), transparent 72%)' }}
        >
          <p className="text-[10px] font-semibold tracking-[0.3em] uppercase text-on-surface-variant mb-4">{t('ok.codeLabel')}</p>
          <p className="font-mono text-3xl font-semibold tracking-[0.22em] text-primary">
            {code.split('').map((ch, i) => (
              <span key={i} className="letter-in" style={{ animationDelay: `${500 + i * 70}ms` }}>{ch}</span>
            ))}
          </p>
        </div>
        <div className="mb-8 fade-up" style={{ animationDelay: '400ms' }}><CredentialHint /></div>

        <div className="w-full fade-up" style={{ animationDelay: '480ms' }}>
          <SuccessFooter s={s} onHome={onHome} />
        </div>
      </main>
    </DesignShell>
  )
}

/* Breeze: a check that draws itself, then a clean card floats up. */
function BreezeSuccess({ code, note, onHome }) {
  const { t } = useT()
  const s = useSuccessFlow(code)
  return (
    <DesignShell className="flex flex-col min-h-screen relative overflow-hidden">
      <Confetti />
      <main className="flex-grow flex flex-col items-center justify-center px-5 py-10 z-10 w-full max-w-md mx-auto">
        <svg viewBox="0 0 72 72" className="w-24 h-24 mb-6">
          <circle
            cx="36" cy="36" r="32" fill="none" stroke="var(--color-primary)" strokeWidth="4"
            strokeDasharray="202" strokeDashoffset="202" strokeLinecap="round"
            className="draw-stroke" style={{ animationDelay: '0.1s' }}
          />
          <path
            d="M22 37 l10 10 l18 -20" fill="none" stroke="var(--color-primary)" strokeWidth="5"
            strokeLinecap="round" strokeLinejoin="round" strokeDasharray="42" strokeDashoffset="42"
            className="draw-stroke" style={{ animationDelay: '0.75s' }}
          />
        </svg>

        <h1 className="text-2xl font-bold tracking-tight mb-1 text-center fade-up" style={{ animationDelay: '250ms' }}>{t('ok.title')}</h1>
        <p className="text-sm text-on-surface-variant text-center mb-8 fade-up" style={{ animationDelay: '350ms' }}>{note}</p>

        <div className="w-full bg-surface rounded-3xl border border-outline-variant shadow-[0_18px_40px_rgba(15,23,42,0.08)] p-6 text-center mb-8 fade-up" style={{ animationDelay: '450ms' }}>
          <p className="text-[10px] font-semibold tracking-[0.25em] uppercase text-on-surface-variant mb-3">{t('ok.codeLabel')}</p>
          <p className="font-mono text-3xl font-bold tracking-[0.15em] text-primary mb-4">{code}</p>
          <CredentialHint />
        </div>

        <div className="w-full fade-up" style={{ animationDelay: '550ms' }}>
          <SuccessFooter s={s} onHome={onHome} />
        </div>
      </main>
    </DesignShell>
  )
}

/* Market Poster: a paper ticket, stamped PAID at an angle. */
function PosterSuccess({ code, note, onHome }) {
  const { t } = useT()
  const s = useSuccessFlow(code)
  return (
    <DesignShell className="flex flex-col min-h-screen relative overflow-hidden">
      <Confetti />
      <main className="flex-grow flex flex-col items-center justify-center px-6 py-12 z-10 w-full max-w-sm mx-auto">
        <h1 className="text-3xl font-bold tracking-tight mb-8 text-center fade-up">{t('ok.title')}</h1>

        <div className="relative w-full bg-surface border-2 border-on-background px-6 pt-9 pb-6 text-center mb-9 fade-up shadow-[6px_6px_0_var(--color-primary)]" style={{ animationDelay: '150ms' }}>
          <div className="absolute -left-3.5 top-1/2 -translate-y-1/2 w-7 h-7 rounded-full bg-background border-2 border-on-background"></div>
          <div className="absolute -right-3.5 top-1/2 -translate-y-1/2 w-7 h-7 rounded-full bg-background border-2 border-on-background"></div>

          <p className="text-[10px] font-bold tracking-[0.3em] uppercase text-on-surface-variant mb-2">{t('ok.codeLabel')}</p>
          <p className="font-mono text-3xl font-bold tracking-[0.18em] mb-4">{code}</p>
          <div className="border-t border-dashed border-on-background/50 my-4"></div>
          <p className="text-xs text-on-surface-variant mb-2">{note}</p>
          <CredentialHint />

          <div
            className="stamp-in absolute -top-5 -right-4 border-[3px] border-error text-error rounded px-3 py-0.5 text-xl font-black tracking-[0.18em] uppercase bg-background/60"
            style={{ fontFamily: 'var(--portal-heading-font)' }}
          >
            {t('poster.paid')}
          </div>
        </div>

        <div className="w-full fade-up" style={{ animationDelay: '400ms' }}>
          <SuccessFooter s={s} onHome={onHome} />
        </div>
      </main>
    </DesignShell>
  )
}

/* Compact Grid: provisioning log line, then the code tile lights up. */
function MatrixSuccess({ code, note, onHome }) {
  const { t } = useT()
  const s = useSuccessFlow(code)
  return (
    <DesignShell className="flex flex-col min-h-screen">
      <main className="flex-grow flex flex-col justify-center px-5 py-10 w-full max-w-md mx-auto">
        <p className="font-mono text-xs text-primary mb-3 type-line" style={{ animationDelay: '0.1s' }}>
          &gt; provisioning <span className="text-on-surface-variant">[done]</span>
        </p>
        <h1 className="text-2xl font-bold tracking-tight mb-1 fade-up" style={{ animationDelay: '250ms' }}>{t('ok.title')}</h1>
        <p className="text-sm text-on-surface-variant mb-6 fade-up" style={{ animationDelay: '330ms' }}>{note}</p>

        <div className="pop-in bg-surface-container-low border border-primary rounded-lg p-5 text-center mb-3" style={{ animationDelay: '0.45s' }}>
          <p className="text-[10px] font-bold tracking-[0.25em] uppercase text-on-surface-variant mb-2">{t('ok.codeLabel')}</p>
          <p className="font-mono text-3xl font-bold tracking-[0.18em] text-primary">{code}</p>
        </div>
        <div className="mb-8 fade-up" style={{ animationDelay: '550ms' }}><CredentialHint /></div>

        <div className="fade-up" style={{ animationDelay: '650ms' }}>
          <SuccessFooter s={s} onHome={onHome} />
        </div>
      </main>
    </DesignShell>
  )
}

/* Step-by-Step: the ring closes to 100% and the last step checks off. */
function StepsSuccess({ code, note, onHome }) {
  const { t } = useT()
  const s = useSuccessFlow(code)
  return (
    <DesignShell className="flex flex-col min-h-screen relative overflow-hidden">
      <Confetti />
      <main className="flex-grow flex flex-col items-center justify-center px-5 py-10 z-10 w-full max-w-md mx-auto">
        <div className="relative w-36 h-36 mb-6">
          <svg viewBox="0 0 120 120" className="w-full h-full -rotate-90">
            <circle cx="60" cy="60" r="52" fill="none" stroke="var(--color-surface-container-high)" strokeWidth="7" />
            <circle
              cx="60" cy="60" r="52" fill="none" stroke="var(--color-primary)" strokeWidth="7" strokeLinecap="round"
              strokeDasharray={RING_C} strokeDashoffset="0"
              className="ring-draw" style={{ '--ring-from': RING_C / 3, '--ring-to': 0 }}
            />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <span className="pop-in w-14 h-14 rounded-full bg-primary text-on-primary flex items-center justify-center" style={{ animationDelay: '0.9s' }}>
              <Icon name="check" className="text-[32px]!" />
            </span>
          </div>
        </div>

        <p className="text-[11px] font-semibold tracking-wider uppercase text-on-surface-variant mb-1 fade-up" style={{ animationDelay: '250ms' }}>
          {t('steps.of', { n: 3, m: 3 })}
        </p>
        <h1 className="text-2xl font-bold tracking-tight mb-1 text-center fade-up" style={{ animationDelay: '330ms' }}>{t('ok.title')}</h1>
        <p className="text-sm text-on-surface-variant text-center mb-7 fade-up" style={{ animationDelay: '410ms' }}>{note}</p>

        <div className="w-full bg-surface rounded-xl border border-outline-variant p-5 text-center mb-8 fade-up" style={{ animationDelay: '500ms' }}>
          <p className="text-[10px] font-semibold tracking-[0.25em] uppercase text-on-surface-variant mb-2">{t('ok.codeLabel')}</p>
          <p className="font-mono text-3xl font-bold tracking-[0.15em] text-primary mb-3">{code}</p>
          <CredentialHint />
        </div>

        <div className="w-full fade-up" style={{ animationDelay: '600ms' }}>
          <SuccessFooter s={s} onHome={onHome} />
        </div>
      </main>
    </DesignShell>
  )
}

/* Terminal: access granted, code in a glowing frame. */
function NeonSuccess({ code, note, onHome }) {
  const { t } = useT()
  const s = useSuccessFlow(code)
  return (
    <DesignShell className="flex flex-col min-h-screen">
      <main className="flex-grow flex flex-col justify-center px-5 py-10 w-full max-w-md mx-auto">
        <p className="text-sm mb-1 type-line text-on-surface-variant" style={{ animationDelay: '0.1s' }}>
          &gt; {t('neon.confirmed')} <span className="text-primary">[ok]</span>
        </p>
        <p className="text-xl font-bold mb-6 type-line text-primary" style={{ animationDelay: '0.6s', textShadow: '0 0 16px var(--portal-glow1)' }}>
          &gt; {t('neon.grant')}
        </p>

        <div className="border border-primary rounded-lg p-6 text-center mb-3 fade-up" style={{ animationDelay: '1s', boxShadow: '0 0 30px var(--portal-glow2), inset 0 0 24px var(--portal-glow2)' }}>
          <p className="text-[10px] tracking-[0.3em] uppercase text-on-surface-variant mb-3">{t('ok.codeLabel')}</p>
          <p className="font-mono text-3xl font-bold tracking-[0.2em] text-primary" style={{ textShadow: '0 0 14px var(--portal-glow1)' }}>{code}</p>
        </div>
        <p className="text-xs text-on-surface-variant text-center mb-2 fade-up" style={{ animationDelay: '1.1s' }}>{note}</p>
        <div className="mb-7 fade-up" style={{ animationDelay: '1.15s' }}><CredentialHint /></div>

        <div className="fade-up" style={{ animationDelay: '1.25s' }}>
          <SuccessFooter s={s} onHome={onHome} />
        </div>
      </main>
    </DesignShell>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 5 — Error                                                    */
/* ------------------------------------------------------------------ */

function ErrorScreen({ message, onRetry, onChoosePlan }) {
  const { t } = useT()
  return (
    <DesignShell className="min-h-screen flex flex-col">
      <header className="bg-surface border-b border-outline-variant w-full top-0 z-50 flex items-center justify-between px-5 h-16 sticky">
        <Brand />
        <span className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{t('err.badge')}</span>
      </header>

      <main className="flex-grow flex flex-col items-center justify-center px-6 py-12 w-full max-w-sm mx-auto text-center">
        <p className="text-[11px] font-semibold tracking-[0.3em] uppercase text-error mb-5 fade-up">{t('err.badge')}</p>
        <h1 className="text-3xl font-bold tracking-tight mb-3 fade-up" style={{ animationDelay: '80ms' }}>{t('err.title')}</h1>
        <p className="text-sm text-on-surface-variant mb-10 fade-up" style={{ animationDelay: '160ms' }}>{message}</p>

        <div className="w-full flex flex-col gap-3 fade-up" style={{ animationDelay: '240ms' }}>
          <button
            onClick={onRetry}
            className="w-full h-12 bg-primary text-on-primary text-lg font-semibold rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 group cursor-pointer"
          >
            <Icon name="refresh" className="group-hover:rotate-180 transition-transform duration-500" />
            {t('err.retry')}
          </button>
          <button
            onClick={onChoosePlan}
            className="w-full h-11 text-primary border border-outline-variant text-sm font-semibold rounded-xl hover:border-primary transition-colors cursor-pointer"
          >
            {t('err.choose')}
          </button>
        </div>

        {/* A charged-but-not-connected customer can recover their code here. */}
        <div className="w-full mt-8 text-left fade-up" style={{ animationDelay: '280ms' }}>
          <RecoverBox />
        </div>

        <p className="mt-10 text-xs text-on-surface-variant flex items-center justify-center gap-2 fade-up" style={{ animationDelay: '320ms' }}>
          <Icon name="support_agent" className="text-[15px]!" />
          {t('err.support')}{' '}
          <a className="text-primary hover:underline font-semibold" href={`tel:${SUPPORT_PHONE.replace(/\s/g, '')}`}>
            {SUPPORT_PHONE}
          </a>
        </p>
      </main>

      <Footer />
    </DesignShell>
  )
}
