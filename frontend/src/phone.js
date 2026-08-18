/**
 * Turning what a customer typed into one canonical number, on the browser side.
 *
 * Mirrors PhoneNumbers on the server deliberately, because the two disagreeing
 * means the portal accepts a number the API then rejects — and the customer
 * sees a form that says nothing is wrong and a payment that never starts.
 *
 * The rules were "254" everywhere until now, which is why a Ghanaian ISP could
 * set their country, currency and language correctly and still sell nothing.
 */

// Dialling code and the number of digits after the trunk zero. Kept in step
// with Country.java; the countries listed there are the countries listed here.
const DIALLING = {
  KE: ['254', 9, ['7', '1']], TZ: ['255', 9], UG: ['256', 9], RW: ['250', 9],
  GH: ['233', 9], NG: ['234', 10], ZA: ['27', 9], ZM: ['260', 9],
  MW: ['265', 9], MZ: ['258', 9], AO: ['244', 9], SN: ['221', 9],
  CI: ['225', 10], CM: ['237', 9], CD: ['243', 9], ET: ['251', 9],
  ZW: ['263', 9],
  // Countries the built rails already reach, added with no new integration.
  ML: ['223', 8], BF: ['226', 8], NE: ['227', 8], GN: ['224', 9],
  SL: ['232', 8], BW: ['267', 8], TD: ['235', 8], GA: ['241', 8],
  CG: ['242', 9], MG: ['261', 9], GM: ['220', 7], BJ: ['229', 10],
  SZ: ['268', 8], SS: ['211', 9],
  OTHER: ['', 0],
}

// Kenya until the server says otherwise, matching the backend default, so an
// existing deployment behaves exactly as it did before this file existed.
let home = 'KE'

/** Primed once from /api/portal-settings, alongside currency and language. */
export function setCountry(code) {
  if (code && DIALLING[code]) home = code
}

export function countryCode() {
  return home
}

/** The shape to show a customer, e.g. "2547XXXXXXXX". */
export function phoneExample() {
  const [dial, len] = DIALLING[home] || DIALLING.KE
  if (!dial || !len) return 'your number, with its country code'
  return dial + 'X'.repeat(len)
}

/** The country whose dialling code these digits begin with, if any. */
function byPrefix(digits) {
  let best = null
  for (const [, [dial, len]] of Object.entries(DIALLING)) {
    if (!dial || !digits.startsWith(dial)) continue
    if (!best || dial.length > best[0].length) best = [dial, len]
  }
  return best
}

/**
 * The canonical form, or null when it cannot be one.
 *
 * Note this returns null rather than guessing. The old version always returned
 * something — it stripped a leading 254 or 0 and glued "254" back on — so a
 * Ghanaian number came back as a real Kenyan number belonging to a stranger.
 */
export function normalizePhone(raw) {
  if (raw === null || raw === undefined) return null
  let digits = String(raw).replace(/\D/g, '')
  if (!digits) return null
  if (digits.startsWith('00')) digits = digits.slice(2)
  if (digits.length > 15) return null

  // Already international, for anywhere we know — an operator near a border
  // has customers on the other side of it.
  const foreign = byPrefix(digits)
  if (foreign && digits.length - foreign[0].length === foreign[1]) return digits

  const [dial, len] = DIALLING[home] || DIALLING.KE
  if (!dial || !len) return digits.length >= 8 ? digits : null

  const prefixes = (DIALLING[home] || [])[2] || []
  // Only where the server also insists, so the two never disagree about the
  // same number — a portal that accepts what the API refuses shows a form
  // saying nothing is wrong and a payment that never starts.
  const ok = (national) => !prefixes.length || prefixes.some((p) => national.startsWith(p))

  if (digits.startsWith('0') && digits.length - 1 === len) {
    return ok(digits.slice(1)) ? dial + digits.slice(1) : null
  }
  if (digits.length === len) return ok(digits) ? dial + digits : null
  if (digits.startsWith(dial) && digits.length - dial.length === len) {
    return ok(digits.slice(dial.length)) ? digits : null
  }
  return null
}

/** Whether this could be dialled — for enabling a Pay button. */
export function isValidPhone(raw) {
  return normalizePhone(raw) !== null
}

/**
 * A value safe to put in a URL or send to the API.
 *
 * normalizePhone returns null for something it cannot read, which is the
 * honest answer — but a lookup path must not become "/loyalty/null". Mirrors
 * loose() on the server: the digits as typed, so the request fails on the
 * server's terms rather than on a malformed URL.
 */
export function phoneForLookup(raw) {
  return normalizePhone(raw) || String(raw ?? '').replace(/\D/g, '')
}

/**
 * The "+254" shown beside a phone field.
 *
 * Was written into the pay form as a literal, which told a Ghanaian customer
 * their number began with Kenya's country code.
 */
export function dialPrefix() {
  const [dial] = DIALLING[home] || DIALLING.KE
  return dial ? '+' + dial : ''
}
