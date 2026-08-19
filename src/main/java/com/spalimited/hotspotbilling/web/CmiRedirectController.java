package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Payment;
import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.service.PaymentService;
import com.spalimited.hotspotbilling.service.payments.CmiProvider;
import com.spalimited.hotspotbilling.service.payments.PaymentProvider;
import com.spalimited.hotspotbilling.service.payments.PublicUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The two browser stops in a CMI payment.
 *
 * <p>CMI is not an API. The customer's own browser posts signed fields to its
 * 3-D Secure gateway and is posted back here with a signed result, so unlike
 * every other rail there are pages in the middle rather than server-to-server
 * calls. These are the two pages.
 *
 * <p>Public on purpose: a customer reaches both before they have any session, and
 * the second one arrives as a cross-site POST from CMI. Neither is a place to put
 * anything an operator would mind a customer seeing, and neither trusts anything
 * it is given — the result is refused unless it carries CMI's signature.
 */
@RestController
@RequestMapping("/api/payments/cmi")
@RequiredArgsConstructor
@Slf4j
public class CmiRedirectController {

    private final CmiProvider cmi;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final PublicUrls urls;

    /**
     * The page that sends the customer to CMI.
     *
     * <p>A form that submits itself, because {@code checkoutUrl} can only be
     * somewhere to send a browser with a GET and CMI needs a POST. The fields are
     * built here rather than when the charge started, so the signature is fresh
     * and nothing has to be stored between the two.
     *
     * <p>Keyed on the reference, which the customer already knows because they
     * are the one paying against it. Only a payment still pending gets a form:
     * without that check, an old reference would build a second form for a
     * payment that had already been settled.
     */
    @GetMapping(value = "/redirect", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> redirect(@RequestParam("ref") String reference) {
        Optional<Payment> found = payments.findByCheckoutRequestId(reference)
                .filter(p -> p.getStatus() == Payment.Status.PENDING);
        if (found.isEmpty()) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body(page("This payment is no longer waiting to be paid.", ""));
        }
        Payment payment = found.get();
        Optional<CmiProvider.Form> form = cmi.form(reference, payment.getAmount(),
                payment.getPhoneNumber(), null);
        if (form.isEmpty()) {
            return ResponseEntity.status(503).contentType(MediaType.TEXT_HTML)
                    .body(page("Card payments are not available right now.", ""));
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(page("Taking you to " + cmi.bankName() + " to pay securely…",
                        formHtml(form.get())));
    }

    /**
     * Where CMI posts the outcome, and where it sends the customer afterwards.
     *
     * <p>One endpoint for both because CMI is configured with one URL for success,
     * failure and its server-to-server callback, and all three carry the same
     * signed fields. Whichever arrives first settles the payment; the second finds
     * it already settled, which {@code settleFromProvider} handles.
     */
    @PostMapping("/return")
    public ResponseEntity<Void> back(@RequestParam Map<String, String> posted) {
        String reference = posted.getOrDefault("oid", "");
        try {
            Optional<PaymentProvider.Settlement> settled = cmi.settleForm(posted);
            if (settled.isPresent()) {
                PaymentProvider.Settlement s = settled.get();
                paymentService.settleFromProvider("CMI", s.providerRef(), s.reference(),
                        s.paid(), s.amount(), s.receipt(), s.failureReason());
                return seeOther(s.paid() ? "paid" : "failed", s.reference());
            }
        } catch (Exception e) {
            // An unsigned or mis-signed result is refused, and the customer is
            // still a person standing in front of a phone. They get sent back to
            // the portal rather than shown a stack trace; the log carries the why.
            log.warn("A CMI result for {} was refused: {}", reference, e.getMessage());
        }
        return seeOther("failed", reference);
    }

    private ResponseEntity<Void> seeOther(String outcome, String reference) {
        String origin = urls.origin();
        String base = origin == null || origin.isBlank() ? "/" : origin + "/";
        return ResponseEntity.status(303)
                .location(URI.create(base + "?" + outcome + "="
                        + URLEncoder.encode(reference == null ? "" : reference,
                                StandardCharsets.UTF_8)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * The self-submitting form.
     *
     * <p>Values are escaped for HTML. A reference or a name is ours rather than a
     * customer's, but the hash is base64 and a stray {@code "} in any field would
     * break out of the attribute — and this page is assembled by hand rather than
     * by a template engine.
     */
    private static String formHtml(CmiProvider.Form form) {
        StringBuilder html = new StringBuilder();
        html.append("<form id=\"cmi\" method=\"POST\" action=\"")
                .append(escape(form.action())).append("\">");
        form.fields().forEach((name, value) -> html
                .append("<input type=\"hidden\" name=\"").append(escape(name))
                .append("\" value=\"").append(escape(value)).append("\">"));
        html.append("<noscript><button type=\"submit\">Continue to pay</button></noscript>")
                .append("</form>")
                .append("<script>document.getElementById('cmi').submit();</script>");
        return html.toString();
    }

    /** A plain page, styled enough not to look broken on a phone. */
    private static String page(String message, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Payment</title><style>"
                + "body{font-family:system-ui,-apple-system,sans-serif;display:flex;"
                + "align-items:center;justify-content:center;min-height:100vh;margin:0;"
                + "background:#faf9f6;color:#1c1b1f;text-align:center;padding:1.5rem}"
                + "p{font-size:1rem;line-height:1.5}"
                + "</style></head><body><div><p>" + escape(message) + "</p>" + body
                + "</div></body></html>";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
