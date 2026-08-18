/**
 * Real brand logos for the payment gateways.
 *
 * Empty on purpose. These are trademarks, and the right versions have to come
 * from each company's own brand assets rather than be redrawn from memory — a
 * hand-approximated logo is both wrong and a worse trademark problem than no
 * logo at all. Where to get them:
 *
 *   Stripe       stripe.com/newsroom/brand-assets
 *   Paystack     paystack.com/press
 *   Flutterwave  flutterwave.com  → press / brand assets
 *   M-Pesa       Safaricom brand portal (partner access)
 *   MTN MoMo     MTN brand portal (partner access)
 *   Visa / MC    Visa Brand Center, Mastercard Brand Center
 *   Free set     simpleicons.org (Stripe, Visa, Mastercard as clean SVGs)
 *
 * To add one: save the SVG here, import it as a React component, and register
 * it below against the gateway kind. Nothing else changes — the screen already
 * falls back to a generic glyph for anything not listed.
 *
 *   import { ReactComponent as Paystack } from './paystack.svg'
 *   export const GATEWAY_LOGOS = { PAYSTACK: () => <Paystack className="h-6 w-auto" /> }
 *
 * Two rules that are not style preferences:
 *
 * 1. INLINE the SVG. Do not link a CDN. The captive portal has no internet
 *    until the customer has paid, so a remote logo fails exactly on the screen
 *    where it matters, and the whole icon set is bundled for this reason.
 *
 * 2. Do not modify them — no recolouring, no squashing, respect the clear
 *    space. Showing a logo to say "we accept this" is ordinary and fine;
 *    altering it, or arranging it so it reads as an endorsement, is not. The
 *    line is stricter for Zidi-the-product than for an ISP saying which
 *    payment methods it takes.
 */

/** Keyed by PaymentGateway.Kind. Anything absent falls back to a glyph. */
export const GATEWAY_LOGOS = {}
