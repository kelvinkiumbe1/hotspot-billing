/**
 * What to call paying, on this operator's screens.
 *
 * "M-Pesa" was written into screens a customer sees, which is fine in Kenya and
 * wrong everywhere else — a Ghanaian customer told to "enter your M-Pesa PIN"
 * while an MTN prompt sits on their handset reasonably concludes something has
 * gone wrong. The backend already treats this as a setting and words its own
 * messages from it; the browser did not.
 *
 * Mirrors money.js deliberately, including the default: M-Pesa until the
 * settings arrive, so an existing Kenyan deployment reads exactly as it did.
 */

let brand = 'M-Pesa'

/** Primed once from /portal-settings, alongside currency and country. */
export function setPayBrand(next) {
  if (next && String(next).trim()) {
    brand = String(next).trim()
  }
}

/** The brand alone, e.g. "M-Pesa", "MTN MoMo", "Mobile Money". */
export function payBrand() {
  return brand
}

/**
 * "your M-Pesa PIN", "your MTN MoMo PIN".
 *
 * A separate helper because the generic brands read badly with a possessive —
 * "your Mobile Money PIN" is fine, "your card PIN" is not — so a rail that is
 * not a wallet gets the plainer wording.
 */
export function payPinPhrase() {
  return /card|bank/i.test(brand) ? 'your PIN' : `your ${brand} PIN`
}
