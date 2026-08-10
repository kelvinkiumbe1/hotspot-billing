import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api.js'
import { themeByKey } from '../portalThemes.js'
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
    'hero.gridSub': 'Choose a pass below — fast, reliable internet.',
    'group.Hourly': 'Hourly Passes',
    'group.Daily': 'Daily Passes',
    'group.Weekly': 'Weekly Passes',
    'group.Monthly': 'Monthly Passes',
    'plans.retry': 'Retry',
    'plans.offline': "We can't reach the server right now. Check your connection and try again.",
    'voucher.label': 'Have a voucher code?',
    'voucher.placeholder': 'Enter code',
    'voucher.redeem': 'Redeem',
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
    'ok.closing': 'This page will close in {n}s — connect to SPA WiFi with your code.',
    'ok.closed': 'You can now connect to SPA WiFi with your code.',
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
    'hero.gridSub': 'Chagua kifurushi hapa chini — intaneti ya haraka na ya kuaminika.',
    'group.Hourly': 'Vifurushi vya Saa',
    'group.Daily': 'Vifurushi vya Siku',
    'group.Weekly': 'Vifurushi vya Wiki',
    'group.Monthly': 'Vifurushi vya Mwezi',
    'plans.retry': 'Jaribu tena',
    'plans.offline': 'Hatuwezi kufikia seva kwa sasa. Angalia muunganisho wako ujaribu tena.',
    'voucher.label': 'Una nambari ya kuponi?',
    'voucher.placeholder': 'Weka nambari',
    'voucher.redeem': 'Tumia',
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
    'ok.closing': 'Ukurasa huu utafunga baada ya sekunde {n} — unganisha na SPA WiFi kwa nambari yako.',
    'ok.closed': 'Sasa unaweza kuunganisha na SPA WiFi kwa nambari yako.',
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

const LangContext = createContext({ lang: 'EN', setLang: () => {}, theme: 'AMBER' })

