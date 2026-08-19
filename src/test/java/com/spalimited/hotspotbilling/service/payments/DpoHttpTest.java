package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DPO over a real socket.
 *
 * <p>The refusal bodies here are the ones the live API returned when probed:
 * {@code 801} for a missing company token, {@code 802} for one that is not
 * active, {@code 803} for a request name that does not exist, {@code 804} for a
 * document that will not parse.
 *
 * <p>Two of these tests exist because this is the first XML in the codebase and
 * so the first time either question has had to be answered: whether a document
 * with punctuation in it can be built, and whether a hostile document can be
 * read safely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DpoHttpTest {

    private static final String COMPANY_TOKEN = "9F416C11-127C-4DE2-AC7F-D5710E4C5F0A";
    private static final String SERVICE_TYPE = "3854";

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private FakeGateway dpo;
    private DpoProvider provider;

    @BeforeEach
    void setUp() {
        dpo = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        ReflectionTestUtils.setField(endpoints, "dpo", dpo.url());
        ReflectionTestUtils.setField(endpoints, "dpoCheckout",
                "https://secure.3gdirectpay.com/payv2.php");

        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.DPO)
                .active(true)
                .secretKey(COMPANY_TOKEN)
                .shortCode(SERVICE_TYPE)
                .build()));
        country("KE", "KES");

        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://isp.example.co.ke/api/payments/mpesa/callback", null));
        provider = new DpoProvider(gateways, portalSettings, endpoints, urls);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    @AfterEach
    void tearDown() {
        dpo.close();
    }

    private void tokenCreated() {
        dpo.on("POST /", """
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>000</Result>\
                <ResultExplanation>Transaction created</ResultExplanation>\
                <TransToken>7A1BFDD1-3F0A-4C9E-9A7B-2E5D8C1F4B60</TransToken>\
                <TransRef>65158</TransRef></API3G>""");
    }

    private static PaymentProvider.ChargeRequest request(String description) {
        return new PaymentProvider.ChargeRequest(
                "254712345678", null, new BigDecimal("1500"), "KES", "HS-31", description);
    }

    // ------------------------------------------------------------------ charge

    @Test
    @DisplayName("The document carries what DPO accepted")
    void createTokenDocument() {
        tokenCreated();

        PaymentProvider.Charge charge = provider.charge(request("1 hour of WiFi"));

        // This exact shape reached 802 "Company is not active" against the live
        // API, which it could not have done had the document been malformed or an
        // element misnamed.
        String body = dpo.call("/").body();
        assertThat(body).contains("<CompanyToken>" + COMPANY_TOKEN + "</CompanyToken>");
        assertThat(body).contains("<Request>createToken</Request>");
        assertThat(body).contains("<PaymentAmount>1500.00</PaymentAmount>");
        assertThat(body).contains("<PaymentCurrency>KES</PaymentCurrency>");
        assertThat(body).contains("<CompanyRef>HS-31</CompanyRef>");
        assertThat(body).contains("<ServiceType>" + SERVICE_TYPE + "</ServiceType>");
        // 0, or DPO refuses a reference it has seen -- which would stop a
        // customer retrying after a declined card.
        assertThat(body).contains("<CompanyRefUnique>0</CompanyRefUnique>");

        assertThat(charge.providerRef()).isEqualTo("7A1BFDD1-3F0A-4C9E-9A7B-2E5D8C1F4B60");
        assertThat(charge.checkoutUrl())
                .isEqualTo("https://secure.3gdirectpay.com/payv2.php"
                        + "?ID=7A1BFDD1-3F0A-4C9E-9A7B-2E5D8C1F4B60");
    }

    @Test
    @DisplayName("An ampersand in a plan name does not break the document")
    void punctuationIsEscaped() {
        tokenCreated();

        // "Fast & Cheap" unescaped produces a document DPO answers with 804
        // Error in XML rather than a payment -- which reads as the gateway being
        // broken rather than as a punctuation problem.
        provider.charge(request("Fast & Cheap <1hr>"));

        String body = dpo.call("/").body();
        assertThat(body).contains("Fast &amp; Cheap &lt;1hr&gt;");
        assertThat(body).doesNotContain("Fast & Cheap");
    }

    @Test
    @DisplayName("Escaping does the ampersand first")
    void ampersandIsEscapedBeforeTheRest() {
        // The other order would escape the ampersand that the < rule had just
        // written, giving &amp;lt; where &lt; was meant.
        assertThat(DpoProvider.xml("X", "a<b")).isEqualTo("<X>a&lt;b</X>");
        assertThat(DpoProvider.xml("X", "a&b")).isEqualTo("<X>a&amp;b</X>");
        assertThat(DpoProvider.xml("X", "a&<b")).isEqualTo("<X>a&amp;&lt;b</X>");
        assertThat(DpoProvider.xml("X", null)).isEqualTo("<X></X>");
    }

    @Test
    @DisplayName("The customer is sent back to us when there is somewhere to send them")
    void returnUrlsPointAtUs() {
        tokenCreated();

        provider.charge(request("1 hour of WiFi"));

        assertThat(dpo.call("/").body())
                .contains("<RedirectURL>https://isp.example.co.ke/?paid=HS-31</RedirectURL>");
    }

    // -------------------------------------------------- what the live API said

    @Test
    @DisplayName("A company that is not active is named as such")
    void inactiveCompanySurfaces() {
        // Verbatim from the live API, for a well-formed request with a token that
        // does not belong to anybody.
        dpo.on("POST /", """
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>802</Result>\
                <ResultExplanation>Company is not active</ResultExplanation></API3G>""");

        assertThatThrownBy(() -> provider.charge(request("1 hour of WiFi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Company is not active")
                // And what to do about it, because "not active" alone reads as a
                // dead end.
                .hasMessageContaining("check the company token");
    }

    @Test
    @DisplayName("A missing company token is named as such")
    void missingTokenSurfaces() {
        dpo.on("POST /", """
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>801</Result>\
                <ResultExplanation>Request missing company token</ResultExplanation></API3G>""");

        assertThatThrownBy(() -> provider.charge(request("1 hour of WiFi")))
                .hasMessageContaining("Request missing company token");
    }

    @Test
    @DisplayName("A malformed document is named as such")
    void badXmlSurfaces() {
        dpo.on("POST /", """
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>804</Result>\
                <ResultExplanation>Error in XML</ResultExplanation></API3G>""");

        assertThatThrownBy(() -> provider.charge(request("1 hour of WiFi")))
                .hasMessageContaining("Error in XML");
    }

    @Test
    @DisplayName("Something that is not XML at all is refused, not guessed at")
    void nonXmlIsRefused() {
        dpo.on("POST /", 200, "<html><body>Service temporarily unavailable</body></html>");

        // An HTML error page from a proxy. It parses as XML by luck or not at
        // all; either way it says nothing about a payment.
        assertThatThrownBy(() -> provider.charge(request("1 hour of WiFi")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------- the parser

    @Test
    @DisplayName("A document that names a local file does not get to read it")
    void externalEntitiesAreNotResolved() throws Exception {
        // The classic XXE. A parser at its defaults resolves this and puts the
        // file's contents in the element, so an intercepted or malicious response
        // reads whatever this process can read. This is the first XML in the
        // codebase and therefore the first time that mattered.
        Path secret = Files.createTempFile("dpo-xxe", ".txt");
        Files.writeString(secret, "TOP-SECRET-CONTENTS");
        String hostile = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE API3G [<!ENTITY leak SYSTEM "file:///%s">]>
                <API3G><Result>000</Result><TransToken>&leak;</TransToken></API3G>"""
                .formatted(secret.toString().replace("\\", "/"));

        // Doctypes are disallowed outright, so this does not parse at all --
        // which is the safest possible outcome. What must never happen is it
        // parsing and the file coming back inside TransToken.
        assertThatThrownBy(() -> DpoProvider.parse(hostile.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
        Files.deleteIfExists(secret);
    }

    @Test
    @DisplayName("A hostile response cannot leak a file through a charge either")
    void xxeThroughTheChargePathIsAlsoStopped() throws Exception {
        Path secret = Files.createTempFile("dpo-xxe2", ".txt");
        Files.writeString(secret, "TOP-SECRET-CONTENTS");
        dpo.on("POST /", ("""
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE API3G [<!ENTITY leak SYSTEM "file:///%s">]>
                <API3G><Result>000</Result><TransToken>&leak;</TransToken></API3G>""")
                .formatted(secret.toString().replace("\\", "/")));

        // The whole path, not just the parser: a charge against a hostile
        // response must fail rather than return a token containing the file.
        assertThatThrownBy(() -> provider.charge(request("1 hour of WiFi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("TOP-SECRET-CONTENTS");
        Files.deleteIfExists(secret);
    }

    @Test
    @DisplayName("An ordinary document still parses")
    void ordinaryDocumentsStillParse() throws Exception {
        // The hardening must not be so aggressive that DPO's own answers stop
        // being readable.
        var document = DpoProvider.parse(("""
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>000</Result>\
                <TransToken>ABC</TransToken></API3G>""").getBytes(StandardCharsets.UTF_8));

        assertThat(DpoProvider.text(document, "Result")).isEqualTo("000");
        assertThat(DpoProvider.text(document, "TransToken")).isEqualTo("ABC");
        assertThat(DpoProvider.text(document, "NotThere")).isNull();
    }

    // ----------------------------------------------------------------- outcome

    private void verifyAnswers(String xml) {
        dpo.on("POST /", xml);
    }

    @Test
    @DisplayName("A paid transaction settles")
    void paidSettles() {
        verifyAnswers("""
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>000</Result>\
                <ResultExplanation>Transaction paid</ResultExplanation>\
                <CompanyRef>HS-31</CompanyRef><TransactionAmount>1500.00</TransactionAmount>\
                <TransactionCurrency>KES</TransactionCurrency>\
                <TransactionApproval>A99123</TransactionApproval>\
                <TransactionStatusCode>1</TransactionStatusCode>\
                <TransactionStatusDescription>Paid</TransactionStatusDescription></API3G>""");

        Optional<PaymentProvider.Settlement> settled = provider.poll("TOK-1");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().reference()).isEqualTo("HS-31");
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(settled.get().currency()).isEqualTo("KES");
        assertThat(settled.get().receipt()).isEqualTo("A99123");
        // And the query names the token rather than our reference.
        assertThat(dpo.call("/").body()).contains("<TransactionToken>TOK-1</TransactionToken>");
        assertThat(dpo.call("/").body()).contains("<Request>verifyToken</Request>");
    }

    @Test
    @DisplayName("Not paid yet is not the same as failed")
    void notPaidYetWaits() {
        // 900 is DPO's answer for a transaction it has not been paid for *yet*.
        // Reading it as a failure cancels a sale from a customer still on the
        // payment page, and this rail has no signed callback to correct it.
        verifyAnswers("""
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>900</Result>\
                <ResultExplanation>Transaction not paid yet</ResultExplanation></API3G>""");

        assertThat(provider.poll("TOK-2")).isEmpty();
    }

    @Test
    @DisplayName("A cancelled transaction settles as unpaid")
    void cancelledSettles() {
        verifyAnswers("""
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>904</Result>\
                <ResultExplanation>Transaction cancelled by customer</ResultExplanation>\
                <CompanyRef>HS-31</CompanyRef></API3G>""");

        Optional<PaymentProvider.Settlement> settled = provider.poll("TOK-3");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isFalse();
        assertThat(settled.get().failureReason()).contains("cancelled");
    }

    @Test
    @DisplayName("An unsigned notification settles only what DPO confirms")
    void notificationIsOnlyAHint() {
        verifyAnswers("""
                <?xml version="1.0" encoding="utf-8"?><API3G><Result>900</Result>\
                <ResultExplanation>Transaction not paid yet</ResultExplanation></API3G>""");

        // The body claims a paid transaction. DPO signs nothing, so it is read
        // only for the token -- and DPO says it is not paid.
        Optional<PaymentProvider.Settlement> settled = provider.settle(
                ("<API3G><TransactionToken>TOK-4</TransactionToken>"
                        + "<Result>000</Result></API3G>").getBytes(StandardCharsets.UTF_8),
                Map.of());

        assertThat(settled).isEmpty();
        assertThat(dpo.call("/").body()).contains("<TransactionToken>TOK-4</TransactionToken>");
    }

    @Test
    @DisplayName("A token is found in XML or in form fields")
    void tokenIsFoundEitherWay() {
        // DPO's notification format is a dashboard setting rather than something
        // we ask for, so both shapes have to be understood.
        assertThat(DpoProvider.tokenIn(
                "<API3G><TransactionToken>ABC</TransactionToken></API3G>"
                        .getBytes(StandardCharsets.UTF_8))).isEqualTo("ABC");
        assertThat(DpoProvider.tokenIn(
                "TransactionToken=DEF&CompanyRef=HS-1".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("DEF");
        assertThat(DpoProvider.tokenIn("nothing useful".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    @DisplayName("A notification naming no token is refused rather than guessed at")
    void notificationWithoutATokenIsRefused() {
        assertThatThrownBy(() -> provider.settle("{}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .hasMessageContaining("no transaction token");
    }

    // ------------------------------------------------------------------ market

    @Test
    @DisplayName("Mauritius and Namibia are served, which is the point of adding this")
    void theTwoThatHadNothing() {
        for (String code : new String[]{"MU", "NA"}) {
            country(code, code.equals("MU") ? "MUR" : "NAD");
            assertThat(provider.usable()).as(code).isTrue();
        }
    }

    @Test
    @DisplayName("Outside DPO's nineteen it is not offered")
    void outsideTheMarketsItIsNotOffered() {
        // Somalia, which DPO does not serve.
        country("SO", "USD");

        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request("1 hour of WiFi")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dpo.calls()).isEmpty();
    }

    @Test
    @DisplayName("The amount goes in whatever the operator prices in")
    void currencyFollowsTheOperator() {
        // No single-currency guard, unlike the one-country rails: DPO settles in
        // whatever its merchant account is denominated in, which is the
        // operator's own currency by construction.
        country("ZA", "ZAR");
        tokenCreated();

        provider.charge(new PaymentProvider.ChargeRequest(
                "27821234567", null, new BigDecimal("299"), "ZAR", "HS-32", "Fibre"));

        assertThat(dpo.call("/").body()).contains("<PaymentCurrency>ZAR</PaymentCurrency>");
        assertThat(dpo.call("/").body()).contains("<PaymentAmount>299.00</PaymentAmount>");
    }

    @Test
    @DisplayName("Asking DPO is what settles this rail")
    void pollable() {
        assertThat(provider.pollable()).isTrue();
    }
}
