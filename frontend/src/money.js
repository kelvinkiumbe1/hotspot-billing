/**
 * How this operator writes money, on the browser side.
 *
 * "KES" was hardcoded in a hundred and seventy-five places across the frontend.
 * The backend now treats currency as a setting; if the screens did not, a
 * Nigerian operator would read naira in their WhatsApp messages and shillings
 * on their own dashboard.
 *
 * The rules deliberately mirror MoneyService on the server, because the same
 * amount shown two ways in one product is a bug the operator has to explain to
 * a customer.
 */

// Shillings until told otherwise, matching the server's default, so a screen
// that renders before the settings arrive is right for existing deployments
// rather than blank or wrong.
let config = { code: 'KES', symbol: '', suffix: false, decimals: 0 }

/** Primed once from /portal-settings or /admin/portal-settings. */
export function setCurrency(next) {
  if (!next) return
  config = {
    code: next.code || 'KES',
    symbol: next.symbol || '',
    suffix: !!next.suffix,
    decimals: Number.isFinite(next.decimals) ? Math.max(0, Math.min(4, next.decimals)) : 0,
  }
}

export function currencyCode() {
  return config.code
}

/** The unit as customers see it — a glyph if one is set, else the code. */
export function currencyUnit() {
  return config.symbol || config.code
}

/**
 * An amount as a customer should read it: "KES 1,200", "₦1,200", "$12.50",
 * "2,500 FCFA". A letter code takes a space, a glyph does not.
 */
export function money(amount) {
  const n = Number(amount || 0)
  const number = n.toLocaleString(undefined, {
    minimumFractionDigits: config.decimals,
    maximumFractionDigits: config.decimals,
  })
  const unit = currencyUnit()
  const spaced = /[A-Za-z]/.test(unit)
  if (config.suffix) return spaced ? `${number} ${unit}` : `${number}${unit}`
  return spaced ? `${unit} ${number}` : `${unit}${number}`
}

/** The grouped number with no unit, for a column that already has a heading. */
export function amount(value) {
  return Number(value || 0).toLocaleString(undefined, {
    minimumFractionDigits: config.decimals,
    maximumFractionDigits: config.decimals,
  })
}
