package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import com.spalimited.hotspotbilling.service.i18n.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DPO Group — nineteen countries on one integration.
 *
 * <p>The best coverage-per-integration left on the continent, and the aggregator
 * East and Southern African ISPs already use. It reaches cards and most of the
 * regional wallets behind one hosted page, and two of its markets — Mauritius and
 * Namibia — had nothing at all before this.
 *
 * <h2>It speaks XML</h2>
 *
 * <p>The only rail here that does. Request and response are both {@code <API3G>}
 * documents, which brings two problems nothing else in this package has.
 *
 * <p>Building one means escaping: a {@code &} in a plan name produces a document
 * DPO answers with {@code 804 Error in XML} rather than a payment. See
 * {@link #xml}.
 *
 * <p>Reading one means not trusting it. An XML parser left at its defaults will
 * fetch external entities a document names — so a malicious or intercepted
 * response could read files off this server or hang it on a slow URL. The parser
 * here is hardened against that, and there is a test that feeds it exactly such a
 * document. This is the first XML in the codebase and so the first place that
 * question has had to be answered.
 *
 * <h2>What was verified</h2>
 *
 * <p>Against the live API. A well-formed {@code createToken} reaches
 * {@code 802 Company is not active} while an empty body gets {@code 804 Error in
 * XML}, so the document shape is accepted. {@code 801 Request missing company
 * token} names the credential element. And {@code createToken} and
 * {@code verifyToken} both reach 802 while an invented request name returns
 * {@code 803 No request or error in Request type name} — so both are real.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DpoProvider implements PaymentProvider {

    /**
     * DPO's markets.
     *
     * <p>Mauritius and Namibia are the reason this list is worth reading: both
     * were {@code Rail.NONE} until DPO, with no gateway of their own reachable
     * from anywhere. The rest already have a rail and DPO sits beside it — an
     * aggregator is worth having as a second option where the first only reaches
     * one telco's customers.
     */
    private static final Set<Country> MARKETS = Set.of(
            Country.BW, Country.CI, Country.EG, Country.ET, Country.GH, Country.KE,
            Country.MW, Country.MU, Country.MA, Country.MZ, Country.NA, Country.NG,
            Country.RW, Country.SN, Country.ZA, Country.TZ, Country.UG, Country.ZM,
            Country.ZW);

    /** What DPO wants a service date to look like. Not ISO-8601. */
    private static final DateTimeFormatter SERVICE_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /** DPO's word for "that worked". Everything else is a refusal or a wait. */
    private static final String OK = "000";

    private final PaymentGatewayService gateways;
    private final PortalSettingsService portalSettings;
    private final PaymentEndpoints endpoints;
    private final PublicUrls urls;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.DPO;
    }

    @Override
    public boolean usable() {
        return availableHere() && config() != null;
    }

    /**
     * Asking is how this settles.
     *
     * <p>{@code verifyToken} is a first-class operation and DPO signs nothing, so
     * a notification is a hint and the query is the answer — the same shape as
     * MTN.
     */
    @Override
    public boolean pollable() {
        return true;
    }

    /** True where DPO is worth offering at all. */
    public boolean availableHere() {
        try {
            return MarketGuard.servesHere("DPO", MARKETS,
                    portalSettings.settings().getCountry());
        } catch (Exception e) {
            return false;
        }
    }

    private record Config(String companyToken, String serviceType) {
    }

    private Config config() {
        PaymentGateway g = gateways.find(PaymentGateway.Kind.DPO)
                .filter(PaymentGateway::isActive).orElse(null);
        if (g == null || blank(g.getSecretKey()) || blank(g.getShortCode())) {
            return null;
        }
        return new Config(g.getSecretKey().trim(), g.getShortCode().trim());
    }

    // ------------------------------------------------------------------ charge

    /**
     * Creates a transaction token and hands back DPO's hosted page for it.
     *
     * <p>No currency guard here, unlike the single-country rails. DPO settles in
     * whatever its merchant account is denominated in, and that is the operator's
     * own currency by construction — so the amount goes out in whatever they
     * priced in rather than being checked against a country's one true currency.
     */
    @Override
    public Charge charge(ChargeRequest request) {
        Config cfg = availableHere() ? config() : null;
        if (cfg == null) {
            throw new IllegalStateException("DPO is not set up for this country");
        }
        String origin = urls.origin();

        StringBuilder body = new StringBuilder();
        body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?><API3G>");
        body.append(xml("CompanyToken", cfg.companyToken()));
        body.append(xml("Request", "createToken"));
        body.append("<Transaction>");
        // Major units, two places. DPO takes the currency's own unit.
        body.append(xml("PaymentAmount",
                request.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()));
        body.append(xml("PaymentCurrency", request.currency()));
        // Ours, and what verifyToken quotes back.
        body.append(xml("CompanyRef", request.reference()));
        if (origin != null && !origin.isBlank()) {
            body.append(xml("RedirectURL", origin + "/?paid=" + enc(request.reference())));
            body.append(xml("BackURL", origin + "/?failed=" + enc(request.reference())));
        }
        // 0, because our reference is already unique per attempt and DPO refuses
        // a repeat when this is 1 -- which would stop a customer retrying after a
        // declined card.
        body.append(xml("CompanyRefUnique", "0"));
        // Minutes the token stays payable. Long enough to find a card, short
        // enough that an abandoned attempt does not sit open.
        body.append(xml("PTL", "30"));
        body.append("</Transaction><Services><Service>");
        body.append(xml("ServiceType", cfg.serviceType()));
        body.append(xml("ServiceDescription", trim(request.description(), 200)));
        body.append(xml("ServiceDate", LocalDateTime.now().format(SERVICE_DATE)));
        body.append("</Service></Services></API3G>");

        Document response = send(body.toString(), "create a transaction");
        String result = text(response, "Result");
        if (!OK.equals(result)) {
            throw new IllegalStateException(refusal(response, result));
        }
        String token = text(response, "TransToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("DPO accepted the transaction but gave no token back");
        }
        return new Charge(token, endpoints.dpoCheckout() + "?ID=" + enc(token));
    }

    // ----------------------------------------------------------------- outcome

    @Override
    public Optional<Settlement> poll(String providerRef) {
        Config cfg = config();
        if (cfg == null || providerRef == null || providerRef.isBlank()) {
            return Optional.empty();
        }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><API3G>"
                + xml("CompanyToken", cfg.companyToken())
                + xml("Request", "verifyToken")
                + xml("TransactionToken", providerRef)
                + "</API3G>";
        Document response;
        try {
            response = send(body, "verify a transaction");
        } catch (Exception e) {
            log.debug("DPO verify for {} unavailable: {}", providerRef, e.getMessage());
            return Optional.empty();
        }
        return read(response, providerRef);
    }

    /**
     * A verify response, turned into a verdict.
     *
     * <p>{@code 000} is paid. Nothing else is treated as paid, and only an
     * explicit cancellation is treated as failed — {@code 900} is DPO's answer
     * for a transaction it has not been paid for <em>yet</em>, so reading it as a
     * failure would cancel a sale from a customer still on the payment page.
     * Everything unrecognised waits, and the sweep eventually times it out.
     */
    static Optional<Settlement> read(Document response, String providerRef) {
        if (response == null) {
            return Optional.empty();
        }
        String result = text(response, "Result");
        String reference = text(response, "CompanyRef");
        BigDecimal amount = amountOf(text(response, "TransactionAmount"));
        String currency = text(response, "TransactionCurrency");
        String receipt = firstOf(text(response, "TransactionApproval"),
                text(response, "TransactionRef"), providerRef);

        if (OK.equals(result)) {
            return Optional.of(new Settlement(providerRef, reference, true, amount,
                    currency, receipt, null));
        }
        // The transaction's own state, where DPO gave one. A cancelled or
        // declined transaction is over and should stop being waited on.
        String state = firstOf(text(response, "TransactionStatusDescription"),
                text(response, "ResultExplanation"), "");
        String lower = state.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("cancel") || lower.contains("declin") || lower.contains("fail")
                || lower.contains("expire")) {
            return Optional.of(new Settlement(providerRef, reference, false, amount,
                    currency, null, state));
        }
        return Optional.empty();
    }

    /**
     * A notification from DPO, which proves nothing on its own.
     *
     * <p>DPO signs nothing, so the body is read only far enough to find the
     * transaction token and the verdict comes from asking. Believing it would let
     * anyone who learned a token mark a payment paid.
     */
    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        String token = tokenIn(rawBody);
        if (token == null || token.isBlank()) {
            throw Signatures.reject("DPO", "no transaction token to check");
        }
        return poll(token);
    }

    /**
     * The token a notification names.
     *
     * <p>DPO's notification is configured in their dashboard rather than in our
     * request, and arrives as XML or as form fields depending on the setting — so
     * this reads out of either rather than parsing to a model. Nothing in it is
     * trusted; it is only used to ask a question.
     */
    static String tokenIn(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        int open = body.indexOf("<TransactionToken>");
        if (open >= 0) {
            int close = body.indexOf("</TransactionToken>", open);
            if (close > open) {
                return body.substring(open + "<TransactionToken>".length(), close).trim();
            }
        }
        for (String key : new String[]{"TransactionToken=", "TransToken=", "ID="}) {
            int at = body.indexOf(key);
            if (at < 0) {
                continue;
            }
            int start = at + key.length();
            int end = start;
            while (end < body.length() && body.charAt(end) != '&' && body.charAt(end) != '\n') {
                end++;
            }
            String value = body.substring(start, end).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the XML

    /**
     * One element, with its value escaped.
     *
     * <p>Ampersands first, or escaping the others would be undone by it. Without
     * this an operator whose plan is called "Fast & Cheap" gets
     * {@code 804 Error in XML} and no payment, which reads as the gateway being
     * broken rather than as a punctuation problem.
     */
    static String xml(String name, String value) {
        String v = value == null ? "" : value;
        String escaped = v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
        return "<" + name + ">" + escaped + "</" + name + ">";
    }

    /**
     * DPO's answer, parsed by a parser that will not fetch anything.
     *
     * <p>Every one of these settings is load bearing. An XML parser at its
     * defaults resolves entities a document declares, so a response naming
     * {@code file:///etc/passwd} would have it read off this server, and one
     * naming a slow URL would hang the thread. Disabling doctypes alone stops
     * both, and the rest are belt and braces for a parser that ignores it.
     *
     * <p>Package-private so a test can feed it a document that tries.
     */
    static Document parse(byte[] response) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(response));
    }

    /** The first element of that name, or null. DPO's documents are flat. */
    static String text(Document document, String name) {
        if (document == null) {
            return null;
        }
        NodeList found = document.getElementsByTagName(name);
        if (found.getLength() == 0) {
            return null;
        }
        String value = found.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---------------------------------------------------------------- plumbing

    private Document send(String body, String what) {
        byte[] answer;
        try {
            answer = RestClient.create(endpoints.dpo()).post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_XML)
                    .body(body)
                    .retrieve()
                    .onStatus(any -> true, (req, res) -> { })
                    .body(byte[].class);
        } catch (Exception e) {
            log.warn("DPO could not {}: {}", what, e.getMessage());
            throw new IllegalStateException("Could not reach DPO. Please try again.");
        }
        if (answer == null || answer.length == 0) {
            throw new IllegalStateException("DPO sent nothing back");
        }
        try {
            return parse(answer);
        } catch (Exception e) {
            log.warn("DPO sent something that is not XML while trying to {}", what);
            throw new IllegalStateException("DPO sent an answer we could not read");
        }
    }

    /**
     * DPO's own explanation, and its code when it gave none.
     *
     * <p>The codes seen firsthand against the live API: 801 missing company
     * token, 802 company is not active, 803 no request or bad request name, 804
     * error in XML. All four are setup problems rather than payment problems, and
     * naming them is the difference between a fixable message and a shrug.
     */
    private static String refusal(Document response, String result) {
        String explanation = text(response, "ResultExplanation");
        if (explanation != null && !explanation.isBlank()) {
            return "DPO: " + explanation
                    + ("802".equals(result)
                    ? " — check the company token, and that DPO has activated the account."
                    : "");
        }
        return result == null || result.isBlank()
                ? "DPO refused the transaction"
                : "DPO refused the transaction (" + result + ")";
    }

    private static BigDecimal amountOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstOf(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
