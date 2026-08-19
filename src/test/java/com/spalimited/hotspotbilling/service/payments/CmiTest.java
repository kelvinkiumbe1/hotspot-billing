package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CMI's signature, which is the whole of the security.
 *
 * <p>Nothing here talks to CMI — it is a browser form post, so there is no socket
 * to stand a fake server in front of. What there is, is a hash: every field
 * sorted, escaped and salted with the store key, in both directions. Get it wrong
 * and CMI refuses every payment, or a forged result is believed.
 *
 * <p>So the expected hashes here are computed longhand rather than by calling the
 * code under test, the same way the Paymob signature is. A test that hashes with
 * the implementation proves the two agree and nothing about whether either is
 * right.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CmiTest {

    private static final String CLIENT_ID = "600001234";
    private static final String STORE_KEY = "TESTSTOREKEY123";

    @Mock private PaymentGatewayService gateways;
    @Mock private PortalSettingsService portalSettings;

    private CmiProvider provider;

    @BeforeEach
    void setUp() {
        when(gateways.find(any())).thenReturn(Optional.of(PaymentGateway.builder()
                .kind(PaymentGateway.Kind.CMI)
                .active(true)
                .shortCode(CLIENT_ID)
                .secretKey(STORE_KEY)
                .build()));
        country("MA", "MAD");
        PaymentEndpoints endpoints = new PaymentEndpoints();
        org.springframework.test.util.ReflectionTestUtils.setField(
                endpoints, "cmi", "https://payment.cmi.co.ma/fim/est3Dgate");
        PublicUrls urls = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://isp.example.ma/api/payments/mpesa/callback", null));
        provider = new CmiProvider(gateways, portalSettings, endpoints, urls);
    }

    private void country(String code, String currency) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(code).currencyCode(currency).build());
    }

    /** The ver3 hash, spelled out rather than delegated. */
    private static String sign(String... sortedValuesThenKey) {
        String joined = String.join("|", sortedValuesThenKey);
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            return Base64.getEncoder().encodeToString(
                    sha512.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------- the hash

    @Test
    @DisplayName("Fields are sorted by name without regard to case")
    void sortingIgnoresCase() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("oid", "HS-1");
        fields.put("BillToName", "WiFi Customer");
        fields.put("amount", "10.00");

        // Sorted case-insensitively: amount, BillToName, oid. Sorting by byte
        // value would put BillToName first -- every capital before every
        // lowercase -- which is a different order and a different hash, and CMI
        // would refuse the payment with nothing useful to say about why.
        assertThat(CmiProvider.hash(fields, STORE_KEY))
                .isEqualTo(sign("10.00", "WiFi Customer", "HS-1", STORE_KEY));
    }

    @Test
    @DisplayName("The hash itself and the encoding are left out of it")
    void hashAndEncodingAreExcluded() {
        Map<String, String> withExtras = new LinkedHashMap<>();
        withExtras.put("amount", "10.00");
        withExtras.put("encoding", "UTF-8");
        withExtras.put("hash", "whatever-was-there-before");

        // Including either produces a hash CMI cannot reproduce, and on the way
        // back it would mean hashing the signature into its own verification.
        assertThat(CmiProvider.hash(withExtras, STORE_KEY))
                .isEqualTo(sign("10.00", STORE_KEY));
    }

    @Test
    @DisplayName("A pipe in a value cannot shift the fields after it")
    void pipesAreEscaped() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("a", "one|two");
        fields.put("b", "three");

        // Unescaped, "one|two" would read as two fields and everything after it
        // would land a position out. That is a hash mismatch nobody could
        // diagnose from CMI's error.
        assertThat(CmiProvider.hash(fields, STORE_KEY))
                .isEqualTo(sign("one\\|two", "three", STORE_KEY));
    }

    @Test
    @DisplayName("A backslash is escaped before the pipe, not after")
    void backslashesAreEscapedFirst() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("a", "back\\slash");

        // Doing the pipe first and the backslash second would escape the escape
        // character that the pipe rule had just written.
        assertThat(CmiProvider.hash(fields, STORE_KEY))
                .isEqualTo(sign("back\\\\slash", STORE_KEY));
    }

    @Test
    @DisplayName("The store key is salted in last")
    void storeKeyGoesLast() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("zzz", "last-field");

        // Even after a field that sorts after it alphabetically. The key is
        // appended, not sorted in.
        assertThat(CmiProvider.hash(fields, STORE_KEY))
                .isEqualTo(sign("last-field", STORE_KEY));
    }

    // ------------------------------------------------------------- the form

    @Test
    @DisplayName("The form carries what CMI needs and is signed over exactly it")
    void formIsComplete() {
        Optional<CmiProvider.Form> form = provider.form(
                "HS-31", new BigDecimal("120"), "212612345678", null);

        assertThat(form).isPresent();
        Map<String, String> f = form.get().fields();
        assertThat(form.get().action()).isEqualTo("https://payment.cmi.co.ma/fim/est3Dgate");
        assertThat(f).containsEntry("clientid", CLIENT_ID);
        assertThat(f).containsEntry("oid", "HS-31");
        assertThat(f).containsEntry("amount", "120.00");
        // The numeric code, not "MAD". CMI takes 504.
        assertThat(f).containsEntry("currency", "504");
        // CMI hosts the card form, so no card number ever reaches this server.
        assertThat(f).containsEntry("storetype", "3D_PAY_HOSTING");
        assertThat(f).containsEntry("hashAlgorithm", "ver3");
        assertThat(f.get("okUrl")).isEqualTo("https://isp.example.ma/api/payments/cmi/return");

        // And the hash in it is the hash of everything else in it.
        Map<String, String> withoutHash = new LinkedHashMap<>(f);
        String claimed = withoutHash.remove("hash");
        assertThat(claimed).isEqualTo(CmiProvider.hash(withoutHash, STORE_KEY));
    }

    @Test
    @DisplayName("Two attempts are not identically signed")
    void everyAttemptIsFreshlySigned() {
        String first = provider.form("HS-31", new BigDecimal("120"), "212612345678", null)
                .orElseThrow().fields().get("hash");
        String second = provider.form("HS-31", new BigDecimal("120"), "212612345678", null)
                .orElseThrow().fields().get("hash");

        // rnd is folded in, so a replay of one attempt's fields is not a valid
        // signature for the next.
        assertThat(first).isNotEqualTo(second);
    }

    // ----------------------------------------------------------- the result

    /** A result posted back by CMI, correctly signed. */
    private Map<String, String> result(String procReturnCode, String response) {
        Map<String, String> posted = new LinkedHashMap<>();
        posted.put("oid", "HS-31");
        posted.put("amount", "120.00");
        posted.put("Response", response);
        posted.put("ProcReturnCode", procReturnCode);
        posted.put("TransId", "T-9911");
        posted.put("AuthCode", "A1234");
        posted.put("clientid", CLIENT_ID);
        posted.put("HASH", CmiProvider.hash(posted, STORE_KEY));
        return posted;
    }

    @Test
    @DisplayName("A signed approval settles")
    void approvedResultSettles() {
        Optional<PaymentProvider.Settlement> settled = provider.settleForm(result("00", "Approved"));

        assertThat(settled).isPresent();
        assertThat(settled.get().paid()).isTrue();
        assertThat(settled.get().reference()).isEqualTo("HS-31");
        assertThat(settled.get().receipt()).isEqualTo("T-9911");
        assertThat(settled.get().amount()).isEqualByComparingTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("Both the response and the return code have to agree")
    void oneCheerfulFieldIsNotEnough() {
        // A declined card can arrive with one of the two looking fine. Reading
        // either alone gives internet away.
        assertThat(provider.settleForm(result("00", "Declined")).orElseThrow().paid()).isFalse();
        assertThat(provider.settleForm(result("99", "Approved")).orElseThrow().paid()).isFalse();
        assertThat(provider.settleForm(result("00", "Approved")).orElseThrow().paid()).isTrue();
    }

    @Test
    @DisplayName("A forged result is refused")
    void forgedResultIsRefused() {
        Map<String, String> posted = result("00", "Approved");
        posted.put("HASH", "not-the-right-hash");

        // This settles payments, so an unverified post is a free-internet
        // generator for anybody who learns the URL.
        assertThatThrownBy(() -> provider.settleForm(posted))
                .hasMessageContaining("signature did not match");
    }

    @Test
    @DisplayName("A result with the amount raised in flight is refused")
    void tamperedFieldsAreRefused() {
        Map<String, String> posted = result("00", "Approved");
        posted.put("amount", "1.00");

        // The hash covers every field, so changing any of them after signing
        // invalidates the lot. This is the check that stops a customer editing
        // the amount on the way back.
        assertThatThrownBy(() -> provider.settleForm(posted))
                .hasMessageContaining("signature did not match");
    }

    @Test
    @DisplayName("An unsigned result is refused")
    void unsignedResultIsRefused() {
        Map<String, String> posted = result("00", "Approved");
        posted.remove("HASH");

        assertThatThrownBy(() -> provider.settleForm(posted))
                .hasMessageContaining("no signature");
    }

    @Test
    @DisplayName("There is no JSON webhook to post at")
    void thereIsNoJsonWebhook() {
        assertThatThrownBy(() -> provider.settle("{}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .hasMessageContaining("signed form");
    }

    // ------------------------------------------------------------- the rest

    @Test
    @DisplayName("The customer is sent to us, not to CMI, because CMI needs a POST")
    void chargeReturnsOurOwnRedirectPage() {
        PaymentProvider.Charge charge = provider.charge(new PaymentProvider.ChargeRequest(
                "212612345678", null, new BigDecimal("120"), "MAD", "HS-31", "WiFi"));

        // checkoutUrl can only be somewhere to send a browser with a GET.
        assertThat(charge.checkoutUrl())
                .isEqualTo("https://isp.example.ma/api/payments/cmi/redirect?ref=HS-31");
        assertThat(charge.providerRef()).isEqualTo("HS-31");
    }

    @Test
    @DisplayName("With no public address it refuses rather than stranding a payment")
    void noPublicAddressRefuses() {
        PublicUrls none = new PublicUrls(new MpesaProperties(
                null, null, null, null, null,
                "https://example.com/api/payments/mpesa/callback", null));
        PaymentEndpoints endpoints = new PaymentEndpoints();
        CmiProvider stranded = new CmiProvider(gateways, portalSettings, endpoints, none);

        // Unlike Konnect there is nothing to ask, so a payment with nowhere to be
        // posted back to could never be settled at all.
        assertThat(stranded.usable()).isFalse();
        assertThatThrownBy(() -> stranded.charge(new PaymentProvider.ChargeRequest(
                "212612345678", null, new BigDecimal("120"), "MAD", "HS-31", "WiFi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public address");
    }

    @Test
    @DisplayName("Outside Morocco it is not offered")
    void outsideMoroccoItIsNotOffered() {
        country("KE", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(provider.form("HS-31", new BigDecimal("120"), "254712345678", null)).isEmpty();
    }

    @Test
    @DisplayName("Prices in the wrong currency stop it rather than mis-charging")
    void currencyMustAgree() {
        country("MA", "KES");

        assertThat(provider.usable()).isFalse();
        assertThat(provider.form("HS-31", new BigDecimal("120"), "212612345678", null)).isEmpty();
    }

    @Test
    @DisplayName("Nothing about this rail claims to be pollable")
    void notPollable() {
        assertThat(provider.pollable()).isFalse();
    }
}
