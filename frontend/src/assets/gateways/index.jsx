/**
 * Real brand logos for the payment gateways.
 *
 * Five are here. The rest are missing because they are trademarks that live
 * behind partner brand portals, and a hand-approximated logo is both wrong and
 * a worse trademark problem than no logo at all. Anything absent falls back to
 * a generic glyph, so a partial set is the designed state rather than a broken
 * one.
 *
 * <h2>What is here, and where it came from</h2>
 *
 * Stripe, Airtel, Orange, Visa and Mastercard came from simpleicons.org, whose
 * icon shapes are CC0. The brands themselves remain the trademarks of their
 * owners — CC0 covers the drawing, not permission to imply a relationship.
 * Each file keeps the official brand colour and is otherwise untouched.
 *
 * <h2>Where to get the rest</h2>
 *
 *   Paystack     paystack.com/press
 *   Flutterwave  flutterwave.com — press / media enquiries
 *   Wave         wave.com — press
 *   M-Pesa       Safaricom brand portal (developer.safaricom.co.ke → partner access)
 *   MTN MoMo     momodeveloper.mtn.com → MTN brand portal
 *   Chapa        chapa.co
 *   Paynow       paynow.co.zw
 *
 * Checked live rather than recited: several of those hosts return 403 to a
 * command-line request and open perfectly in a browser, so a failed fetch there
 * means bot-blocking, not a dead page.
 *
 * What a direct fetch of each site did and did not yield, so nobody repeats it:
 *
 *   Paynow       its real SVG wordmark, /Content/landing/images/paynow-logo-blue.svg
 *   Wave         only /img/nav-logo.png, 230x101 raster -- blurs when scaled
 *   Paystack     nothing usable; the one self-identifying inline SVG on their
 *                home page is a 93 KB world map, not the wordmark
 *   Flutterwave  nothing logo-named; the vector assets served are merchant logos
 *   Chapa        no inline or linked SVG at all
 *
 * <h2>Adding one</h2>
 *
 * Save the SVG beside this file and register it below. Note this project is
 * Vite with no svgr plugin, so the CRA-style `ReactComponent as X` import does
 * not work here — import the file and render it as an image:
 *
 *   import paystack from './paystack.svg'
 *   export const GATEWAY_LOGOS = { PAYSTACK: mark(paystack, 'Paystack') }
 *
 * <h2>Two rules that are not style preferences</h2>
 *
 * 1. NO CDN. These are bundled, and every one is small enough that Vite inlines
 *    it as a data URI — so there is no network request at all. That matters
 *    because the captive portal has no internet until the customer has paid,
 *    which is exactly the screen where a missing logo would be noticed.
 *
 * 2. DO NOT MODIFY THEM. No recolouring, no squashing, respect the clear space.
 *    Showing a mark to say "we accept this" is ordinary and fine; altering it,
 *    or arranging it so it reads as an endorsement, is not. The line is stricter
 *    for Zidi-the-product than for an ISP listing the methods it takes.
 */

import stripe from './stripe.svg'
import airtel from './airtel.svg'
import orange from './orange.svg'

/**
 * One mark, rendered without being interfered with.
 *
 * object-contain rather than a fixed width and height: these SVGs are not all
 * square, and stretching a trademark to fit a box is the exact thing rule 2
 * forbids.
 */
const mark = (src, alt) => function GatewayLogo() {
  return <img src={src} alt={alt} className="w-6 h-6 object-contain" />
}

/** Keyed by PaymentGateway.Kind. Anything absent falls back to a glyph. */
export const GATEWAY_LOGOS = {
  STRIPE: mark(stripe, 'Stripe'),
  AIRTEL_MONEY: mark(airtel, 'Airtel Money'),
  ORANGE_MONEY: mark(orange, 'Orange Money'),
}

/**
 * Wordmarks: correct, and the wrong shape for the gateway chip.
 *
 * Paynow publishes its logo as an 88x19 SVG wordmark. The chip on a gateway
 * card is a 44px square, so object-contain renders that wordmark about five
 * pixels tall -- legible to nobody. An illegible logo is worse than the clean
 * generic glyph it would replace, so this is deliberately not in
 * GATEWAY_LOGOS; it is here for the places that have horizontal room, like a
 * "works with" strip.
 *
 * Its single fill (#175FF8) measures 3.3:1 against the dark chip background,
 * which clears the 3:1 threshold for non-text, so colour was never the problem
 * -- only the aspect ratio.
 */
export { default as paynowWordmark } from './paynow.svg'

/**
 * Card-network marks, kept for the "cards accepted" row on the checkout and the
 * marketing page. Deliberately not in GATEWAY_LOGOS: Visa and Mastercard are
 * not gateways, they are what Paystack, Flutterwave and Stripe accept, and
 * putting a Visa logo where a gateway's own mark belongs misdescribes who the
 * operator has an account with.
 */
export { default as visaMark } from './visa.svg'
export { default as mastercardMark } from './mastercard.svg'
