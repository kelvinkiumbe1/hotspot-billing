package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave — the one wallet rail here that signs its webhooks, and the two status
 * fields that are easy to confuse.
 */
class WaveProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "wave_whsec_test";

    private final WaveProvider provider = new WaveProvider(null, null, null, MAPPER);

    @Test
    @DisplayName("A succeeded payment settles with its amount")
    void succeededIsPaid() {
        Optional<PaymentProvider.Settlement> s = read("""
                {"id":"cos-1","client_reference":"HS-9","amount":"2500","currency":"XOF",
                 "payment_status":"succeeded","checkout_status":"complete",
                 "transaction_id":"T-1"}""");

        assertThat(s).isPresent();
        assertThat(s.get().paid()).isTrue();
        assertThat(s.get().reference()).isEqualTo("HS-9");
        assertThat(s.get().amount()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(s.get().receipt()).isEqualTo("T-1");
    }

    @Test
    @DisplayName("A complete page with a processing payment is not a sale")
    void completePageIsNotPaidMoney() {
        // The two fields are not interchangeable and this is the trap: the page
        // is finished, the money is not. Reading the page as the money issues a
        // voucher for nothing.
        assertThat(read("""
                {"id":"cos-2","amount":"100","payment_status":"processing",
                 "checkout_status":"complete"}""")).isEmpty();
    }

    @Test
    @DisplayName("Cancelled and expired are failures, still processing is neither")
    void otherStates() {
        assertThat(read("""
                {"id":"c","amount":"1","payment_status":"cancelled","checkout_status":"open"}""")
                .get().paid()).isFalse();
        assertThat(read("""
                {"id":"c","amount":"1","payment_status":"processing","checkout_status":"expired"}""")
                .get().paid()).isFalse();
        assertThat(read("""
                {"id":"c","amount":"1","payment_status":"processing","checkout_status":"open"}"""))
                .isEmpty();
    }

    @Test
    @DisplayName("An unreadable amount is zero, not null")
    void unreadableAmountIsZeroNotNull() {
        // Null would now mean "this rail does not report amounts", which skips
        // the amount check. For a rail that settles from a body the check has to
        // stay, so a broken amount must fail it rather than bypass it.
        assertThat(read("""
                {"id":"c","amount":"not-a-number","payment_status":"succeeded",
                 "checkout_status":"complete"}""").get().amount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- the signature ---

    @Test
    @DisplayName("A correctly signed body verifies")
    void goodSignaturePasses() {
        byte[] body = "{\"type\":\"checkout.session.completed\"}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();

        assertThatCode(() -> provider.verify(body, header(now, body), SECRET))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The timestamp is part of what is signed, not decoration")
    void timestampIsSigned() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        // A signature computed over the body alone — the mistake that makes the
        // whole scheme worthless.
        String bodyOnly = Signatures.hmacHex("HmacSHA256", SECRET, body);

        assertThatThrownBy(() -> provider.verify(body, "t=" + now + ",v1=" + bodyOnly, SECRET))
                .hasMessageContaining("no signature matched");
    }

    @Test
    @DisplayName("An old signature is refused however valid it is")
    void replayIsRefused() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long old = Instant.now().minusSeconds(60 * 60).getEpochSecond();

        // Without the window a single captured success can be posted back
        // forever, each time issuing a free voucher.
        assertThatThrownBy(() -> provider.verify(body, header(old, body), SECRET))
                .hasMessageContaining("outside the accepted time window");
    }

    @Test
    @DisplayName("Several v1 values are accepted while a secret is being rotated")
    void rotationIsAllowed() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        String good = header(now, body).split("v1=")[1];

        assertThatCode(() -> provider.verify(body, "t=" + now + ",v1=deadbeef,v1=" + good, SECRET))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A missing or malformed header is refused")
    void badHeaders() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> provider.verify(body, null, SECRET))
                .hasMessageContaining("no Wave-Signature header");
        assertThatThrownBy(() -> provider.verify(body, "nonsense", SECRET))
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> provider.verify(body, "t=later,v1=abc", SECRET))
                .hasMessageContaining("not a number");
    }

    @Test
    @DisplayName("Wave needs both its key and its webhook secret to be ready")
    void configuredNeedsBoth() {
        assertThat(PaymentGateway.builder().kind(PaymentGateway.Kind.WAVE)
                .secretKey("wave_sn_prod_x").build().isConfigured()).isFalse();
        assertThat(PaymentGateway.builder().kind(PaymentGateway.Kind.WAVE)
                .secretKey("wave_sn_prod_x").webhookSecret("s").build().isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Live or test is read off the key, not off a dropdown")
    void liveComesFromTheKey() {
        // An operator can set a dropdown to the opposite of reality and believe
        // they are collecting money.
        assertThat(PaymentGateway.builder().kind(PaymentGateway.Kind.WAVE)
                .secretKey("wave_sn_test_abc").webhookSecret("s").build().isLive()).isFalse();
        assertThat(PaymentGateway.builder().kind(PaymentGateway.Kind.WAVE)
                .secretKey("wave_sn_prod_abc").webhookSecret("s").build().isLive()).isTrue();
    }

    private static String header(long timestamp, byte[] body) {
        byte[] signed = (timestamp + "." + new String(body, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        return "t=" + timestamp + ",v1=" + Signatures.hmacHex("HmacSHA256", SECRET, signed);
    }

    private static Optional<PaymentProvider.Settlement> read(String json) {
        return WaveProvider.read(MAPPER.readTree(json));
    }
}
