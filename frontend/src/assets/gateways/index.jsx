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
 * <h2>Added later, for the North and Horn of Africa rails</h2>
 *
 *   Konnect      konnect.network/logo.png — their own app icon
 *   Hormuud      commons.wikimedia.org — File:Hormuud logo.png, CC0
 *   EMIS         emis.co.ao/media/irgmivwb/logo_emis.svg — their own vector
 *
 * Each was opened and looked at before being wired up, which is how the Paymob
 * mistake below was caught rather than shipped. simpleicons has none of these
 * five brands; that was checked per slug and not assumed.
 *
 * <h2>Wikimedia Commons, for the official marks</h2>
 *
 * M-Pesa, MTN and Paystack are on Commons as public domain -- these logos are
 * below the threshold of originality, so no copyright subsists in them. That
 * makes Commons a better source than any icon set: it is the real mark rather
 * than somebody's redraw of it. Checked per file via the API rather than
 * assumed, because Commons also hosts plenty that is only CC-BY-SA.
 *
 *   M-Pesa       commons.wikimedia.org — File:M-PESA LOGO-01.svg
 *   MTN          commons.wikimedia.org — File:MTN 2022 logo.svg
 *   Paystack     commons.wikimedia.org — File:Paystack Logo.svg
 *
 * Copyright and trademark are different questions. Public domain settles the
 * first; the second is why rule 2 below still applies in full.
 *
 * <h2>What is still missing</h2>
 *
 * BANK_TRANSFER, which has no logo by nature — it is "any bank", so the generic
 * glyph is the correct answer rather than a gap.
 *
 * And two that were looked for properly and not found:
 *
 *   PAYMOB   Wikimedia has two Paymob files and both are CC BY-SA 4.0, which is
 *            share-alike and not something to pull into this app. Their own site
 *            serves several square logos under /uploads/paymob/logos/ and they
 *            are their partner banks, not Paymob — the first one opened turned
 *            out to be SAIB. Shipping that would have put a random Egyptian
 *            bank's mark on the Paymob card, which is worse than no mark.
 *
 *   CMI      cmi.co.ma renders its logo in script and exposes no logo file; the
 *            only image it offers is a 1201x631 OpenGraph banner, which is a
 *            social card rather than a mark. Nothing on Commons, nothing on
 *            simpleicons.
 *
 * Neither is a gap to be filled by drawing something close enough. Both fall
 * back to the glyph, which is the designed state.
 *
 * Everything else now carries a real mark. If a better vector turns up for
 * Chapa, Flutterwave or Wave, replacing the PNG is a one-line change:
 *
 *   Flutterwave  flutterwave.com — press / media enquiries
 *   Wave         wave.com — press
 *   Chapa        chapa.co
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
import mpesa from './mpesa.svg'
import mtn from './mtn.svg'
import paystack from './paystack.svg'
import paynow from './paynow.svg'
// PNG, not SVG. Chapa, Flutterwave and Wave publish no vector mark anywhere
// reachable -- no inline SVG, nothing logo-named, nothing on Commons -- so
// these are their own app icons. Each is comfortably larger than the 20px it
// renders at (68, 76 and 192 square), which covers a 3x display, and each has
// a real alpha channel rather than a background baked in.
import chapa from './chapa.png'
import flutterwave from './flutterwave.png'
import wave from './wave.png'
// Konnect's own app icon, from konnect.network/logo.png -- 512 square with a
// real alpha channel, lime on their navy. Hormuud's wordmark came off Wikimedia
// Commons as CC0, checked via the API rather than assumed.
import konnect from './konnect.png'
import hormuud from './hormuud.png'
// EMIS's own vector, from emis.co.ao. A wordmark converted to two paths in
// their navy and orange, 160x29 -- so it renders at its real ratio.
import emis from './emis.svg'

/**
 * One mark, rendered without being interfered with.
 *
 * object-contain rather than a fixed width and height: these SVGs are not all
 * square, and stretching a trademark to fit a box is the exact thing rule 2
 * forbids.
 */
const mark = (src, alt) => function GatewayLogo() {
  // Height-constrained with width free, so a square icon renders 20x20 and a
  // wordmark renders at its own ratio rather than being squashed into a box.
  // max-w keeps a very wide one (Paystack is 5.6:1) from pushing the card title.
  return <img src={src} alt={alt} className="h-5 w-auto max-w-[92px] object-contain" />
}

/** Keyed by PaymentGateway.Kind. Anything absent falls back to a glyph. */
export const GATEWAY_LOGOS = {
  MPESA_API: mark(mpesa, 'M-Pesa'),
  MPESA_PAYBILL_MANUAL: mark(mpesa, 'M-Pesa'),
  MPESA_TILL_MANUAL: mark(mpesa, 'M-Pesa'),
  // The same brand under a different operator. Vodacom licenses the M-Pesa
  // name and mark from Vodafone exactly as Safaricom does, so this is the
  // right logo rather than a stand-in.
  VODACOM_MPESA: mark(mpesa, 'M-Pesa'),
  MTN_MOMO: mark(mtn, 'MTN'),
  PAYSTACK: mark(paystack, 'Paystack'),
  PAYNOW: mark(paynow, 'Paynow'),
  STRIPE: mark(stripe, 'Stripe'),
  AIRTEL_MONEY: mark(airtel, 'Airtel Money'),
  ORANGE_MONEY: mark(orange, 'Orange Money'),
  CHAPA: mark(chapa, 'Chapa'),
  FLUTTERWAVE: mark(flutterwave, 'Flutterwave'),
  WAVE: mark(wave, 'Wave'),
  KONNECT: mark(konnect, 'Konnect'),
  // Hormuud is the company; EVC Plus is the wallet it runs and what a Somali
  // customer would recognise. The mark is Hormuud's because that is the one
  // that exists as a free file, and the card beside it says EVC Plus.
  WAAFIPAY: mark(hormuud, 'Hormuud EVC Plus'),
  // EMIS runs Multicaixa. Their corporate mark rather than a Multicaixa Express
  // one, because Express publishes no reachable logo file -- and EMIS is whose
  // gateway this actually calls.
  MULTICAIXA: mark(emis, 'EMIS Multicaixa'),
}

/**
 * Also exported by name, for the places that lay marks out horizontally -- a
 * "works with" strip wants the wordmark directly rather than through the
 * gateway-keyed map.
 */
export { default as paynowWordmark } from './paynow.svg'
export { default as mpesaWordmark } from './mpesa.svg'

/**
 * Card-network marks, kept for the "cards accepted" row on the checkout and the
 * marketing page. Deliberately not in GATEWAY_LOGOS: Visa and Mastercard are
 * not gateways, they are what Paystack, Flutterwave and Stripe accept, and
 * putting a Visa logo where a gateway's own mark belongs misdescribes who the
 * operator has an account with.
 */
export { default as visaMark } from './visa.svg'
export { default as mastercardMark } from './mastercard.svg'
