package com.spalimited.hotspotbilling.service.payments;

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

import javax.crypto.Cipher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Vodacom M-Pesa over a real socket.
 *
 * <p>This rail has two properties that make it worth exercising harder than the
 * others. It has no webhook, so the query is not a safety net but the entire
 * settlement path — every voucher it ever sells is issued by the sweep coming
 * back and asking. And its charge call blocks while the customer types their
 * PIN, which makes an unanswered request genuinely ambiguous: the money may
 * already have moved.
 *
 * <p>The test holds a real RSA key pair and decrypts what the provider sends,
 * because "did it encrypt the right thing under the right padding" is not
 * something asserting on a base64 blob can answer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VodacomMpesaHttpTest {

    private static final String API_KEY = "e4a3c1f0b27d4e8fa1c6";
    private static final String SESSION_ID = "0e29a5b1c7f24d6f9b3a8e5d2c4f7a10";

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private KeyPair keys;
    private FakeGateway vodacom;
    private VodacomMpesaProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();

        vodacom = new FakeGateway();
        PaymentEndpoints endpoints = new PaymentEndpoints();
        // The market and the environment are both path segments, so the stand-in
        // has to sit in front of the whole thing rather than in front of a host.
        ReflectionTestUtils.setField(endpoints, "vodacom", vodacom.url());

        configured(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        country("TZ");
        provider = new VodacomMpesaProvider(gateways, portalSettings, endpoints);
    }

    private void configured(String publicKey) {
        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.VODACOM_MPESA)
                .active(true)
                .environment(PaymentGateway.Environment.SANDBOX)
                .secretKey(API_KEY)
                .publicKey(publicKey)
                .shortCode("000000")
                .build()));
    }

    private void country(String code) {
        when(portalSettings.settings()).thenReturn(PortalSettings.builder()
                .country(code).currencyCode(code.equals("TZ") ? "TZS" : "MZN").build());
    }

    @AfterEach
    void tearDown() {
        vodacom.close();
    }

    /** The sandbox path Vodacom actually serves Tanzania on. */
    private static final String BASE = "/sandbox/ipg/v2/vodacomTZN";

    private void sessionOpens() {
        vodacom.on("GET " + BASE + "/getSession/", """
                {"output_ResponseCode":"INS-0","output_ResponseDesc":"Request processed successfully",
                 "output_SessionID":"%s"}""".formatted(SESSION_ID));
    }

    private static PaymentProvider.ChargeRequest request() {
        return new PaymentProvider.ChargeRequest(
                "+255 744 553 344", null, new BigDecimal("2000"), "TZS", "HS-31",
                "1 hour of WiFi");
    }

    /** What the provider put in an Authorization header, in the clear. */
    private String decrypt(String authorizationHeader) throws Exception {
        String base64 = authorizationHeader.replace("Bearer ", "");
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate());
        return new String(cipher.doFinal(Base64.getDecoder().decode(base64)),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ session

    @Test
    @DisplayName("The API key is encrypted under Vodacom's key to open a session")
    void sessionIsOpenedWithAnEncryptedApiKey() throws Exception {
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/",
                """
                {"output_ResponseCode":"INS-0","output_ResponseDesc":"Request processed successfully",
                 "output_TransactionID":"6GC8ZQBJ"}""");

        provider.charge(request());

        // The header is not the API key. It is the API key encrypted under
        // Vodacom's public key with PKCS1 padding -- and the only way to prove
        // that is to decrypt it with the private half.
        String header = vodacom.call(BASE + "/getSession/").header("Authorization");
        assertThat(header).startsWith("Bearer ");
        assertThat(header).doesNotContain(API_KEY);
        assertThat(decrypt(header)).isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("The session id is encrypted too, never sent as it came")
    void theSessionIdIsEncryptedBeforeItIsUsed() throws Exception {
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/",
                """
                {"output_ResponseCode":"INS-0","output_TransactionID":"6GC8ZQBJ"}""");

        provider.charge(request());

        // A request carrying the session id plainly is refused, with an error
        // that says nothing about encryption. This is the second RSA pass.
        String header = vodacom.call(BASE + "/c2bPayment/singleStage/").header("Authorization");
        assertThat(header).doesNotContain(SESSION_ID);
        assertThat(decrypt(header)).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("A session Vodacom refuses is reported with its own words")
    void refusedSessionSurfaces() {
        // Vodacom answers a refused session with a non-2xx status and the reason
        // in the body. Letting the status throw would discard the one sentence
        // that says what is wrong.
        vodacom.on("GET " + BASE + "/getSession/", 401, """
                {"output_ResponseCode":"INS-2001","output_ResponseDesc":"Initiator authentication error."}""");

        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Initiator authentication error");
        // Nothing was sent, so nothing can have been collected.
        assertThat(vodacom.calls()).noneMatch(c -> c.path().contains("c2bPayment"));
    }

    @Test
    @DisplayName("One session is opened, not one per call")
    void theSessionIsReused() {
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/",
                """
                {"output_ResponseCode":"INS-0","output_TransactionID":"6GC8ZQBJ"}""");

        provider.charge(request());
        provider.charge(request());

        assertThat(vodacom.calls().stream().filter(c -> c.path().endsWith("/getSession/")).count())
                .as("a session per charge is slow and Vodacom rate-limits it")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------- charge

    @Test
    @DisplayName("The charge goes to the right market with the right money")
    void chargeBody() {
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/",
                """
                {"output_ResponseCode":"INS-0","output_TransactionID":"6GC8ZQBJ"}""");

        PaymentProvider.Charge charge = provider.charge(request());

        String body = vodacom.call(BASE + "/c2bPayment/singleStage/").body();
        // Major units. 200000 here is a hundred times the price, unattended.
        assertThat(body).containsPattern("\"input_Amount\":\"?2000\\.00\"?");
        assertThat(body).contains("\"input_Country\":\"TZN\"");
        assertThat(body).contains("\"input_Currency\":\"TZS\"");
        assertThat(body).contains("\"input_ServiceProviderCode\":\"000000\"");
        // No page: the customer is already looking at a PIN prompt.
        assertThat(charge.checkoutUrl()).isNull();
    }

    @Test
    @DisplayName("The number goes international, which is the opposite of Airtel")
    void msisdnIsInternational() {
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/",
                """
                {"output_ResponseCode":"INS-0","output_TransactionID":"6GC8ZQBJ"}""");

        provider.charge(request());

        // Airtel wants 744553344; Vodacom wants 255744553344. The two providers
        // sit next to each other and the wrong one is a prompt that is accepted
        // and then finds nobody.
        String body = vodacom.call(BASE + "/c2bPayment/singleStage/").body();
        assertThat(body).contains("\"input_CustomerMSISDN\":\"255744553344\"");
    }

    @Test
    @DisplayName("A refusal Vodacom is sure about reaches the customer")
    void definiteRefusalThrows() {
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/", 400, """
                {"output_ResponseCode":"INS-2006","output_ResponseDesc":"Insufficient balance"}""");

        // The customer is standing there. Telling them beats a spinner.
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient balance");
    }

    @Test
    @DisplayName("A charge with no answer is not called failed")
    void ambiguousChargeDoesNotThrow() {
        sessionOpens();
        // INS-9 is "no answer from the handset yet". The customer may still be
        // looking for their phone. Throwing marks the payment failed, so a
        // customer who then pays would be given nothing.
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/", 408, """
                {"output_ResponseCode":"INS-9","output_ResponseDesc":"Request timeout"}""");

        PaymentProvider.Charge charge = provider.charge(request());

        assertThat(charge.providerRef()).isNotBlank();
    }

    @Test
    @DisplayName("A charge Vodacom has already seen is asked about, not failed")
    void duplicateIsNotAFailure() {
        sessionOpens();
        // INS-10 means this reference already reached them -- which means it may
        // already have been paid. It is the single worst code to treat as a
        // failure.
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/", 409, """
                {"output_ResponseCode":"INS-10","output_ResponseDesc":"Duplicate Transaction"}""");

        assertThat(provider.charge(request()).providerRef()).isNotBlank();
    }

    @Test
    @DisplayName("A charge that is never answered leaves a reference to ask about")
    void aDeadConnectionStillLeavesAHandle() {
        sessionOpens();
        // No route registered for the charge: the fake gateway answers 418, which
        // is not Vodacom answering anything. The payment may or may not exist.
        PaymentProvider.Charge charge = provider.charge(request());

        // The reference is deterministic from ours, so the sweep can ask about a
        // charge whose response never arrived.
        assertThat(charge.providerRef()).isEqualTo("HSZ31");
    }

    // --------------------------------------------------------------------- poll

    @Test
    @DisplayName("A completed transaction settles, and the query names our reference")
    void completedSettles() {
        sessionOpens();
        vodacom.on("GET " + BASE + "/queryTransactionStatus/", """
                {"output_ResponseCode":"INS-0","output_ResponseTransactionStatus":"Completed",
                 "output_TransactionID":"6GC8ZQBJ"}""");

        Optional<PaymentProvider.Settlement> settled = provider.poll("HSZ31");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().receipt()).isEqualTo("6GC8ZQBJ");
        // The whole lookup. Vodacom has no id of its own until the charge
        // answers, so ours is what a query about it has to quote.
        String query = vodacom.call(BASE + "/queryTransactionStatus/").query();
        assertThat(query).contains("input_QueryReference=HSZ31");
        assertThat(query).contains("input_ServiceProviderCode=000000");
        assertThat(query).contains("input_Country=TZN");
    }

    @Test
    @DisplayName("A transaction still pending is not reported as unpaid")
    void pendingIsNotAFailure() {
        sessionOpens();
        vodacom.on("GET " + BASE + "/queryTransactionStatus/", """
                {"output_ResponseCode":"INS-0","output_ResponseTransactionStatus":"Pending"}""");

        // This rail has no webhook to correct a wrong verdict. Calling a pending
        // transaction unpaid cancels a sale from a customer who is mid-PIN, and
        // nothing would ever revisit it.
        assertThat(provider.poll("HSZ31")).isEmpty();
    }

    @Test
    @DisplayName("A failed transaction settles as failed, with the reason")
    void failedSettles() {
        sessionOpens();
        vodacom.on("GET " + BASE + "/queryTransactionStatus/", """
                {"output_ResponseCode":"INS-0","output_ResponseTransactionStatus":"Failed",
                 "output_ResponseDesc":"Transaction cancelled by customer"}""");

        Optional<PaymentProvider.Settlement> settled = provider.poll("HSZ31");

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isFalse();
        assertThat(settled.get().failureReason()).contains("cancelled");
    }

    @Test
    @DisplayName("An answered query is not itself a payment")
    void theEnvelopeIsNotTheVerdict() {
        sessionOpens();
        // INS-0 on the query means the question was answered. Reading it as the
        // outcome would issue a voucher for every transaction anyone asked about.
        vodacom.on("GET " + BASE + "/queryTransactionStatus/", """
                {"output_ResponseCode":"INS-0","output_ResponseTransactionStatus":"Failed"}""");

        assertThat(provider.poll("HSZ31")).isPresent();
        assertThat(provider.poll("HSZ31").get().paid()).isFalse();
    }

    @Test
    @DisplayName("A query Vodacom could not answer leaves the payment alone")
    void unanswerableQueryIsEmpty() {
        sessionOpens();
        vodacom.on("GET " + BASE + "/queryTransactionStatus/", 500, """
                {"output_ResponseCode":"INS-1","output_ResponseDesc":"Internal Error"}""");

        assertThat(provider.poll("HSZ31")).isEmpty();
    }

    // ----------------------------------------------------------------- webhooks

    @Test
    @DisplayName("Nothing may settle this rail by posting at it")
    void thereIsNoWebhook() {
        // Vodacom does not call back. An endpoint that believed an unsigned body
        // naming a reference would be a free-internet generator.
        assertThatThrownBy(() -> provider.settle("{}".getBytes(StandardCharsets.UTF_8), java.util.Map.of()))
                .hasMessageContaining("no webhook");
    }

    // ------------------------------------------------------------------ markets

    @Test
    @DisplayName("Mozambique goes to the Mozambican path, not the Tanzanian one")
    void marketPicksThePath() {
        country("MZ");
        vodacom.on("GET /sandbox/ipg/v2/vodacomMOZ/getSession/", """
                {"output_ResponseCode":"INS-0","output_SessionID":"%s"}""".formatted(SESSION_ID));
        vodacom.on("POST /sandbox/ipg/v2/vodacomMOZ/c2bPayment/singleStage/", """
                {"output_ResponseCode":"INS-0","output_TransactionID":"6GC8ZQBJ"}""");

        provider.charge(request());

        // A Tanzanian key against the Mozambican path fails as an authentication
        // error rather than as a wrong address, so this is worth pinning.
        String body = vodacom.call("/sandbox/ipg/v2/vodacomMOZ/c2bPayment/singleStage/").body();
        assertThat(body).contains("\"input_Country\":\"MOZ\"");
        assertThat(body).contains("\"input_Currency\":\"MZN\"");
    }

    @Test
    @DisplayName("A country Vodacom M-Pesa does not reach is not offered it")
    void outsideTheMarketsItIsNotOffered() {
        country("KE");

        // Kenya has M-Pesa and it is Safaricom's, on a different platform with
        // different credentials. Offering this there would show a customer a way
        // to pay that cannot take their money.
        assertThat(provider.usable()).isFalse();
        assertThatThrownBy(() -> provider.charge(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(vodacom.calls()).isEmpty();
    }

    @Test
    @DisplayName("A public key pasted out of a web page still works")
    void aWrappedPublicKeyIsAccepted() throws Exception {
        // Copied from the portal it arrives with line breaks in it, and sometimes
        // with PEM headers. Refusing it would tell an operator who pasted their
        // key correctly that it is wrong.
        String wrapped = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        configured(wrapped);
        sessionOpens();
        vodacom.on("POST " + BASE + "/c2bPayment/singleStage/", """
                {"output_ResponseCode":"INS-0","output_TransactionID":"6GC8ZQBJ"}""");

        provider.charge(request());

        assertThat(decrypt(vodacom.call(BASE + "/getSession/").header("Authorization")))
                .isEqualTo(API_KEY);
    }

    @Test
    @DisplayName("An unreadable public key stops the rail rather than every payment")
    void aBrokenPublicKeyMakesItUnusable() {
        configured("not-a-key");

        // Better to be switched off with a line in the log than to be offered
        // and fail every charge with an error about credentials.
        assertThat(provider.usable()).isFalse();
    }
}