function useT() {
  const { lang, setLang, theme } = useContext(LangContext)
  const t = (key, vars) => {
    let s = (STRINGS[lang] && STRINGS[lang][key]) || STRINGS.EN[key] || key
    if (vars) {
      for (const k of Object.keys(vars)) s = s.split('{' + k + '}').join(vars[k])
    }
    return s
  }
  // Everything the portal shell needs to paint itself in the chosen theme.
  const themeVars = themeByKey(theme).vars
  return { t, lang, setLang, theme, themeVars }
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

function Icon({ name, filled = false, className = '' }) {
  return (
    <span className={`material-symbols-outlined select-none ${filled ? 'filled' : ''} ${className}`} aria-hidden="true">
      {name}
    </span>
  )
}

function Brand() {
  return (
    <div className="flex items-center gap-2">
      <Icon name="wifi" className="text-primary" />
      <span className="text-lg font-semibold text-primary tracking-tight uppercase">SPA WiFi</span>
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
  const [redeemCode, setRedeemCode] = useState('')
  const [redeemErr, setRedeemErr] = useState(null)
  const [plansError, setPlansError] = useState(false)
  const [custom, setCustom] = useState(null)
  const [promo, setPromo] = useState(null)
  const [template, setTemplate] = useState('CLASSIC')
  const [loyaltyEnabled, setLoyaltyEnabled] = useState(false)
  const [lang, setLang] = useState('EN')
  const [theme, setTheme] = useState('AMBER')
  const langChosen = useRef(false)
  const pollRef = useRef(null)

  function loadPlans() {
    setPlansError(false)
    api('/plans').then(setPlans).catch(() => setPlansError(true))
    api('/custom-plan').then(setCustom).catch(() => {})
    api('/promotion').then(setPromo).catch(() => {})
    api('/portal-settings').then((s) => {
      setTemplate(s.portalTemplate || 'CLASSIC')
      setLoyaltyEnabled(!!s.loyaltyEnabled)
      if (s.portalTheme) setTheme(s.portalTheme)
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

  async function redeem(e) {
    e.preventDefault()
    setRedeemErr(null)
    try {
      const v = await api(`/vouchers/${redeemCode.trim().toUpperCase()}/activate`, { method: 'POST' })
      setResult({ code: v.code, note: `Voucher activated — you have ${formatDuration(v.effectiveDurationMinutes || v.plan.durationMinutes)} of internet.` })
      setScreen('success')
      window.scrollTo(0, 0)
    } catch (err) {
      setRedeemErr(err.message)
    }
  }

  function backToPlans() {
    stopPoll()
    setScreen('plans')
    setRedeemErr(null)
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
        template={template}
        loyaltyEnabled={loyaltyEnabled}
        plansError={plansError}
        onRetryPlans={loadPlans}
        onPromoExpire={loadPlans}
        onBuy={choosePlan}
        redeemCode={redeemCode}
        setRedeemCode={setRedeemCode}
        redeemErr={redeemErr}
        onRedeem={redeem}
      />
    )
  }

  return (
    <LangContext.Provider value={{ lang, setLang: chooseLang, theme }}>
      {screen_}
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

function PlansScreen({ plans, custom, promo, template = 'CLASSIC', loyaltyEnabled = false, plansError, onRetryPlans, onPromoExpire, onBuy, redeemCode, setRedeemCode, redeemErr, onRedeem }) {
  const { t, theme, themeVars } = useT()
  const isGrid = template === 'GRID'
  const isMinimal = template === 'MINIMAL'
  const mainWidth = isGrid ? 'max-w-3xl' : 'max-w-lg'
  return (
    <div className="portal-theme bg-background text-on-background min-h-screen flex flex-col" style={themeVars} data-skin={theme}>
      <header className="bg-surface flex items-center justify-between px-5 h-16 w-full border-b border-outline-variant sticky top-0 z-40">
        <Brand />
        <LangToggle />
      </header>

      <main className={`flex-1 w-full ${mainWidth} mx-auto pb-24 px-5 pt-6 flex flex-col gap-6`}>
        {isMinimal ? (
          <section className="fade-up">
            <div className="flex items-center gap-3">
              <div className="w-11 h-11 rounded-full bg-primary/10 flex items-center justify-center">
                <Icon name="wifi" filled className="text-primary text-[22px]!" />
              </div>
              <div>
                <h1 className="text-2xl font-bold tracking-tight text-on-background">{t('hero.minTitle')}</h1>
                <p className="text-sm text-on-surface-variant">{t('hero.minSub')}</p>
              </div>
            </div>
          </section>
        ) : isGrid ? (
          <section className="relative rounded-xl overflow-hidden shadow-[0_8px_16px_rgba(15,23,42,0.08)] fade-up">
            <img src={heroCity} alt="" className="absolute inset-0 w-full h-full object-cover" />
            <div className="absolute inset-0 bg-gradient-to-r from-black/95 via-black/75 to-primary/25"></div>
            <div className="relative z-10 px-6 py-6 flex items-center gap-3">
              <Icon name="wifi" filled className="text-primary-fixed text-[26px]!" />
              <div>
                <h1 className="text-2xl md:text-3xl font-bold tracking-tight text-white">{t('hero.title')}</h1>
                <p className="text-sm text-white/80 mt-0.5">{t('hero.gridSub')}</p>
              </div>
            </div>
          </section>
        ) : (
          <section className="relative rounded-xl overflow-hidden shadow-[0_8px_16px_rgba(15,23,42,0.08)] fade-up">
            <img src={heroCity} alt="" className="absolute inset-0 w-full h-full object-cover" />
            <div className="absolute inset-0 bg-gradient-to-r from-black/95 via-black/80 to-primary/25"></div>
            <div className="relative z-10 p-6 py-8 md:py-10 flex items-center gap-6">
              <div className="flex-1">
                <div className="w-14 h-14 rounded-full bg-white/10 backdrop-blur flex items-center justify-center mb-4 border border-white/20">
                  <Icon name="wifi" filled className="text-primary-fixed text-[28px]!" />
                </div>
                <h1 className="text-3xl md:text-4xl font-bold tracking-tight text-white">{t('hero.title')}</h1>
                <p className="text-base text-white/80 mt-2 max-w-sm">{t('hero.sub')}</p>
              </div>
              <img
                src={customerPhoto}
                alt="Customer browsing on SPA WiFi"
                className="hidden md:block w-36 h-48 object-cover rounded-xl border-2 border-white/20 shadow-lg"
              />
            </div>
          </section>
        )}

        {promo?.active && <PromoBanner promo={promo} onExpire={onPromoExpire} />}

        <section className="flex flex-col gap-6">
          {isMinimal ? (
            plans.length > 0 && (
              <div className="flex flex-col gap-3">
                {plans.map((p) => (
                  <PlanCard key={p.id} plan={p} popular={p.durationMinutes === 1440}
                    onBuy={onBuy} index={plans.indexOf(p)} promo={promo} />
                ))}
              </div>
            )
          ) : (
            PLAN_GROUPS.map((group) => {
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
                  <div className={isGrid ? 'grid grid-cols-1 sm:grid-cols-2 gap-4' : 'flex flex-col gap-4'}>
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
            })
          )}
          {custom?.enabled && plans.length > 0 && <CustomTimeCard custom={custom} promo={promo} onBuy={onBuy} />}
          {plans.length === 0 && !plansError && (
            <div className="flex flex-col gap-4">
              {[0, 1, 2].map((i) => (
                <div key={i} className="animate-pulse bg-surface-container-high rounded-xl h-36" style={{ animationDelay: `${i * 150}ms` }}></div>
              ))}
            </div>
          )}
          {plansError && (
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
          )}
        </section>

        <section className="bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] mt-3 fade-up" style={{ animationDelay: '400ms' }}>
          <form onSubmit={onRedeem}>
            <label className="block text-xs font-semibold tracking-wider uppercase text-on-surface-variant mb-2" htmlFor="voucher">
              {t('voucher.label')}
            </label>
            <div className="flex gap-3">
              <input
                id="voucher"
                type="text"
                required
                value={redeemCode}
                onChange={(e) => setRedeemCode(e.target.value)}
                placeholder={t('voucher.placeholder')}
                className="flex-1 min-w-0 h-12 bg-surface-bright border border-outline-variant rounded-xl px-3 text-sm font-mono uppercase text-on-background focus:outline-none focus:border-primary focus:ring-2 focus:ring-primary/20 transition-all"
              />
              <button
                type="submit"
                className="h-12 px-6 bg-surface text-primary border border-primary rounded-xl text-lg font-semibold hover:bg-surface-container-low transition-colors active:scale-95 cursor-pointer"
              >
                {t('voucher.redeem')}
              </button>
            </div>
            {redeemErr && <p className="text-sm text-error mt-2">{redeemErr}</p>}
          </form>
        </section>

        {loyaltyEnabled && <RewardsCard />}
      </main>

      <div className="hidden md:block w-full"><Footer /></div>

      {/* Mobile bottom nav */}
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
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 2 — Payment (phone number entry)                             */
/* ------------------------------------------------------------------ */

function PayScreen({ plan, phone, setPhone, sending, onSubmit, onClose }) {
  const { t, theme, themeVars } = useT()
  return (
    <div className="portal-theme bg-background text-on-background min-h-screen flex flex-col" style={themeVars} data-skin={theme}>
      <header className="bg-surface border-b border-outline-variant flex items-center justify-between px-5 h-16 w-full sticky top-0 z-50">
        <Brand />
        <button
          aria-label="Close payment"
          onClick={onClose}
          className="material-symbols-outlined text-on-surface-variant hover:bg-surface-container-low p-2 rounded-full transition-colors active:scale-95 duration-100 cursor-pointer"
        >
          close
        </button>
      </header>

      <main className="flex-grow flex flex-col items-center px-5 py-6 w-full max-w-md mx-auto">
        <div className="w-24 h-24 mb-6 rounded-full bg-surface-container-low flex items-center justify-center shadow-[0_4px_12px_rgba(15,23,42,0.05)]">
          <Icon name="wifi" filled className="text-primary text-[48px]!" />
        </div>
        <h1 className="text-2xl font-bold text-center mb-2">{t('pay.complete')}</h1>
        <p className="text-sm text-on-surface-variant text-center mb-8">{t('pay.sub')}</p>

        <div className="w-full bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] mb-6 border-t-4 border-primary">
          <h2 className="text-xs font-semibold tracking-wider uppercase text-outline mb-3">{t('pay.summary')}</h2>
          <div className="flex justify-between items-center">
            <span className="text-lg font-semibold">{t('pay.access', { name: plan.name })}</span>
            <span className="font-mono text-lg font-semibold text-primary">KES {plan.price}</span>
          </div>
        </div>

        <form className="w-full" onSubmit={onSubmit}>
          <div className="w-full bg-surface-container-lowest rounded-xl p-4 shadow-[0_4px_12px_rgba(15,23,42,0.05)] mb-6">
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

          <div className="w-full flex items-start gap-3 bg-surface-container-low p-4 rounded-lg mb-8 border border-surface-dim">
            <Icon name="info" className="text-primary text-[20px]! mt-0.5" />
            <p className="text-sm text-on-surface-variant">
              {t('pay.info')}
            </p>
          </div>

          <button
            type="submit"
            disabled={sending}
            className="w-full bg-primary text-on-primary text-lg font-semibold h-12 rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:brightness-110 active:scale-[0.98] transition-all flex items-center justify-center gap-2 disabled:opacity-60 cursor-pointer"
          >
            <Icon name="send_money" />
            {sending ? t('pay.sending') : t('pay.send')}
          </button>
        </form>
      </main>

      <Footer />
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 3 — Waiting for M-Pesa PIN                                   */
/* ------------------------------------------------------------------ */

function WaitingScreen({ onCancel }) {
  const { t, theme, themeVars } = useT()
  return (
    <div className="portal-theme bg-background text-on-background min-h-screen flex flex-col items-center justify-center" style={themeVars} data-skin={theme}>
      <main className="w-full max-w-md px-5 flex flex-col items-center py-8">
        <div className="flex items-center gap-2 mb-8">
          <Icon name="wifi" className="text-primary text-[28px]!" />
          <span className="text-lg font-semibold tracking-tight uppercase text-primary">SPA WiFi</span>
        </div>

        <div className="relative w-32 h-32 flex items-center justify-center mb-6">
          <div className="absolute inset-0 rounded-full bg-primary/20 radar-ping"></div>
          <div className="absolute inset-0 rounded-full bg-primary/10 radar-ping" style={{ animationDelay: '0.5s' }}></div>
          <div className="relative z-10 w-16 h-16 bg-surface-container-lowest rounded-full shadow-lg flex items-center justify-center border-4 border-surface">
            <Icon name="smartphone" filled className="text-primary text-[32px]!" />
          </div>
        </div>

        <div className="text-center mb-8 w-full">
          <h1 className="text-2xl font-bold text-on-background mb-2">{t('wait.title')}</h1>
          <p className="text-base text-on-surface-variant px-4">{t('wait.sub')}</p>
        </div>

        <div className="w-full bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] p-4 relative">
          <div className="absolute left-[31px] top-8 bottom-8 w-[2px] bg-surface-container-highest z-0"></div>

          <div className="flex items-start gap-4 relative z-10 mb-6">
            <div className="bg-surface-container-lowest rounded-full p-1 border-2 border-surface-container-lowest shadow-sm flex-shrink-0">
              <Icon name="check_circle" filled className="text-primary text-[20px]!" />
            </div>
            <p className="pt-1 text-lg font-semibold text-on-background">{t('wait.sent')}</p>
          </div>

          <div className="flex items-start gap-4 relative z-10 mb-6">
            <div className="bg-surface-container-lowest rounded-full p-1 border-2 border-primary flex-shrink-0">
              <div className="w-5 h-5 rounded-full bg-primary relative flex items-center justify-center">
                <div className="absolute inset-0 rounded-full bg-primary animate-ping opacity-75"></div>
                <div className="w-2 h-2 bg-on-primary rounded-full"></div>
              </div>
            </div>
            <p className="pt-1 text-lg font-semibold text-primary">{t('wait.pin')}</p>
          </div>

          <div className="flex items-start gap-4 relative z-10">
            <div className="bg-surface-container-lowest rounded-full p-1 border-2 border-surface-container-lowest shadow-sm flex-shrink-0">
              <div className="w-5 h-5 rounded-full border-2 border-outline-variant bg-surface-container-lowest"></div>
            </div>
            <p className="pt-1 text-base text-on-surface-variant">{t('wait.activating')}</p>
          </div>
        </div>

        <button
          onClick={onCancel}
          className="mt-8 w-full flex items-center justify-center h-12 rounded-xl border-2 border-primary text-primary text-lg font-semibold hover:bg-primary/5 transition-colors duration-200 cursor-pointer"
        >
          {t('wait.cancel')}
        </button>
      </main>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 4 — Success (voucher code)                                   */
/* ------------------------------------------------------------------ */

// Amber-family + white, matching the brand — the old set was teal/green
// from before the rebrand and fought the whole portal palette.
const CONFETTI_COLORS = ['#fdbf2d', '#e0aa22', '#ffd479', '#ffffff']

function Confetti() {
  const pieces = useMemo(
    () =>
      Array.from({ length: 50 }, (_, i) => ({
        id: i,
        left: Math.random() * 100,
        color: CONFETTI_COLORS[Math.floor(Math.random() * CONFETTI_COLORS.length)],
        duration: Math.random() * 2 + 1,
        delay: Math.random() * 2,
        round: Math.random() > 0.5,
      })),
    []
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

function SuccessScreen({ code, note, onHome }) {
  const { t, theme, themeVars } = useT()
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

  return (
    <div className="portal-theme bg-background text-on-background flex flex-col min-h-screen relative overflow-hidden" style={themeVars} data-skin={theme}>
      <Confetti />

      <main className="flex-grow flex flex-col items-center justify-center px-5 py-8 z-10 w-full max-w-md mx-auto">
        <div className="mb-6 bg-surface-container rounded-full p-6 shadow-sm border border-surface-variant flex items-center justify-center">
          <Icon name="check_circle" filled className="text-[64px]! text-primary" />
        </div>

        <h1 className="text-2xl font-bold text-primary mb-2 text-center">{t('ok.title')}</h1>
        <p className="text-sm text-on-surface-variant text-center mb-8">{note}</p>

        <div className="w-full bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] border-2 border-dashed border-outline-variant p-6 relative flex flex-col items-center mb-6">
          <div className="absolute -left-3 top-1/2 -translate-y-1/2 w-6 h-6 bg-background rounded-full border-r-2 border-dashed border-outline-variant"></div>
          <div className="absolute -right-3 top-1/2 -translate-y-1/2 w-6 h-6 bg-background rounded-full border-l-2 border-dashed border-outline-variant"></div>

          <p className="text-xs font-semibold tracking-wider uppercase text-outline mb-3">{t('ok.codeLabel')}</p>
          <div className="flex items-center justify-center gap-4 bg-surface-container-low px-4 py-3 rounded-lg w-full mb-4">
            <span className="text-lg font-medium font-mono tracking-widest text-on-surface">{code}</span>
            <button
              aria-label="Copy code"
              onClick={copyCode}
              className="w-10 h-10 flex items-center justify-center rounded-full bg-background hover:bg-surface-container transition-colors focus:outline-none focus:ring-2 focus:ring-primary active:scale-95 duration-100 cursor-pointer"
            >
              <Icon name={copied ? 'check' : 'content_copy'} className={`text-[20px]! ${copied ? 'text-secondary' : 'text-primary'}`} />
            </button>
          </div>
          <p className="text-[13px] text-on-surface-variant text-center">
            {t('ok.usePre')} <strong className="text-on-surface font-semibold">{t('ok.username')}</strong> {t('ok.and')}{' '}
            <strong className="text-on-surface font-semibold">{t('ok.password')}</strong>.
          </p>
        </div>

        <button
          onClick={copyCode}
          className="w-full bg-primary text-on-primary text-lg font-semibold py-3 px-6 rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-primary-container transition-colors active:scale-95 duration-100 flex items-center justify-center gap-3 cursor-pointer"
        >
          <Icon name="wifi" filled />
          {copied ? t('ok.copied') : t('ok.copyConnect')}
        </button>

        {redirect && (
          <a
            href={redirect}
            className="mt-4 w-full border border-primary text-primary text-sm font-semibold py-3 px-6 rounded-xl hover:bg-primary/5 transition-colors flex items-center justify-center gap-2 cursor-pointer"
          >
            {t('ok.continue')} <Icon name="arrow_forward" className="text-[18px]!" />
          </a>
        )}
        <p className="mt-4 text-sm text-on-surface-variant text-center">
          {closeIn > 0
            ? (redirect
                ? t('ok.closingRedirect', { n: closeIn })
                : t('ok.closing', { n: closeIn }))
            : t('ok.closed')}
        </p>
        <button onClick={onHome} className="mt-3 text-sm text-primary hover:underline cursor-pointer">
          {t('ok.return')}
        </button>
      </main>

      <div className="z-10 relative w-full"><Footer /></div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Screen 5 — Error                                                    */
/* ------------------------------------------------------------------ */

function ErrorScreen({ message, onRetry, onChoosePlan }) {
  const { t, theme, themeVars } = useT()
  return (
    <div className="portal-theme bg-background text-on-background min-h-screen flex flex-col" style={themeVars} data-skin={theme}>
      <header className="bg-surface border-b border-outline-variant w-full top-0 z-50 flex items-center justify-between px-5 h-16 sticky">
        <Brand />
        <span className="text-xs font-semibold tracking-wider uppercase text-on-surface-variant">{t('err.badge')}</span>
      </header>

      <main className="flex-grow flex flex-col items-center justify-center px-5 py-8 w-full max-w-md mx-auto">
        <section className="bg-surface-container-lowest rounded-xl shadow-[0_4px_12px_rgba(15,23,42,0.05)] w-full p-6 flex flex-col items-center text-center gap-4 border-t-4 border-error">
          <div className="w-20 h-20 bg-error-container text-on-error-container rounded-full flex items-center justify-center mb-3 relative">
            <Icon name="error" filled className="text-[40px]!" />
          </div>

          <h1 className="text-2xl font-bold text-on-surface">{t('err.title')}</h1>
          <p className="text-base text-on-surface-variant max-w-[280px]">{message}</p>

          <div className="w-full flex flex-col gap-3 mt-4">
            <button
              onClick={onRetry}
              className="w-full h-12 bg-primary text-on-primary text-lg font-semibold rounded-xl shadow-[0_8px_16px_rgba(15,23,42,0.08)] hover:bg-surface-tint active:scale-95 transition-all duration-200 flex items-center justify-center gap-2 group cursor-pointer"
            >
              <Icon name="refresh" className="group-hover:rotate-180 transition-transform duration-500" />
              {t('err.retry')}
            </button>
            <button
              onClick={onChoosePlan}
              className="w-full h-12 bg-surface-container-lowest text-primary border border-primary text-lg font-semibold rounded-xl hover:bg-surface-container-low active:scale-95 transition-all duration-200 cursor-pointer"
            >
              {t('err.choose')}
            </button>
          </div>

          <div className="mt-3 pt-3 border-t border-outline-variant w-full">
            <p className="text-sm text-on-surface-variant flex items-center justify-center gap-2">
              <Icon name="support_agent" className="text-[16px]!" />
              {t('err.support')}{' '}
              <a className="text-primary hover:underline font-semibold text-sm" href={`tel:${SUPPORT_PHONE.replace(/\s/g, '')}`}>
                {SUPPORT_PHONE}
              </a>
            </p>
          </div>
        </section>
      </main>

      <Footer />
    </div>
  )
}
